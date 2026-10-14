package llc.slacker.openime.keyboard

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
import llc.slacker.openime.candidate.CandidatePipeline
import llc.slacker.openime.candidate.CandidateResolver
import llc.slacker.openime.candidate.NineKeyReading
import llc.slacker.openime.data.RailSymbolRepository

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
    private val onChooseReading: (NineKeyReading) -> Unit,
    private val fixedPrefix: () -> String,
    private val onEditSymbols: () -> Unit,
) {
    private enum class RailMode { SYMBOLS, PINYIN }

    private var rail: ScrollView? = null
    private var railMode = RailMode.SYMBOLS
    private var renderedChoices: List<NineKeyReading> = emptyList()
    private var renderedSelected: NineKeyReading? = null
    /** The open digits the list was built for: a new tail is a new list, so it starts at the top. */
    private var renderedDigits: String? = null

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
            cellHeightDp = symbolCellHeightDp(),
            toPx = toPx,
            tagPrefix = "punct:",
            onCommit = onCommit,
            onFeedback = onFeedback,
            onAdd = onEditSymbols,
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

        // Text before the last boundary is already fixed. The rail always
        // offers the syllables that can start the still-open tail.
        val tail = text.removePrefix(fixedPrefix()).lowercase()
        val digits = openTailDigits(tail)
        val resolver = context as? CandidateResolver
        if (digits == null || resolver == null) {
            renderSymbols()
            return
        }

        val choices = resolver.nineKeyReadingsFor(digits, tail)
            .asSequence()
            .filter { it.syllables.isNotEmpty() }
            .distinctBy { it.display }
            .take(MAX_VISIBLE_PATHS)
            .toList()
        if (choices.isEmpty()) {
            renderSymbols()
            return
        }

        // The reading the pre-edit is showing: the list leads with it and a tap
        // on it cannot change anything. The pre-edit is where the reference
        // shows which reading is current, so nothing is highlighted here.
        val shown = tail.filter { it in 'a'..'z' }
        val selected = choices.firstOrNull { it.coversAll && it.syllables.joinToString("") == shown }
            ?: choices.firstOrNull { shown.startsWith(it.syllables.joinToString("")) }
            ?: choices.first()
        renderPinyinChoices(digits = digits, choices = choices, selected = selected) { chosen ->
            onChooseReading(chosen)
        }
    }

    /** Digits for an open tail made of letters and/or still-undecoded digits. */
    private fun openTailDigits(tail: String): String? {
        if (tail.isEmpty()) return null
        val out = StringBuilder(tail.length)
        tail.forEach { ch ->
            when {
                // The local decoder separates guessed syllables with spaces.
                ch == ' ' || ch == '\'' -> Unit
                ch in '2'..'9' -> out.append(ch)
                else -> out.append(CandidatePipeline.nineKeyDigitsFor(ch.toString()) ?: return null)
            }
        }
        return out.toString().ifEmpty { null }
    }

    private fun renderSymbols(force: Boolean = false) {
        val scroll = rail ?: return
        if (railMode == RailMode.SYMBOLS && !force) return

        SymbolRailRenderer.populate(
            scroll = scroll,
            symbols = commonSymbols(),
            cellHeightDp = symbolCellHeightDp(),
            toPx = toPx,
            tagPrefix = "punct:",
            onCommit = onCommit,
            onFeedback = onFeedback,
            onAdd = onEditSymbols,
        )
        railMode = RailMode.SYMBOLS
        renderedChoices = emptyList()
        renderedSelected = null
        // The next composition builds a new list; it starts at the top.
        renderedDigits = null
        (scroll.getChildAt(0) as? LinearLayout)?.tag = NINE_CONTENT_TAG
        if (scroll.scrollY != 0) scroll.post { scroll.scrollTo(0, 0) }
        scroll.contentDescription = "九键常用符号，上下滑动查看更多"
        onRailChanged(scroll)
    }

    private fun renderPinyinChoices(
        digits: String,
        choices: List<NineKeyReading>,
        selected: NineKeyReading,
        onSelect: (NineKeyReading) -> Unit,
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
        // The same flat list the symbols use: one line per reading, a hairline
        // between them, nothing highlighted. More readings fit by scrolling
        // instead of by shrinking the text, and the item under the finger keeps
        // its place when the list is rebuilt.
        content.tag = PINYIN_PANEL_TAG
        choices.forEachIndexed { index, choice ->
            if (index > 0) SymbolRailRenderer.addDivider(content, toPx)
            val active = choice == selected
            content.addView(
                TextView(content.context).apply {
                    text = choice.display
                    textSize = RAIL_ITEM_TEXT_SP
                    // The reference draws reading items a step heavier than the
                    // marks (weight 600 against 500) at the same size.
                    typeface = Typeface.create(RAIL_ITEM_TYPEFACE, Typeface.NORMAL)
                    gravity = Gravity.CENTER
                    tag = if (active) SELECTED_FILTER_TAG else FILTER_TAG
                    contentDescription =
                        if (active) {
                            "九键拼音${speakablePath(choice.display)}，已选择"
                        } else {
                            "九键拼音${choice.display}，双击选择"
                        }
                    if (Build.VERSION.SDK_INT >= 30) {
                        stateDescription = if (active) "已选择" else "未选择"
                    }
                    isSelected = active
                    isClickable = true
                    isFocusable = true
                    maxLines = 1
                    includeFontPadding = false
                    setPadding(toPx(4), 0, toPx(4), 0)
                    inheritedTextColor?.let(::setTextColor)
                    setOnClickListener {
                        onFeedback()
                        onSelect(choice)
                    }
                },
                SymbolRailRenderer.flatCellParams(heightDp = cellHeightDp(), toPx = toPx),
            )
        }

        railMode = RailMode.PINYIN
        renderedChoices = choices.toList()
        renderedSelected = selected
        scroll.contentDescription = "九键拼音读法，上下滑动查看更多"
        onRailChanged(scroll)
        // New digits are a new list: show it from its start. A refresh for the
        // same digits leaves the scroll position where the user put it.
        val tailChanged = renderedDigits != digits
        renderedDigits = digits
        if (tailChanged && scroll.scrollY != 0) scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun speakablePath(path: String): String =
        path.replace(" ", "、").replace("|", "、").replace("'", "、")

    /** Three symbols to the rail's three key rows, level with the keys beside them. */
    private fun symbolCellHeightDp(): Int = cellHeightDp()

    /** The user's rail (ten common marks until edited), then ＋ to edit it. */
    private fun commonSymbols(): List<String> = RailSymbolRepository.load(context)

    private companion object {
        const val NINE_RAIL_TAG = "nine-punct-stack"
        const val NINE_CONTENT_TAG = "nine-symbol-scroll-content"
        const val FILTER_TAG = "nine-pinyin-path-filter"
        const val SELECTED_FILTER_TAG = "nine-pinyin-path-selected"
        const val PINYIN_PANEL_TAG = "nine-pinyin-panel"
        const val MAX_VISIBLE_PATHS = 16

        /** The symbol cells' own size: one list, one text size for both kinds of item. */
        const val RAIL_ITEM_TEXT_SP = 20f
        const val RAIL_ITEM_TYPEFACE = "sans-serif-medium"
    }
}
