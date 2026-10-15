package llc.slacker.openime.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Lays its children out left to right and wraps to the next line, which is the
 * shape a word list needs when every word is its own width.
 *
 * It also answers which child a point falls in ([childIndexAt]): the reference
 * selects words by sliding across them (`word_segmentation_title`
 * 「滑动选词」), so the list has to read both the first and the last word a
 * gesture touches, not just the one that was tapped.
 */
internal class WordFlowLayout(context: Context) : ViewGroup(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight)
            .coerceAtLeast(1)
        var lineWidth = 0
        var lineHeight = 0
        var contentWidth = 0
        var contentHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            if (lineWidth > 0 && lineWidth + childWidth > available) {
                contentWidth = maxOf(contentWidth, lineWidth)
                contentHeight += lineHeight
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += childWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight + params.topMargin + params.bottomMargin)
        }
        contentWidth = maxOf(contentWidth, lineWidth)
        contentHeight += lineHeight
        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = (right - left - paddingLeft - paddingRight).coerceAtLeast(1)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val params = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            if (x > paddingLeft && x + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += lineHeight
                lineHeight = 0
            }
            child.layout(
                x + params.leftMargin,
                y + params.topMargin,
                x + params.leftMargin + child.measuredWidth,
                y + params.topMargin + child.measuredHeight,
            )
            x += childWidth
            lineHeight = maxOf(lineHeight, child.measuredHeight + params.topMargin + params.bottomMargin)
        }
    }

    /** The child under a point of this view's own coordinates, or -1. */
    fun childIndexAt(x: Float, y: Float): Int {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != VISIBLE) continue
            if (x >= child.left && x < child.right && y >= child.top && y < child.bottom) return index
        }
        return -1
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun checkLayoutParams(params: LayoutParams): Boolean = params is MarginLayoutParams

    /** The keyboard build knows no child type of its own. */
    override fun generateLayoutParams(params: LayoutParams): LayoutParams = MarginLayoutParams(params)
}
