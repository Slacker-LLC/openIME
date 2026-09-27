package llc.slacker.openime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Mechanical builder for the vertical symbol rails used beside compact
 * keyboards. It owns only View construction/reuse; product semantics stay in
 * the nine-key/numeric callers.
 */
internal object SymbolRailRenderer {
    private const val CELL_HEIGHT_DP = 48

    fun decorate(
        root: View,
        sourceTag: String,
        railTag: String,
        contentTag: String,
        contentDescription: String,
        symbols: List<String>,
        tagPrefix: String,
        preservedHeader: TextView? = null,
        onCommit: (String) -> Unit,
        onFeedback: () -> Unit,
    ): ScrollView? {
        val tagged = root.findViewWithTag<View>(railTag)
            ?: root.findViewWithTag<View>(sourceTag)
            ?: return null
        val scroll = when (tagged) {
            is ScrollView -> tagged
            is LinearLayout -> wrapStack(tagged, railTag, contentTag, contentDescription)
            else -> return null
        }
        val content = scroll.getChildAt(0) as? LinearLayout ?: return null
        val headerOffset = if (preservedHeader != null) 1 else 0
        val alreadyDecorated = tagged is ScrollView &&
            content.childCount == symbols.size + headerOffset &&
            symbols.indices.all { index ->
                (content.getChildAt(index + headerOffset) as? TextView)?.text?.toString() == symbols[index]
            }
        if (alreadyDecorated) return scroll

        val inheritedTextColor = (0 until content.childCount)
            .asSequence()
            .mapNotNull { content.getChildAt(it) as? TextView }
            .firstOrNull { it !== preservedHeader }
            ?.currentTextColor

        content.removeAllViews()
        content.contentDescription = null
        if (preservedHeader != null) {
            inheritedTextColor?.let(preservedHeader::setTextColor)
            content.addView(preservedHeader, cellParams(content.context, withGap = true))
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
        return scroll
    }

    fun cellParams(context: Context, withGap: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, CELL_HEIGHT_DP),
    ).apply {
        if (withGap) bottomMargin = dp(context, 1)
    }

    fun cellHeightPx(context: Context): Int = dp(context, CELL_HEIGHT_DP)

    private fun wrapStack(
        stack: LinearLayout,
        railTag: String,
        contentTag: String,
        description: String,
    ): ScrollView {
        val parent = stack.parent as? ViewGroup ?: return ScrollView(stack.context)
        val index = parent.indexOfChild(stack)
        val slotParams = stack.layoutParams
        val legacyBackground = stack.background

        stack.tag = contentTag
        stack.background = null
        stack.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val scroll = ScrollView(stack.context).apply {
            tag = railTag
            background = legacyBackground
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            contentDescription = description
        }

        parent.removeViewAt(index)
        scroll.addView(
            stack,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        parent.addView(scroll, index, slotParams)
        return scroll
    }

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
