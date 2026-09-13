package llc.slacker.openime

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Upgrades the legacy 9-key punctuation stack into a real vertical symbol rail.
 * When one digit code has several valid Pinyin paths, the first cell becomes a
 * real path filter; the remaining cells stay the normal scrollable symbols.
 */
internal object NineKeySymbolRailDecorator {
    private const val LEGACY_TAG = "nine-punct-stack"
    private const val CONTENT_TAG = "nine-symbol-scroll-content"
    private const val FILTER_TAG = "nine-pinyin-path-filter"
    private const val CELL_HEIGHT_DP = 48

    fun decorate(root: View, onCommit: (String) -> Unit) {
        val tagged = root.findViewWithTag<View>(LEGACY_TAG) ?: return
        val scroll = when (tagged) {
            is ScrollView -> tagged
            is LinearLayout -> wrapLegacyStack(tagged)
            else -> return
        }
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val symbols = commonSymbols()
        val filter = content.findViewWithTag<TextView>(FILTER_TAG)
        val offset = if (filter != null) 1 else 0
        val alreadyDecorated = tagged is ScrollView &&
            content.childCount == symbols.size + offset &&
            symbols.indices.all { index ->
                (content.getChildAt(index + offset) as? TextView)?.text?.toString() == symbols[index]
            }
        if (alreadyDecorated) return

        val inheritedTextColor = (0 until content.childCount)
            .asSequence()
            .mapNotNull { content.getChildAt(it) as? TextView }
            .firstOrNull { it.tag != FILTER_TAG }
            ?.currentTextColor

        content.removeAllViews()
        content.contentDescription = null
        if (filter != null) {
            inheritedTextColor?.let(filter::setTextColor)
            content.addView(filter, cellParams(content.context, withGap = true))
        }
        symbols.forEachIndexed { index, symbol ->
            content.addView(
                symbolCell(content.context, symbol, inheritedTextColor, onCommit),
                cellParams(content.context, withGap = index < symbols.lastIndex),
            )
        }
    }

    /**
     * Show a selectable Pinyin path only when the current T9 code is genuinely
     * ambiguous. Selecting it is a real input constraint supplied by the caller,
     * not a cosmetic label.
     */
    fun setPinyinFilters(
        root: View,
        filters: List<String>,
        selected: String?,
        onSelect: (String) -> Unit,
    ) {
        val scroll = root.findViewWithTag<ScrollView>(LEGACY_TAG) ?: return
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val choices = filters
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(8)
            .toList()
        val existing = content.findViewWithTag<TextView>(FILTER_TAG)
        if (choices.size <= 1) {
            if (existing != null) content.removeView(existing)
            return
        }

        val active = selected?.takeIf { it in choices } ?: choices.first()
        val view = existing ?: TextView(content.context).apply {
            tag = FILTER_TAG
            textSize = 12f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            minimumHeight = dp(content.context, CELL_HEIGHT_DP)
            val inherited = (0 until content.childCount)
                .asSequence()
                .mapNotNull { content.getChildAt(it) as? TextView }
                .firstOrNull { it.tag != FILTER_TAG }
                ?.currentTextColor
            inherited?.let(::setTextColor)
            applySelectableBackground(this)
            content.addView(this, 0, cellParams(content.context, withGap = true))
        }
        view.text = active.replace(" ", "·") + " ›"
        view.contentDescription = "九键拼音筛选，当前${active.replace(" ", "、")}，点击切换"
        view.setOnClickListener {
            val current = choices.indexOf(active).coerceAtLeast(0)
            onSelect(choices[(current + 1) % choices.size])
        }
    }

    private fun commonSymbols(): List<String> = ImeData.symbols["常用"]
        .orEmpty()
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .toList()
        .ifEmpty { listOf("，", "。", "？", "！") }

    private fun wrapLegacyStack(stack: LinearLayout): ScrollView {
        val parent = stack.parent as? ViewGroup ?: return ScrollView(stack.context)
        val index = parent.indexOfChild(stack)
        val slotParams = stack.layoutParams
        val legacyBackground = stack.background

        stack.tag = CONTENT_TAG
        stack.background = null
        stack.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        val scroll = ScrollView(stack.context).apply {
            tag = LEGACY_TAG
            background = legacyBackground
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            contentDescription = "九键常用符号，上下滑动查看更多"
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
        onCommit: (String) -> Unit,
    ): TextView = TextView(context).apply {
        text = symbol
        textSize = 17f
        gravity = Gravity.CENTER
        contentDescription = symbol
        isClickable = true
        isFocusable = true
        minimumHeight = dp(context, CELL_HEIGHT_DP)
        inheritedTextColor?.let(::setTextColor)
        applySelectableBackground(this)
        setOnClickListener { onCommit(symbol) }
    }

    private fun applySelectableBackground(view: TextView) {
        val selectable = TypedValue()
        if (
            view.context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                selectable,
                true,
            ) && selectable.resourceId != 0
        ) {
            view.setBackgroundResource(selectable.resourceId)
        }
    }

    private fun cellParams(context: Context, withGap: Boolean) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(context, CELL_HEIGHT_DP),
    ).apply {
        if (withGap) bottomMargin = dp(context, 1)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
