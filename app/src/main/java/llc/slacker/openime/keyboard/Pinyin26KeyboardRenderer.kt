package llc.slacker.openime.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * Concrete renderer for the shared Chinese/English 26-key surface.
 *
 * It owns row construction, key geometry, 26-key hints, Shift presentation,
 * and the bottom-row balance. Composition/candidate state stays in the host.
 */
internal class Pinyin26KeyboardRenderer(
    private val context: Context,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightDp: () -> Int,
    private val createKey: (
        text: String,
        function: Boolean,
        secondary: String?,
        textSize: Float?,
        iconRes: Int,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createBackspaceKey: () -> ImeKeyView,
    private val createSpaceVoiceKey: (label: String, onTap: () -> Unit) -> ImeKeyView,
    private val onLetter: (String) -> Unit,
    private val onPinyinSegment: () -> Unit,
    private val onShowChoicePopup: (View, List<String>) -> Unit,
    private val onCommitCharacter: (String) -> Unit,
    private val hintsEnabled: () -> Boolean,
    private val swipeUpEnabled: () -> Boolean,
    private val onShift: () -> Unit,
    private val onShiftLongPress: () -> Unit,
    private val onDigits: () -> Unit,
    private val onModeSwitch: () -> Unit,
    private val onSpace: () -> Unit,
    private val onEnter: () -> Unit,
    private val onSymbols: () -> Unit = {},
    private val splitLayout: () -> Boolean = { false },
    private val uriField: () -> Boolean = { false },
) {
    fun render(
        english: Boolean,
        shiftState: ShiftState,
        enterLabel: String,
        landscape: Boolean = false,
    ) {
        if (splitLayout()) {
            renderSplit(english, shiftState, enterLabel)
            return
        }
        ROWS.forEachIndexed { rowIndex, rowText ->
            val row = rowHost().apply {
                if (rowIndex == 1) tag = "key-row-secondary"
            }
            if (rowIndex == 2) {
                row.addView(
                    shiftKey(shiftState),
                    flexKeyParams(EDGE_KEY_WEIGHT),
                )
            }

            rowText.forEachIndexed { index, character ->
                // The reference widens A and L by half a letter (weight 15
                // against 10) and the function keys of the last row likewise.
                val wide = rowIndex == 1 && (index == 0 || index == rowText.lastIndex)
                row.addView(
                    letterKey(character, english, shiftState),
                    flexKeyParams(if (wide) EDGE_KEY_WEIGHT else 1f),
                )
            }

            if (rowIndex == 2) {
                row.addView(createBackspaceKey(), flexKeyParams(EDGE_KEY_WEIGHT))
            }
            keyboardBody.addView(row, rowParams())
        }

        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights(landscape)
        val punctuation = PunctuationKeyPolicy.spec(english, uriField())

        val bottom = rowHost()
        bottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, 0, onDigits),
            flexKeyParams(weights.leftOuter),
        )
        // The merged ，。 key sits left of the space bar, 中/英 right of it.
        bottom.addView(punctuationKey(punctuation), flexKeyParams(weights.merged))
        // The reference's two symbol keys bookend the space bar: ，and 。in
        // Chinese, , and . in English, the address marks in a URL field.
        bottom.addView(symbolKey("key-symbol-comma", punctuation.comma), flexKeyParams(weights.symbol))
        bottom.addView(
            createSpaceVoiceKey("空格", onSpace),
            flexKeyParams(weights.space),
        )
        bottom.addView(symbolKey("key-symbol-period", punctuation.period), flexKeyParams(weights.symbol))
        bottom.addView(
            createKey(
                "中/英",
                true,
                null,
                ImeTypographyTokens.BODY_SP,
                0,
            ) { onModeSwitch() }.apply { tag = "key:mode" },
            flexKeyParams(weights.rightInner),
        )
        bottom.addView(
            createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, 0, onEnter).apply {
                tag = "key-enter"
            },
            flexKeyParams(weights.rightOuter),
        )
        keyboardBody.addView(bottom, rowParams())
    }

    /**
     * Landscape split keyboard, as Sogou draws it: each half is a complete thumb
     * keyboard, G and V sit on both sides so either thumb reaches them, and both
     * halves carry their own space bar. The gap between them stays empty.
     */
    private fun renderSplit(english: Boolean, shiftState: ShiftState, enterLabel: String) {
        fun row(build: LinearLayout.() -> Unit) {
            keyboardBody.addView(rowHost().apply(build), rowParams())
        }
        fun LinearLayout.letters(chars: String) {
            chars.forEach { addView(letterKey(it, english, shiftState), flexKeyParams(1f)) }
        }
        fun LinearLayout.gap() = addView(View(context), flexKeyParams(SPLIT_GAP))
        fun fn(label: String, onTap: () -> Unit) =
            createKey(label, true, null, ImeTypographyTokens.BODY_SP, 0, onTap)

        row { letters("qwert"); gap(); letters("yuiop") }
        row { letters("asdfg"); gap(); letters("ghjkl") }
        row {
            addView(shiftKey(shiftState), flexKeyParams(1f)); letters("zxcv")
            gap()
            letters("vbnm"); addView(createBackspaceKey(), flexKeyParams(1f))
        }
        row {
            addView(fn("符") { onSymbols() }.apply { tag = "key-symbols" }, flexKeyParams(1f))
            addView(fn("123", onDigits), flexKeyParams(1f))
            addView(punctuationKey(PunctuationKeyPolicy.spec(english, uriField())), flexKeyParams(1f))
            addView(createSpaceVoiceKey("空格", onSpace), flexKeyParams(2f))
            gap()
            addView(createSpaceVoiceKey("空格", onSpace), flexKeyParams(2f))
            addView(fn("。") { onCommitCharacter(if (english) "." else "。") }, flexKeyParams(1f))
            addView(fn("中/英") { onModeSwitch() }.apply { tag = "key:mode" }, flexKeyParams(1f))
            addView(
                createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, 0, onEnter).apply { tag = "key-enter" },
                flexKeyParams(1f),
            )
        }
    }

    private fun punctuationKey(spec: PunctuationKeyPolicy.Spec): ImeKeyView {
        // A character key, not a function key: only character keys carry corner hints.
        return createKey(spec.merged, false, spec.mergedCorner, ImeTypographyTokens.KEY_LETTER_COMPACT_SP, 0) {
            onCommitCharacter(spec.merged)
        }.apply {
            tag = "key-punctuation"
            contentDescription = "${spec.merged}，上滑或长按输入${spec.mergedCorner}"
            setSecondaryVisible(true)
            setSecondaryAlpha(1f)
            // The key is a mark and a corner in 8.8% of the row: the mark shrinks
            // rather than showing an ellipsis when the key or the system font
            // leaves it no room.
            allowLabelShrink()
            // Up swipes the corner mark, as the reference keyboard does.
            onSwipeUp = { onCommitCharacter(spec.mergedCorner) }
            setOnLongClickListener {
                onCommitCharacter(spec.mergedCorner)
                true
            }
        }
    }

    /**
     * One of the reference's two symbol keys beside the space bar
     * (`input_kbd_pinyin26.xml:153,156`). It carries no corner mark and no
     * bubble: the key is its own character.
     */
    private fun symbolKey(tag: String, text: String): ImeKeyView =
        createKey(text, false, null, ImeTypographyTokens.KEY_LETTER_COMPACT_SP, 0) {
            onCommitCharacter(text)
        }.apply {
            this.tag = tag
            contentDescription = text
            allowLabelShrink()
        }

    private fun segmentKey(): ImeKeyView =
        createKey("分词", true, null, ImeTypographyTokens.BODY_SP, 0, onPinyinSegment).apply {
            // `cls_button_shift` draws the label alone: the reference's 分词 key
            // carries no corner mark and no bubble (`style.xml:915-920`).
            tag = "key-segment"
            contentDescription = "分词"
        }

    private fun shiftKey(shiftState: ShiftState): ImeKeyView {
        val iconRes = EnglishShiftPolicy.icon(shiftState)
        return createKey("", true, null, null, iconRes, onShift).apply {
            tag = when (shiftState) {
                ShiftState.LOWERCASE -> "key-shift"
                ShiftState.SHIFT_ONCE -> "key-shift-active"
                ShiftState.CAPS_LOCK -> "key-shift-caps"
            }
            setOnLongClickListener {
                onShiftLongPress()
                true
            }
        }
    }

    private fun letterKey(
        character: Char,
        english: Boolean,
        shiftState: ShiftState,
    ): ImeKeyView {
        val base = character.toString()
        val main = if (english && shiftState != ShiftState.LOWERCASE) {
            character.uppercaseChar().toString()
        } else if (!english) {
            character.uppercaseChar().toString()
        } else {
            base
        }
        // The corner hint is a digit or symbol; swipe up or long press types it.
        val secondary = if (hintsEnabled()) LetterHintPolicy.hint(character, english) else null
        return createKey(main, false, secondary, ImeTypographyTokens.KEY_LETTER_COMPACT_SP, 0) {
            onLetter(base)
        }.apply {
            tag = "key:$base"
            if (secondary != null) {
                setSecondaryVisible(true)
                setSecondaryAlpha(1f)
                onSwipeUp = { onCommitCharacter(secondary) }
                swipeUpEnabled = this@Pinyin26KeyboardRenderer.swipeUpEnabled
            }
            // Long press opens the letter in both cases: slide to one and lift to
            // type it, as the reference keyboard does.
            setOnLongClickListener {
                onShowChoicePopup(this, LetterHintPolicy.caseChoices(character))
                true
            }
        }
    }

    private fun rowHost(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = rowParams()
    }

    private fun rowParams() =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            toPx(keyRowHeightDp()),
        )

    private fun flexKeyParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(
            0,
            toPx(keyRowHeightDp()),
            weight,
        )

    private companion object {
        const val SPLIT_GAP = 3.5f

        /** The reference skin's weight 15 against a letter's 10. */
        const val EDGE_KEY_WEIGHT = 1.5f
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }
}
