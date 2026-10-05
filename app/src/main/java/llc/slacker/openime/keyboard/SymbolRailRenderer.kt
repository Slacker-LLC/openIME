package llc.slacker.openime.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import llc.slacker.openime.R
import llc.slacker.openime.theme.ImeGeometryTokens

/**
 * First-frame builder for the vertical symbol rails used beside compact
 * keyboards. It owns only View construction/reuse; product semantics stay in
 * the nine-key/numeric callers.
 */
internal object SymbolRailRenderer {
    private const val CELL_HEIGHT_DP = 54
    private const val FADING_EDGE_DP = 18

    fun build(
        context: Context,
        railTag: String,
        contentTag: String,
        contentDescription: String,
        symbols: List<String>,
        cellHeightDp: Int = CELL_HEIGHT_DP,
        toPx: (Int) -> Int = { dp(context, it) },
        tagPrefix: String,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
        onAdd: (() -> Unit)? = null,
    ): ScrollView {
        val content = LinearLayout(context).apply {
            tag = contentTag
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            tag = railTag
            isVerticalScrollBarEnabled = false
            // The list fades out at its edges: more lies beyond.
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(toPx(FADING_EDGE_DP))
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            this.contentDescription = contentDescription
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        populate(
            scroll = scroll,
            symbols = symbols,
            cellHeightDp = cellHeightDp,
            toPx = toPx,
            tagPrefix = tagPrefix,
            onCommit = onCommit,
            onFeedback = onFeedback,
            onAdd = onAdd,
        )
        return scroll
    }

    fun populate(
        scroll: ScrollView,
        symbols: List<String>,
        cellHeightDp: Int = CELL_HEIGHT_DP,
        tagPrefix: String,
        preservedHeader: TextView? = null,
        toPx: (Int) -> Int = { dp(scroll.context, it) },
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
        /** When set, a ＋ cell after the symbols opens the rail editor. */
        onAdd: (() -> Unit)? = null,
    ) {
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val inheritedTextColor = (0 until content.childCount)
            .asSequence()
            .mapNotNull { content.getChildAt(it) as? TextView }
            .firstOrNull { it !== preservedHeader }
            ?.currentTextColor

        content.removeAllViews()
        if (preservedHeader != null) {
            inheritedTextColor?.let(preservedHeader::setTextColor)
            content.addView(
                preservedHeader,
                cellParams(content.context, withGap = true, heightDp = cellHeightDp, toPx = toPx),
            )
        }
        // One flat list on one panel (the theme draws the panel), hairlines
        // between the items and faded edges: it reads as something to scroll,
        // not a column of separate keys. Three items show, level with the keys.
        fun divider() {
            content.addView(
                View(content.context).apply {
                    tag = DIVIDER_TAG
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = toPx(DIVIDER_INSET_DP)
                    marginEnd = toPx(DIVIDER_INSET_DP)
                },
            )
        }
        symbols.forEachIndexed { index, symbol ->
            if (index > 0) divider()
            content.addView(
                symbolCell(
                    context = content.context,
                    symbol = symbol,
                    inheritedTextColor = inheritedTextColor,
                    tagPrefix = tagPrefix,
                    onCommit = onCommit,
                    onFeedback = onFeedback,
                ),
                flatCellParams(heightDp = cellHeightDp, toPx = toPx),
            )
        }
        if (onAdd != null) {
            if (symbols.isNotEmpty()) divider()
            content.addView(
                ImageView(content.context).apply {
                    setImageResource(R.drawable.ic_rail_add)
                    scaleType = ImageView.ScaleType.CENTER
                    tag = "${tagPrefix}add"
                    contentDescription = "添加和排序常用符号"
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        onFeedback()
                        onAdd()
                    }
                },
                flatCellParams(heightDp = cellHeightDp, toPx = toPx),
            )
        }
    }

    const val DIVIDER_TAG = "rail-divider"
    private const val DIVIDER_INSET_DP = 10

    private fun flatCellParams(heightDp: Int, toPx: (Int) -> Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(heightDp))

    fun cellParams(context: Context, withGap: Boolean, heightDp: Int = CELL_HEIGHT_DP, toPx: (Int) -> Int = { dp(context, it) }) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        toPx(heightDp),
    ).apply {
        val gap = toPx(ImeGeometryTokens.KEY_GAP_DP) / 2
        setMargins(gap, gap, gap, gap)
        height -= gap * 2
    }

    fun cellHeightPx(context: Context): Int = dp(context, CELL_HEIGHT_DP)

    private fun symbolCell(
        context: Context,
        symbol: String,
        inheritedTextColor: Int?,
        tagPrefix: String,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
    ): TextView = InkCenteredTextView(context).apply {
        text = symbol
        textSize = when (symbol) { "！", "!", "？", "?" -> 24f; "，", "、", "%", "+", "−", "-" -> 22f; else -> 20f }
        gravity = Gravity.CENTER
        tag = "$tagPrefix$symbol"
        contentDescription = symbol
        isClickable = true
        isFocusable = true
        maxLines = 2
        includeFontPadding = false
        inheritedTextColor?.let(::setTextColor)
        setOnClickListener {
            onFeedback()
            onCommit(symbol)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * Full-width punctuation is drawn in the corner of its em box (the comma
     * and full stop at bottom-left, the exclamation and question marks at the
     * left), so plain gravity centering looks off-center. Shift the canvas so
     * the glyph's real ink bounds, not its em box, sit at the cell center.
     */
    private class InkCenteredTextView(context: Context) : TextView(context) {
        private val ink = Rect()

        override fun onDraw(canvas: Canvas) {
            val value = text?.toString().orEmpty()
            if (value.isEmpty() || value.codePointCount(0, value.length) != 1) {
                super.onDraw(canvas)
                return
            }
            val paint = paint
            paint.getTextBounds(value, 0, value.length, ink)
            if (ink.isEmpty) {
                super.onDraw(canvas)
                return
            }
            val metrics = paint.fontMetrics
            val dx = paint.measureText(value) / 2f - ink.exactCenterX()
            val dy = (metrics.ascent + metrics.descent) / 2f - ink.exactCenterY()
            canvas.save()
            canvas.translate(dx, dy)
            super.onDraw(canvas)
            canvas.restore()
        }
    }
}
