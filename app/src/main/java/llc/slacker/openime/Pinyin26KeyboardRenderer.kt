package llc.slacker.openime

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout

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
    private val onShift: () -> Unit,
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
                    if (english) shiftKey(shiftState) else segmentKey(),
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
            createKey("123", true, null, 15f, 0, onDigits),
            flexKeyParams(weights.leftOuter),
        )
        val punctuation = if (english) "." else "，"
        bottom.addView(
            createKey(
                punctuation,
                true,
                null,
                15f,
                0,
            ) { onCommitCharacter(punctuation) },
            flexKeyParams(weights.leftInner),
        )
        bottom.addView(
            createSpaceVoiceKey(if (english) "space" else "空格", onSpace),
            flexKeyParams(weights.space),
        )
        bottom.addView(
            createKey("中/英", true, null, 14f, 0, onModeSwitch).apply {
                tag = "key:mode"
            },
            flexKeyParams(weights.rightInner),
        )
        bottom.addView(
            createKey(enterLabel, true, null, 15f, 0, onEnter).apply {
                tag = "key-enter"
            },
            flexKeyParams(weights.rightOuter),
        )
        keyboardBody.addView(bottom, rowParams(includeBottomGap = false))
    }

    private fun segmentKey(): ImeKeyView =
        createKey("分词", true, "@#/", 12f, 0, onPinyinSegment).apply {
            tag = "key-segment"
            contentDescription = "分词，长按输入@井号或斜杠"
            setOnLongClickListener {
                onShowChoicePopup(this, listOf("@", "#", "/"))
                true
            }
        }

    private fun shiftKey(shiftState: ShiftState): ImeKeyView {
        val iconRes = if (shiftState == ShiftState.CAPS_LOCK) {
            R.drawable.ic_caps_lock
        } else {
            R.drawable.ic_shift
        }
        return createKey("", true, null, null, iconRes, onShift).apply {
            tag = when (shiftState) {
                ShiftState.LOWERCASE -> "key-shift"
                ShiftState.SHIFT_ONCE -> "key-shift-active"
                ShiftState.CAPS_LOCK -> "key-shift-caps"
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
        } else {
            base
        }
        val secondary = if (english) null else DIGIT_HINTS[character]
        return createKey(main, false, secondary, 20f, 0) {
            onLetter(base)
        }.apply {
            tag = "key:$base"
            // 26-key keeps the surface visually clean while preserving the
            // long-press digit action.
            if (secondary != null) {
                setSecondaryVisible(false)
                setOnLongClickListener {
                    onCommitCharacter(secondary)
                    true
                }
            }
        }
    }

    private fun rowHost(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        layoutParams = rowParams()
    }

    private fun rowParams(includeBottomGap: Boolean = true) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            toPx(keyRowHeightDp()),
        ).apply {
            if (includeBottomGap) bottomMargin = toPx(ImeGeometryTokens.KEY_ROW_GAP_DP)
        }

    private fun flexKeyParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(
            0,
            toPx(keyRowHeightDp()),
            weight,
        ).apply {
            marginStart = toPx(2)
            marginEnd = toPx(2)
        }

    private companion object {
        val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val DIGIT_HINTS = mapOf(
            'q' to "1",
            'w' to "2",
            'e' to "3",
            'r' to "4",
            't' to "5",
            'y' to "6",
            'u' to "7",
            'i' to "8",
            'o' to "9",
            'p' to "0",
        )
    }
}
