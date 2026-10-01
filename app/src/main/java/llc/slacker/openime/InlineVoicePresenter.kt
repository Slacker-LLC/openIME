package llc.slacker.openime

import android.content.res.ColorStateList
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

internal class InlineVoicePresenter(
    private val handler: Handler,
    private val toPx: (Int) -> Int,
    private val zone: () -> LinearLayout,
    private val icon: () -> ImageView,
    private val status: () -> TextView,
    private val waves: () -> List<View>,
    private val tokens: () -> ImeTheme.Tokens,
    private val isGestureSessionActive: () -> Boolean,
    private val isComposing: () -> Boolean,
    private val updateTopZone: (Boolean) -> Unit,
) {
    var active: Boolean = false
        private set

    private var cancelling = false
    private var error = false
    private var paletteColor: Int? = null
    private var generation = 0L
    private var hasLiveRms = false
    private var pulseFrame = 0

    private val pulseAction = object : Runnable {
        override fun run() {
            if (!active || !isGestureSessionActive() || hasLiveRms) return
            waves().forEachIndexed { index, bar ->
                val phase = (pulseFrame + index * 2) % 12
                val distance = abs(phase - 6)
                val params = bar.layoutParams
                params.height = toPx((7 + (6 - distance) * 3).coerceIn(7, 25))
                bar.layoutParams = params
            }
            pulseFrame = (pulseFrame + 1) % 12
            handler.postDelayed(this, 72L)
        }
    }

    fun show(
        message: String,
        cancelling: Boolean = false,
        error: Boolean = false,
        rms: Float? = null,
    ) {
        active = true
        this.cancelling = cancelling
        this.error = error

        val statusView = status()
        if (statusView.text.toString() != message) {
            statusView.text = message
            zone().contentDescription = message
        }

        if (rms != null) {
            hasLiveRms = true
            handler.removeCallbacks(pulseAction)
            val strength = (rms * 9f).coerceIn(0.08f, 1f)
            waves().forEachIndexed { index, bar ->
                val shape = when {
                    index in 2..3 -> 1f
                    index in 1..4 -> 0.72f
                    else -> 0.48f
                }
                val height = toPx(
                    (6f + 22f * strength * shape)
                        .toInt()
                        .coerceIn(6, 28),
                )
                val params = bar.layoutParams
                if (params.height != height) {
                    params.height = height
                    bar.layoutParams = params
                }
            }
        }

        refreshPalette()
        updateTopZone(false)
    }

    fun startPulse() {
        hasLiveRms = false
        pulseFrame = 0
        handler.removeCallbacks(pulseAction)
        handler.post(pulseAction)
    }

    fun stopPulse() {
        handler.removeCallbacks(pulseAction)
    }

    fun refreshPalette() {
        if (!active) return
        val palette = tokens()
        val backgroundColor =
            if (cancelling || error) palette.destructive else ImeDrawableFactory.blend(palette.primary, palette.toolbarBackground, 0.16f)
        if (paletteColor == backgroundColor) return
        paletteColor = backgroundColor

        zone().background = ImeDrawableFactory.rounded(
            backgroundColor,
            toPx(ImeGeometryTokens.CONTROL_RADIUS_DP),
        )
        val foregroundColor = palette.keyText
        zone().findViewWithTag<TextView>("voice-cancel-hint")?.apply {
            setTextColor(foregroundColor)
            background = ImeDrawableFactory.rounded(palette.functionKeyBackground, toPx(99))
        }
        icon().imageTintList = ColorStateList.valueOf(foregroundColor)
        status().setTextColor(foregroundColor)
        waves().forEach { bar ->
            bar.background = ImeDrawableFactory.rounded(
                palette.primary,
                toPx(ImeGeometryTokens.PILL_RADIUS_DP),
            )
        }
    }

    fun hide() {
        stopPulse()
        active = false
        cancelling = false
        error = false
        paletteColor = null
        updateTopZone(isComposing())
    }

    fun hideLater(
        delayMs: Long,
        canHide: () -> Boolean,
    ) {
        val scheduledGeneration = generation
        handler.postDelayed(
            {
                if (scheduledGeneration == generation && canHide()) {
                    hide()
                }
            },
            delayMs,
        )
    }

    fun invalidateGeneration() {
        generation++
    }
}
