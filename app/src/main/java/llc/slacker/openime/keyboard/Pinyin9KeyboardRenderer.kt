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
        container.addView(left, adaptiveColumnParams(1f))

        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            buildNineGrid().apply { tag = "pinyin9-grid" },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineGridHeightDp()),
            ),
        )
        val centerBottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val weights = ProductionKeyPolicy.nineKeyBottomRowWeights()
        centerBottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, onDigits).apply {
                markSideKey(this)
            },
            flexKeyParams(weights.side),
        )
        centerBottom.addView(
            createSpaceVoiceKey("空格", onSpace),
            flexKeyParams(weights.space),
        )
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
        container.addView(center, adaptiveColumnParams(25f / 7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "pinyin9-actions"
        }
        side.addView(
            createBackspaceKey().apply { markSideKey(this) },
            sideKeyParams(nineBodyHeightDp() / 3),
        )
        side.addView(
            createKey("重输", true, null, ImeTypographyTokens.BODY_SP, onRetranslate).apply {
                tag = "key-retype"
                markSideKey(this)
            },
            sideKeyParams(nineBodyHeightDp() / 3),
        )
        side.addView(
            createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            sideKeyParams(nineBodyHeightDp() / 3),
        )
        container.addView(side, adaptiveColumnParams(1f))

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
        NINE_KEYS.forEachIndexed { rowIndex, rowDefinition ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowDefinition.forEach { (digit, subLabel) ->
                val segmentation = digit == "1"
                val key = createKey(
                    if (segmentation) "分词" else subLabel,
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
                    // Swipe up types the digit without waiting for a long press.
                    onSwipeUp = { onCommitCharacter(digit) }
                    swipeUpEnabled = this@Pinyin9KeyboardRenderer.swipeUpEnabled
                }
                onDigitKeyCreated(digit, key)
                row.addView(key, flexKeyParams())
            }
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
