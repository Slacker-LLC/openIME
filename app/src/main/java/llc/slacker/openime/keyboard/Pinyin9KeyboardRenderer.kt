package llc.slacker.openime.keyboard

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * Concrete first-frame renderer for the Chinese 9-key surface.
 *
 * Candidate decoding stays in the host pipeline. The renderer owns key/column
 * geometry and passes created digit keys to controllers by explicit reference.
 */
internal class Pinyin9KeyboardRenderer(
    private val context: Context,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightDp: () -> Int,
    private val nineGridHeightDp: () -> Int,
    private val nineBodyHeightDp: () -> Int,
    private val doubleKeyHeightDp: () -> Int,
    private val createKey: (
        text: String,
        function: Boolean,
        secondary: String?,
        textSize: Float?,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createBackspaceKey: () -> ImeKeyView,
    private val createSpaceVoiceKey: (label: String, onTap: () -> Unit) -> ImeKeyView,
    private val createSymbolRail: () -> ScrollView,
    private val markSideKey: (ImeKeyView) -> Unit,
    private val markWhiteKey: (ImeKeyView) -> Unit,
    private val onDigitKeyCreated: (String, ImeKeyView) -> Unit,
    private val onNineKey: (String) -> Unit,
    private val onPinyinSegment: () -> Unit,
    private val onShowChoicePopup: (View, List<String>) -> Unit,
    private val onShowChoiceRows: (View, List<List<String>>) -> Unit,
    private val swipeUpEnabled: () -> Boolean,
    private val onCommitCharacter: (String) -> Unit,
    private val onShowSymbols: () -> Unit,
    private val onDigits: () -> Unit,
    private val onSpace: () -> Unit,
    private val onModeSwitch: () -> Unit,
    private val onRetranslate: () -> Unit,
    private val onEnter: () -> Unit,
    private val splitLayout: () -> Boolean = { false },
    private val mirrored: () -> Boolean = { false },
    private val onMirror: () -> Unit = {},
) {
    fun render(enterLabel: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "pinyin9-layout"
        }

        // Symbol rail over a 符号 key, as on the numeric keyboard.
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        left.addView(
            createSymbolRail(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineGridHeightDp()),
            ),
        )
        left.addView(
            createKey("符号", true, null, ImeTypographyTokens.BODY_SP, onShowSymbols).apply {
                tag = "key-symbols"
                markSideKey(this)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp()),
            ),
        )
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            buildNineGrid().apply { tag = "pinyin9-grid" },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineGridHeightDp()),
            ),
        )
        val centerBottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val weights = ProductionKeyPolicy.nineKeyBottomRowWeights()
        centerBottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, onDigits).apply { markSideKey(this) },
            flexKeyParams(weights.side),
        )
        centerBottom.addView(createSpaceVoiceKey("空格", onSpace), flexKeyParams(weights.space))
        centerBottom.addView(
            createKey("中/英", true, null, ImeTypographyTokens.BODY_SP, onModeSwitch).apply {
                tag = "key:mode"
                markSideKey(this)
            },
            flexKeyParams(weights.side),
        )
        center.addView(
            centerBottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp()),
            ),
        )
        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pinyin9-actions"
        }
        // Keep the zero key separate from retype and enter, as on the phone keypad.
        side.addView(createBackspaceKey().apply { markSideKey(this) }, sideKeyParams(nineBodyHeightDp() / 4))
        side.addView(
            createKey("重输", true, null, ImeTypographyTokens.BODY_SP, onRetranslate).apply {
                tag = "key-retype"
                markSideKey(this)
            },
            sideKeyParams(nineBodyHeightDp() / 4),
        )
        side.addView(
            createKey("0", true, null, ImeTypographyTokens.KEY_LETTER_SP) { onCommitCharacter("0") }.apply {
                tag = "key-9:0"
                contentDescription = "0"
                markSideKey(this)
            },
            sideKeyParams(nineBodyHeightDp() / 4),
        )
        side.addView(
            createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            sideKeyParams(nineBodyHeightDp() / 4),
        )
        if (splitLayout()) {
            // Keep all Chinese keys in their familiar three-by-three order.
            // The other hand gets direct numbers and the existing symbol rail.
            val numbers = buildNumberPad()
            val numberHand = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "pinyin9-number-hand"
                addView(left, adaptiveColumnParams(1f))
                addView(numbers, adaptiveColumnParams(3f))
            }
            val chineseHand = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = "pinyin9-chinese-hand"
                addView(center, adaptiveColumnParams(3f))
                addView(side, adaptiveColumnParams(1f))
            }
            val hands = if (mirrored()) listOf(chineseHand, numberHand) else listOf(numberHand, chineseHand)
            container.addView(hands[0], adaptiveColumnParams(1f))
            container.addView(View(context), LinearLayout.LayoutParams(toPx(24), toPx(nineBodyHeightDp())))
            container.addView(hands[1], adaptiveColumnParams(1f))
        } else {
            container.addView(left, adaptiveColumnParams(1f))
            container.addView(center, adaptiveColumnParams(25f / 7f))
            container.addView(side, adaptiveColumnParams(1f))
        }

        keyboardBody.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineBodyHeightDp()),
            ),
        )
    }

    private fun buildNineGrid(): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        NINE_KEYS.forEach { rowDefinition ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowDefinition.forEach { (digit, _) -> row.addView(createNineDigitKey(digit), flexKeyParams()) }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(keyRowHeightDp()),
                ),
            )
        }
        return grid
    }

    private fun buildNumberPad(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        (1..9).chunked(3).forEach { digits ->
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    digits.forEach { digit ->
                        addView(createKey(digit.toString(), false, null, ImeTypographyTokens.KEY_LETTER_SP) {
                            onCommitCharacter(digit.toString())
                        }.apply {
                            tag = "key-split-number:$digit"
                            contentDescription = "数字 $digit"
                            markWhiteKey(this)
                        }, flexKeyParams())
                    }
                },
                sideKeyParams(keyRowHeightDp()),
            )
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(createKey("，", true, null, ImeTypographyTokens.BODY_SP) {
                    onCommitCharacter("，")
                }.apply { tag = "key-punctuation"; markSideKey(this) }, flexKeyParams())
                addView(createKey("0", false, null, ImeTypographyTokens.KEY_LETTER_SP) {
                    onCommitCharacter("0")
                }.apply { tag = "key-split-number:0"; markWhiteKey(this) }, flexKeyParams())
                addView(createKey("互换", true, null, ImeTypographyTokens.BODY_SP, onMirror).apply {
                    tag = "key-mirror"
                    contentDescription = "左右互换"
                    markSideKey(this)
                }, flexKeyParams())
            },
            sideKeyParams(keyRowHeightDp()),
        )
    }

    private fun createNineDigitKey(digit: String): ImeKeyView {
        val segmentation = digit == "1"
        val labels = NINE_KEYS.flatten().first { it.first == digit }.second
        val key = createKey(
            if (segmentation) "分词" else labels,
            false,
            digit,
            if (segmentation) ImeTypographyTokens.BODY_SP else ImeTypographyTokens.CANDIDATE_SP,
        ) {
            if (segmentation) onPinyinSegment() else onNineKey(digit)
        }.apply {
            tag = "key-9:$digit"
            contentDescription = if (segmentation) "1，分词" else digit
            markWhiteKey(this)
            if (segmentation) {
                setOnLongClickListener {
                    onShowChoicePopup(this, listOf("@", "#", "/"))
                    true
                }
            } else {
                val rows = NineKeyLongPressPolicy.choiceRows(digit)
                if (rows.isNotEmpty()) {
                    setOnLongClickListener {
                        onShowChoiceRows(this, rows)
                        true
                    }
                }
            }
            onSwipeUp = { onCommitCharacter(digit) }
            swipeUpEnabled = this@Pinyin9KeyboardRenderer.swipeUpEnabled
        }
        onDigitKeyCreated(digit, key)
        return key
    }

    private fun flexKeyParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(
            0,
            toPx(keyRowHeightDp()),
            weight,
        )

    private fun sideKeyParams(heightDp: Int) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            toPx(heightDp),
        )

    private fun adaptiveColumnParams(weight: Float) =
        LinearLayout.LayoutParams(
            0,
            toPx(nineBodyHeightDp()),
            weight,
        )

    private companion object {
        val NINE_KEYS = listOf(
            listOf("1" to "@#", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        )
    }
}
