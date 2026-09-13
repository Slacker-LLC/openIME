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
 *
 * The legacy renderer owns layout construction, so production replaces only the
 * tagged punctuation slot after each hierarchy rebuild. Keeping this outside the
 * renderer avoids coupling 9-key candidate state to a purely visual interaction.
 */
internal object NineKeySymbolRailDecorator {
    private const val LEGACY_TAG = "nine-punct-stack"
    private const val CONTENT_TAG = "nine-symbol-scroll-content"
    private const val CELL_HEIGHT_DP = 48

    fun decorate(root: View, onCommit: (String) -> Unit) {
        val tagged = root.findViewWithTag<View>(LEGACY_TAG) ?: return
        val scroll = when (tagged) {
            is ScrollView -> tagged
            is LinearLayout -> wrapLegacyStack(tagged)
            else -> return
        }
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val symbols = ImeData.symbols["常用"]
            .orEmpty()
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
            .ifEmpty { listOf("，", "。", "？", "！") }

        val signature = symbols.joinToString(separator = "\u0001")
        if (content.contentDescription?.toString() == signature && content.childCount == symbols.size) return

        val inheritedTextColor = (0 until content.childCount)
            .asSequence()
            .mapNotNull { content.getChildAt(it) as? TextView }
            .firstOrNull()
            ?.currentTextColor

        content.removeAllViews()
        content.contentDescription = signature
        symbols.forEachIndexed { index, symbol ->
            content.addView(
                symbolCell(content.context, symbol, inheritedTextColor, onCommit),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(content.context, CELL_HEIGHT_DP),
                ).apply {
                    if (index < symbols.lastIndex) bottomMargin = dp(content.context, 1)
                },
            )
        }
    }

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

        val selectable = TypedValue()
        if (
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                selectable,
                true,
            ) && selectable.resourceId != 0
        ) {
            setBackgroundResource(selectable.resourceId)
        }
        setOnClickListener { onCommit(symbol) }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
