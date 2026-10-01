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

/**
 * Pure dp geometry derived from orientation and font scale.
 *
 * Keep calculations here; applying these values to Android View layout params
 * remains the renderer's responsibility.
 */
internal data class KeyboardLayoutMetrics(
    val landscape: Boolean,
    val fontScale: Float,
    val heightPercent: Int = 100,
    val availableWidthDp: Int = 390,
) {
    val keyRowHeightDp: Int = run {
        val base = if (landscape) {
            ImeGeometryTokens.LANDSCAPE_KEY_ROW_HEIGHT_DP
        } else {
            54
        }
        val fontGrow = ((fontScale - 1f).coerceAtLeast(0f) * 12f)
            .toInt()
            .coerceAtMost(12)
        val scaled = ((base + fontGrow) * heightPercent.coerceIn(80, 120) / 100f).toInt()
        scaled.coerceAtLeast(if (landscape) 38 else 44)
    }

    val nineGridHeightDp: Int =
        keyRowHeightDp * 3

    val nineBodyHeightDp: Int =
        nineGridHeightDp + keyRowHeightDp

    val doubleKeyHeightDp: Int =
        keyRowHeightDp * 2 + ImeGeometryTokens.KEY_ROW_GAP_DP

    val topZoneHeightDp: Int = ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP

    val imeHeightDp: Int = run {
        val derived = topZoneHeightDp +
            keyRowHeightDp * 4 + 16
        val baseMinimum = if (landscape) 256 else 0
        val scaledMinimum =
            (baseMinimum * heightPercent.coerceIn(80, 120) / 100f).toInt()
        maxOf(scaledMinimum, derived)
    }

    val keyboardBodyHeightDp: Int = imeHeightDp - topZoneHeightDp

    val panelBodyHeightDp: Int =
        (imeHeightDp - ImeGeometryTokens.TOUCH_TARGET_DP).coerceAtLeast(0)
}


/** All reference dimensions scale together from the PDF's 390-unit canvas. */
internal object ImeReferenceSizing {
    fun scale(context: android.content.Context, widthPx: Int = 0): Float {
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val widthDp = if (widthPx > 0) widthPx / metrics.density else config.screenWidthDp.toFloat()
        val widthScale = widthDp.coerceAtMost(600f) / 390f
        return if (config.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            minOf(widthScale, config.screenHeightDp * 0.55f / 256f)
        } else widthScale
    }
}
