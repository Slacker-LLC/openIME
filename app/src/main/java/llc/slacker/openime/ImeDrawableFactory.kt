package llc.slacker.openime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable

/**
 * Shared drawable/color primitives for the live IME and setup surfaces.
 * Radius and stroke values are pixels; callers keep density conversion.
 */
internal object ImeDrawableFactory {
    fun rounded(
        color: Int,
        radiusPx: Int,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 1,
    ): GradientDrawable = rounded(
        color = color,
        radiusPx = radiusPx.toFloat(),
        strokeColor = strokeColor,
        strokeWidthPx = strokeWidthPx,
    )

    fun rounded(
        color: Int,
        radiusPx: Float,
        strokeColor: Int? = null,
        strokeWidthPx: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx
        strokeColor?.let { setStroke(strokeWidthPx, it) }
    }

    fun statefulRounded(
        normal: Int,
        pressed: Int,
        radiusPx: Int,
        focusStrokeColor: Int,
        focusStrokeWidthPx: Int,
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radiusPx))
        addState(
            intArrayOf(android.R.attr.state_focused),
            rounded(normal, radiusPx, focusStrokeColor, focusStrokeWidthPx),
        )
        addState(intArrayOf(), rounded(normal, radiusPx))
    }

    fun focusedRounded(
        fillColor: Int,
        radiusPx: Float,
        focusedStrokeColor: Int,
        defaultStrokeColor: Int?,
        strokeWidthPx: Int,
    ): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_focused),
            rounded(fillColor, radiusPx, focusedStrokeColor, strokeWidthPx),
        )
        addState(
            intArrayOf(),
            rounded(fillColor, radiusPx, defaultStrokeColor, strokeWidthPx),
        )
    }

    fun dim(
        color: Int,
        factor: Float = 0.82f,
        preserveAlpha: Boolean = true,
    ): Int {
        val red = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val green = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val blue = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return if (preserveAlpha) {
            Color.argb(Color.alpha(color), red, green, blue)
        } else {
            Color.rgb(red, green, blue)
        }
    }

    /**
     * Mix two opaque UI colors without bringing in another color library.
     * amount=0 returns background; amount=1 returns foreground.
     */
    fun blend(foreground: Int, background: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun channel(front: Int, back: Int): Int =
            (back + (front - back) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            channel(Color.alpha(foreground), Color.alpha(background)),
            channel(Color.red(foreground), Color.red(background)),
            channel(Color.green(foreground), Color.green(background)),
            channel(Color.blue(foreground), Color.blue(background)),
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

    fun contrastText(background: Int): Int =
        ImeContrastPolicy.contrastText(background)
}
