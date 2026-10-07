package llc.slacker.openime.floating

import android.os.Handler
import android.view.Gravity
import android.view.Window
import android.view.WindowManager

/**
 * Docks the IME window to the bottom-right corner at part of the screen width
 * (the landscape "left and right columns" layout: the app on the left, the
 * keyboard on the right). Unlike floating, the window keeps its bottom edge, so
 * the app still resizes around it.
 */
internal class SideDockWindowController(
    private val resources: android.content.res.Resources,
    private val mainHandler: Handler,
    private val windowProvider: () -> Window?,
    private val keyboardHeightPx: () -> Int?,
) {
    var enabled: Boolean = false
        private set

    private var baseGravity: Int? = null
    private var baseWidth: Int? = null
    private var baseHeight: Int? = null

    fun enable() {
        enabled = true
        mainHandler.post {
            if (!enabled) return@post
            val window = windowProvider() ?: return@post
            val attrs = window.attributes
            if (baseGravity == null) {
                baseGravity = attrs.gravity
                baseWidth = attrs.width
                baseHeight = attrs.height
            }
            val metrics = resources.displayMetrics
            val width = sideWidthPx()
            val height = (keyboardHeightPx() ?: return@post).coerceIn(1, metrics.heightPixels)
            // Same window technique as the floating card (an explicit size at an
            // explicit offset), pinned to the bottom-right corner.
            attrs.gravity = Gravity.TOP or Gravity.START
            attrs.width = width
            attrs.height = height
            attrs.x = metrics.widthPixels - width
            attrs.y = metrics.heightPixels - height
            window.attributes = attrs
        }
    }

    fun restore() {
        enabled = false
        mainHandler.post {
            if (enabled) return@post
            val window = windowProvider() ?: return@post
            val attrs = window.attributes
            attrs.gravity = baseGravity ?: Gravity.BOTTOM
            attrs.width = baseWidth ?: WindowManager.LayoutParams.MATCH_PARENT
            attrs.height = baseHeight ?: WindowManager.LayoutParams.WRAP_CONTENT
            attrs.x = 0
            attrs.y = 0
            window.attributes = attrs
        }
    }

    /** About 60% of the width, never narrower than a phone keyboard or wider than a tablet one. */
    internal fun sideWidthPx(): Int {
        val metrics = resources.displayMetrics
        val screen = metrics.widthPixels
        val density = metrics.density
        val preferred = (screen * SIDE_FRACTION).toInt()
        return preferred.coerceIn((MIN_DP * density).toInt().coerceAtMost(screen), (MAX_DP * density).toInt().coerceAtLeast(1))
            .coerceAtMost(screen)
    }

    private companion object {
        const val SIDE_FRACTION = 0.6f
        const val MIN_DP = 360
        const val MAX_DP = 560
    }
}
