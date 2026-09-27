package llc.slacker.openime

import android.content.res.Configuration
import android.os.Handler
import android.view.Gravity
import android.view.Window
import android.view.WindowManager

/**
 * Owns the real IME window's Docked/Floating presentation state.
 *
 * This is deliberately a concrete controller: it wraps Android WindowManager
 * mechanics and keeps them out of InputMethodService lifecycle/business logic.
 */
internal class FloatingWindowController(
    private val resources: android.content.res.Resources,
    private val mainHandler: Handler,
    private val windowProvider: () -> Window?,
    private val keyboardHeightPx: () -> Int?,
    private val debugLog: (String) -> Unit = {},
) {
    var enabled: Boolean = false
        private set

    private var x = 0
    private var y = 0
    private var baseGravity: Int? = null
    private var baseWidth: Int? = null
    private var baseHeight: Int? = null
    private var baseSoftInputMode: Int? = null

    fun enable(resetPosition: Boolean = x == 0 && y == 0) {
        enabled = true
        scheduleLayout(resetPosition)
    }

    fun reapply() {
        if (enabled) scheduleLayout(resetPosition = false)
    }

    fun onConfigurationChanged() {
        if (enabled) scheduleLayout(resetPosition = false)
    }

    fun drag(deltaX: Float, deltaY: Float) {
        if (!enabled) return
        x += deltaX.toInt()
        y += deltaY.toInt()
        applyLayout()
    }

    fun restore() {
        mainHandler.post {
            val imeWindow = windowProvider() ?: return@post
            val attrs = imeWindow.attributes
            attrs.gravity = baseGravity ?: Gravity.BOTTOM
            attrs.width = baseWidth ?: WindowManager.LayoutParams.MATCH_PARENT
            attrs.height = baseHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
            baseSoftInputMode?.let { attrs.softInputMode = it }
            attrs.x = 0
            attrs.y = 0
            imeWindow.attributes = attrs
            enabled = false
            debugLog("floating-window-restored")
        }
    }

    private fun scheduleLayout(resetPosition: Boolean) {
        mainHandler.post {
            val imeWindow = windowProvider() ?: return@post
            val attrs = imeWindow.attributes
            if (baseGravity == null) {
                baseGravity = attrs.gravity
                baseWidth = attrs.width
                baseHeight = attrs.height
                baseSoftInputMode = attrs.softInputMode
            }

            val (screenWidth, screenHeight) = displaySize()
            val availableWidth = (screenWidth - dp(24)).coerceAtLeast(dp(1))
            val availableHeight = (screenHeight - dp(24)).coerceAtLeast(dp(1))
            val desiredWidth = floatingWidth(screenWidth).coerceAtMost(availableWidth)
            val currentHeight = (keyboardHeightPx() ?: dp(302))
                .coerceAtLeast(dp(1))
                .coerceAtMost(availableHeight)
            if (resetPosition) {
                x = ((screenWidth - desiredWidth) / 2).coerceAtLeast(0)
                y = ((screenHeight - currentHeight) / 2).coerceAtLeast(dp(16))
            }

            val bounds = bounds(screenWidth, screenHeight, desiredWidth, currentHeight)
            attrs.gravity = Gravity.TOP or Gravity.START
            attrs.width = desiredWidth
            attrs.height = currentHeight
            attrs.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            attrs.x = x.coerceIn(bounds[0], bounds[1])
            attrs.y = y.coerceIn(bounds[2], bounds[3])
            x = attrs.x
            y = attrs.y
            imeWindow.attributes = attrs
            debugLog("floating-window x=${attrs.x} y=${attrs.y} w=${attrs.width} h=${attrs.height}")
        }
    }

    private fun applyLayout() {
        if (!enabled) return
        val imeWindow = windowProvider() ?: return
        val (screenWidth, screenHeight) = displaySize()
        val attrs = imeWindow.attributes
        val availableWidth = (screenWidth - dp(24)).coerceAtLeast(dp(1))
        val availableHeight = (screenHeight - dp(24)).coerceAtLeast(dp(1))
        val width = (if (attrs.width > 0) attrs.width else floatingWidth(screenWidth))
            .coerceAtMost(availableWidth)
        val height = (
            keyboardHeightPx()?.takeIf { it > 0 }
                ?: imeWindow.decorView.height.takeIf { it > 0 }
                ?: dp(302)
            )
            .coerceAtMost(availableHeight)
        val bounds = bounds(screenWidth, screenHeight, width, height)
        x = x.coerceIn(bounds[0], bounds[1])
        y = y.coerceIn(bounds[2], bounds[3])
        attrs.gravity = Gravity.TOP or Gravity.START
        attrs.width = width
        attrs.height = height
        attrs.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        attrs.x = x
        attrs.y = y
        imeWindow.attributes = attrs
        debugLog("floating-window-drag x=$x y=$y w=$width h=$height")
    }

    /** Return [minX, maxX, minY, maxY] for a visible floating IME card. */
    private fun bounds(
        screenWidth: Int,
        screenHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): IntArray {
        val margin = dp(12)
        val minX = margin
        val maxX = (screenWidth - windowWidth - margin).coerceAtLeast(minX)
        val minY = margin
        val maxY = (screenHeight - windowHeight - margin).coerceAtLeast(minY)
        return intArrayOf(minX, maxX, minY, maxY)
    }

    private fun floatingWidth(screenWidth: Int): Int {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val preferred = if (landscape) {
            dp(ImeGeometryTokens.FLOATING_LANDSCAPE_WIDTH_DP)
        } else {
            (screenWidth * 0.88f).toInt()
        }
        val maximum = dp(
            if (landscape) ImeGeometryTokens.FLOATING_LANDSCAPE_WIDTH_DP else 400,
        )
        return minOf(
            preferred,
            maximum,
            (screenWidth - dp(16)).coerceAtLeast(dp(1)),
        ).coerceAtLeast(dp(320))
    }

    private fun displaySize(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
