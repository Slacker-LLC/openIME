package llc.slacker.openime

import android.graphics.RectF
import android.view.View

/**
 * Keyboard-local normalized coordinate system.
 *
 * Origin is the top-left of the live IME content view. All values are
 * 0.0..1.0 relative to the root's current width/height; pixel conversion
 * happens only at runtime from the real measured View bounds.
 */
data class NormalizedBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun toPx(rootWidth: Float, rootHeight: Float): RectF = RectF(
        left * rootWidth,
        top * rootHeight,
        right * rootWidth,
        bottom * rootHeight,
    )

    companion object {
        fun fromView(view: View, root: View): NormalizedBounds {
            val rootLocation = IntArray(2)
            val viewLocation = IntArray(2)
            root.getLocationOnScreen(rootLocation)
            view.getLocationOnScreen(viewLocation)
            val rootWidth = root.width.toFloat().coerceAtLeast(1f)
            val rootHeight = root.height.toFloat().coerceAtLeast(1f)
            return NormalizedBounds(
                left = (viewLocation[0] - rootLocation[0]) / rootWidth,
                top = (viewLocation[1] - rootLocation[1]) / rootHeight,
                right = (viewLocation[0] - rootLocation[0] + view.width) / rootWidth,
                bottom = (viewLocation[1] - rootLocation[1] + view.height) / rootHeight,
            )
        }
    }
}
