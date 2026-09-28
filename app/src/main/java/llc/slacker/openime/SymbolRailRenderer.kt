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
    private const val CELL_HEIGHT_DP = 48

    fun build(
        context: Context,
        railTag: String,
        contentTag: String,
        contentDescription: String,
        symbols: List<String>,
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
            tagPrefix = tagPrefix,
            onCommit = onCommit,
            onFeedback = onFeedback,
        )
        return scroll
    }

    fun populate(
        scroll: ScrollView,
        symbols: List<String>,
        tagPrefix: String,
        preservedHeader: TextView? = null,
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
                cellParams(content.context, withGap = true),
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
                cellParams(content.context, withGap = index < symbols.lastIndex),
            )
        }
    }

    fun cellParams(context: Context, withGap: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, CELL_HEIGHT_DP),
    ).apply {
        if (withGap) bottomMargin = dp(context, 1)
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
        textSize = if (symbol.length > 2) 12f else 17f
        gravity = Gravity.CENTER
        tag = "$tagPrefix$symbol"
        contentDescription = symbol
        isClickable = true
        isFocusable = true
        maxLines = 2
        minimumHeight = cellHeightPx(context)
        inheritedTextColor?.let(::setTextColor)
        setOnClickListener {
            onFeedback()
            onCommit(symbol)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
