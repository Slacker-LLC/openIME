package llc.slacker.openime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * First-frame builder for the vertical symbol rails used beside compact
 * keyboards. It owns only View construction/reuse; product semantics stay in
 * the nine-key/numeric callers.
 */
internal object SymbolRailRenderer {
    private const val CELL_HEIGHT_DP = 54

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
    ): ScrollView {
        val content = LinearLayout(context).apply {
            tag = contentTag
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(context).apply {
            tag = railTag
            isVerticalScrollBarEnabled = false
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
        symbols.forEachIndexed { index, symbol ->
            content.addView(
                symbolCell(
                    context = content.context,
                    symbol = symbol,
                    inheritedTextColor = inheritedTextColor,
                    tagPrefix = tagPrefix,
                    onCommit = onCommit,
                    onFeedback = onFeedback,
                ),
                cellParams(content.context, withGap = index < symbols.lastIndex, heightDp = cellHeightDp, toPx = toPx),
            )
        }
    }

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
    ): TextView = TextView(context).apply {
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
}
