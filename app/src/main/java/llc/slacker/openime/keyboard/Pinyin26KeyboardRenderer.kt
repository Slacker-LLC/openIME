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
) {
    fun render(
        english: Boolean,
        shiftState: ShiftState,
        enterLabel: String,
    ) {
        ROWS.forEachIndexed { rowIndex, rowText ->
            val row = rowHost().apply {
                if (rowIndex == 1) tag = "key-row-secondary"
            }
            if (rowIndex == 2) {
                row.addView(
                    shiftKey(shiftState),
                    flexKeyParams(1.25f),
                )
            }

            rowText.forEach { character ->
                row.addView(
                    letterKey(character, english, shiftState),
                    flexKeyParams(),
                )
            }

            if (rowIndex == 2) {
                row.addView(createBackspaceKey(), flexKeyParams(1.25f))
            }
            keyboardBody.addView(row, rowParams())
        }

        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()

        val bottom = rowHost()
        bottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, 0, onDigits),
            flexKeyParams(weights.leftOuter),
        )
        // ，。 sits left of space and 中/英 right of it, next to the enter key.
        bottom.addView(punctuationKey(english), flexKeyParams(weights.leftInner))
        bottom.addView(
            createSpaceVoiceKey("空格", onSpace),
            flexKeyParams(weights.space),
        )
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

    private fun punctuationKey(english: Boolean): ImeKeyView {
        val spec = PunctuationKeyPolicy.spec(english)
        // A character key, not a function key: only character keys carry corner hints.
        return createKey(spec.comma, false, spec.period, ImeTypographyTokens.KEY_LETTER_COMPACT_SP, 0) {
            onCommitCharacter(spec.comma)
        }.apply {
            tag = "key-punctuation"
            contentDescription = "${spec.comma}，长按输入${spec.period}"
            setSecondaryVisible(true)
            setSecondaryAlpha(1f)
            setOnLongClickListener {
                onCommitCharacter(spec.period)
                true
            }
        }
    }

    private fun segmentKey(): ImeKeyView =
        createKey("分词", true, "@#/", ImeTypographyTokens.BODY_SP, 0, onPinyinSegment).apply {
            tag = "key-segment"
            contentDescription = "分词，长按输入@井号或斜杠"
            setOnLongClickListener {
                onShowChoicePopup(this, listOf("@", "#", "/"))
                true
            }
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
            // Long press opens a popup (other case, digit or mark): slide to one and
            // lift to type it, as the Sogou and iFlytek keyboards do.
            setOnLongClickListener {
                val upperShown = currentMainText.firstOrNull()?.isUpperCase() == true
                val choices = LetterHintPolicy.longPressChoices(character, english, upperShown, hintsEnabled())
                onShowChoicePopup(this, choices)
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
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    }
}
