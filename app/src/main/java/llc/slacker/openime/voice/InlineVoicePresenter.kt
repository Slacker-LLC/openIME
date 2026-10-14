package llc.slacker.openime.voice

import android.os.Handler
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import llc.slacker.openime.theme.ImeDrawableFactory
import llc.slacker.openime.theme.ImeGeometryTokens
import llc.slacker.openime.theme.ImeTheme
import kotlin.math.abs

internal class InlineVoicePresenter(
    private val handler: Handler,
    private val toPx: (Int) -> Int,
    private val zone: () -> LinearLayout,
    private val face: () -> VoiceFaceView,
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

    /**
     * Shows one message of a session, and the step of it that [state] names.
     *
     * The panel controller sends the state through [setFaceState] instead, so
     * that one state drives the panel's character and this one together; the
     * callers that show a message on their own pass it here.
     */
    fun show(
        message: String,
        cancelling: Boolean = false,
        error: Boolean = false,
        rms: Float? = null,
        state: VoiceFaceState? = null,
    ) {
        active = true
        this.cancelling = cancelling
        this.error = error
        if (state != null) face().setState(state)

        val statusView = status()
        if (statusView.text.toString() != message) {
            statusView.text = message
            zone().contentDescription = message
        }

        if (rms != null) {
            hasLiveRms = true
            face().setLevel(rms)
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

    /** Shows [state] on the character of this zone. */
    fun setFaceState(state: VoiceFaceState) {
        face().setState(state)
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
        face().applyTokens(palette)
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
        face().setState(VoiceFaceState.IDLE)
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
