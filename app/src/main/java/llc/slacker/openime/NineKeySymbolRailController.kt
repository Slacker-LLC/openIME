package llc.slacker.openime

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Owns the Chinese 9-key side rail.
 *
 * With no composition it behaves as the normal vertically scrollable symbol
 * rail. During composition, the same rail
 * becomes a directly selectable Pinyin list. This mirrors mature 9-key IMEs:
 * the user sees all useful spellings at once instead of cycling one hidden
 * option through a single button.
 */
internal class NineKeySymbolRailController(
    private val context: Context,
    private val composition: EditText,
    private val onCommit: (String) -> Unit,
    private val onFeedback: () -> Unit,
    private val cellHeightDp: () -> Int,
    private val toPx: (Int) -> Int = { (it * context.resources.displayMetrics.density).toInt() },
    private val onRailChanged: (View) -> Unit,
) {
    private enum class RailMode { SYMBOLS, PINYIN }

    private var rail: ScrollView? = null
    private var railMode = RailMode.SYMBOLS
    private var renderedChoices: List<String> = emptyList()
    private var renderedSelected: String? = null

    init {
        composition.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                composition.post(::refreshPinyinFilters)
            }
        })
    }

    fun buildRail(): ScrollView =
        SymbolRailRenderer.build(
            context = context,
            railTag = NINE_RAIL_TAG,
            contentTag = NINE_CONTENT_TAG,
            contentDescription = "九键拼音筛选或常用符号，上下滑动查看更多",
            symbols = commonSymbols(),
            cellHeightDp = cellHeightDp(),
            toPx = toPx,
            tagPrefix = "punct:",
            onCommit = onCommit,
            onFeedback = onFeedback,
        ).also {
            rail = it
            railMode = RailMode.SYMBOLS
            renderedChoices = emptyList()
            renderedSelected = null
            refreshPinyinFilters()
        }

    /**
     * A symbol edit may happen while the Pinyin filter is visible. Re-resolve
     * the current composition first; symbols are repopulated only when the
     * rail is actually in symbol mode.
     */
    fun refreshSymbols() {
        if (railMode == RailMode.SYMBOLS) {
            renderSymbols(force = true)
        } else {
            refreshPinyinFilters()
        }
    }

    private fun refreshPinyinFilters() {
        val text = composition.text?.toString().orEmpty()
        if (text.isBlank()) {
            renderSymbols()
            return
        }

        val lastSpace = text.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) text.substring(0, lastSpace + 1) else ""
        val suffix = if (lastSpace >= 0) text.substring(lastSpace + 1) else text
        val digits = CandidatePipeline.nineKeyDigitsFor(suffix)
        val code = digits?.let { NineKeyLocalDecoder.nativeCode(prefix, it) }
        if (code.isNullOrEmpty()) {
            renderSymbols()
            return
        }

        val resolver = context as? CandidateResolver
        if (resolver == null) {
            renderSymbols()
            return
        }

        val choices = resolver.nineKeyPathsFor(code)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_VISIBLE_PATHS)
            .toList()

        // Keep the selected spelling visible throughout composition, even when
        // this digit sequence currently has only one complete display path.
        if (choices.isEmpty()) {
            renderSymbols()
            return
        }

        val selected = resolver.selectedNineKeyPathFor(code)
            ?.takeIf { it in choices }
            ?: text.takeIf { it in choices }
            ?: choices.first()

        renderPinyinChoices(
            choices = choices,
            selected = selected,
        ) { chosen ->
            resolver.selectNineKeyPath(code, chosen)
            if (chosen == composition.text?.toString()) return@renderPinyinChoices
            composition.setText(chosen)
            composition.setSelection(chosen.length)
        }
    }

    private fun renderSymbols(force: Boolean = false) {
        val scroll = rail ?: return
        if (railMode == RailMode.SYMBOLS && !force) return

        SymbolRailRenderer.populate(
            scroll = scroll,
            symbols = commonSymbols(),
            cellHeightDp = cellHeightDp(),
            toPx = toPx,
            tagPrefix = "punct:",
            onCommit = onCommit,
            onFeedback = onFeedback,
        )
        railMode = RailMode.SYMBOLS
        renderedChoices = emptyList()
        renderedSelected = null
        if (scroll.scrollY != 0) scroll.post { scroll.scrollTo(0, 0) }
        scroll.contentDescription = "九键常用符号，上下滑动查看更多"
        onRailChanged(scroll)
    }

    private fun renderPinyinChoices(
        choices: List<String>,
        selected: String,
        onSelect: (String) -> Unit,
    ) {
        val scroll = rail ?: return
        if (
            railMode == RailMode.PINYIN &&
            renderedChoices == choices &&
            renderedSelected == selected
        ) {
            return
        }
        val content = scroll.getChildAt(0) as? LinearLayout ?: return
        val inheritedTextColor = (0 until content.childCount)
            .asSequence()
            .mapNotNull { content.getChildAt(it) as? TextView }
            .firstOrNull()
            ?.currentTextColor

        content.removeAllViews()
        choices.forEachIndexed { index, choice ->
            val active = choice == selected
            content.addView(
                TextView(content.context).apply {
                    text = displayPath(choice)
                    textSize = 18f
                    gravity = Gravity.CENTER
                    tag = if (active) SELECTED_FILTER_TAG else FILTER_TAG
                    contentDescription =
                        if (active) {
                            "九键拼音${speakablePath(choice)}，已选择"
                        } else {
                            "九键拼音${speakablePath(choice)}，双击选择"
                        }
                    if (Build.VERSION.SDK_INT >= 30) {
                        stateDescription = if (active) "已选择" else "未选择"
                    }
                    isSelected = active
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    isClickable = true
                    isFocusable = true
                    maxLines = 2
                    includeFontPadding = false
                    inheritedTextColor?.let(::setTextColor)
                    setOnClickListener {
                        onFeedback()
                        onSelect(choice)
                    }
                },
                SymbolRailRenderer.cellParams(
                    content.context,
                    withGap = index < choices.lastIndex,
                    heightDp = cellHeightDp(),
                    toPx = toPx,
                ),
            )
        }

        railMode = RailMode.PINYIN
        renderedChoices = choices.toList()
        renderedSelected = selected
        scroll.contentDescription = "九键拼音筛选，上下滑动查看更多"
        onRailChanged(scroll)
        val selectedIndex = choices.indexOf(selected).coerceAtLeast(0)
        scroll.post {
            val target = content.getChildAt(selectedIndex) ?: return@post
            val viewport = scroll.height.coerceAtLeast(SymbolRailRenderer.cellHeightPx(content.context))
            val targetY = (target.top - (viewport - target.height) / 2).coerceAtLeast(0)
            scroll.scrollTo(0, targetY)
        }
    }

    private fun displayPath(path: String): String =
        path.replace(" ", "'").replace("|", "'")

    private fun speakablePath(path: String): String =
        path.replace(" ", "、").replace("|", "、").replace("'", "、")

    private fun commonSymbols(): List<String> =
        (listOf("，", "。", "？", "！") +
            CustomSymbolRepository.load(context).map { it.symbol } +
            ImeData.symbols["常用"].orEmpty())
            .filter { it.isNotBlank() }
            .distinct()

    private companion object {
        const val NINE_RAIL_TAG = "nine-punct-stack"
        const val NINE_CONTENT_TAG = "nine-symbol-scroll-content"
        const val FILTER_TAG = "nine-pinyin-path-filter"
        const val SELECTED_FILTER_TAG = "nine-pinyin-path-selected"
        const val MAX_VISIBLE_PATHS = 16
    }
}
