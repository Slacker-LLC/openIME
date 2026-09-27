package llc.slacker.openime

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Concrete first-frame renderer for number/decimal/phone keyboards.
 *
 * Phone remapping and the symbol rail are resolved before the view is attached,
 * so no post-build hierarchy scan is needed.
 */
internal class NumericKeyboardRenderer(
    private val context: Context,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightDp: () -> Int,
    private val nineGridHeightDp: () -> Int,
    private val nineBodyHeightDp: () -> Int,
    private val createKey: (
        text: String,
        function: Boolean,
        textSize: Float?,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createBackspaceKey: () -> ImeKeyView,
    private val createSpaceVoiceKey: (label: String, onTap: () -> Unit) -> ImeKeyView,
    private val markSideKey: (ImeKeyView) -> Unit,
    private val markWhiteKey: (ImeKeyView) -> Unit,
    private val onCommitCharacter: (String) -> Unit,
    private val onShowSymbols: () -> Unit,
    private val onReturnToText: () -> Unit,
    private val onSpace: () -> Unit,
    private val onEnter: () -> Unit,
) {
    private var symbolRail: ScrollView? = null

    fun render(
        editorKind: EditorInfoAdapter.EditorKind,
        enterLabel: String,
    ) {
        val phone = editorKind == EditorInfoAdapter.EditorKind.PHONE
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "digits-layout"
        }

        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rail = SymbolRailRenderer.build(
            context = context,
            railTag = DIGITS_RAIL_TAG,
            contentTag = DIGITS_CONTENT_TAG,
            contentDescription = "数字键盘符号，上下滑动查看更多",
            symbols = digitSymbols(),
            tagPrefix = "digit-symbol:",
            onCommit = onCommitCharacter,
            onFeedback = {},
        ).also {
            symbolRail = it
            if (phone) it.visibility = View.GONE
        }
        left.addView(
            rail,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineGridHeightDp()),
            ),
        )
        left.addView(
            createKey("符号", true, 13f, onShowSymbols).apply {
                markSideKey(this)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp()),
            ).apply { topMargin = toPx(ImeGeometryTokens.KEY_ROW_GAP_DP) },
        )
        container.addView(left, adaptiveColumnParams(1f))

        val grid = LinearLayout(context).apply {
            tag = "digits-grid"
            orientation = LinearLayout.VERTICAL
        }
        DIGIT_ROWS.forEachIndexed { rowIndex, rowDigits ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            rowDigits.forEach { digit ->
                row.addView(
                    createKey(digit, false, 22f) { onCommitCharacter(digit) }.apply {
                        tag = "key:$digit"
                        markWhiteKey(this)
                    },
                    flexKeyParams(),
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(keyRowHeightDp()),
                ).apply {
                    if (rowIndex < DIGIT_ROWS.lastIndex) {
                        bottomMargin = toPx(ImeGeometryTokens.KEY_ROW_GAP_DP)
                    }
                },
            )
        }

        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineGridHeightDp()),
            ),
        )
        val centerBottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        centerBottom.addView(
            createKey("返回", true, 14f, onReturnToText).apply {
                tag = "key:mode"
                markSideKey(this)
            },
            flexKeyParams(),
        )
        centerBottom.addView(
            if (phone) {
                createKey("*", true, 14f) { onCommitCharacter("*") }.apply {
                    tag = "key-space"
                    markWhiteKey(this)
                }
            } else {
                createSpaceVoiceKey("空格", onSpace).apply {
                    markWhiteKey(this)
                }
            },
            flexKeyParams(),
        )
        centerBottom.addView(
            createKey(if (phone) "+" else ".", false, 22f) {
                onCommitCharacter(if (phone) "+" else ".")
            }.apply {
                tag = "key:."
                markWhiteKey(this)
            },
            flexKeyParams(),
        )
        center.addView(
            centerBottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp()),
            ).apply { topMargin = toPx(ImeGeometryTokens.KEY_ROW_GAP_DP) },
        )
        container.addView(center, adaptiveColumnParams(3.7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "digits-actions"
        }
        side.addView(
            createBackspaceKey().apply { markSideKey(this) },
            sideKeyParams(includeBottomGap = true),
        )
        side.addView(
            createKey("0", false, 22f) { onCommitCharacter("0") }.apply {
                tag = "key:0"
                markSideKey(this)
            },
            sideKeyParams(includeBottomGap = true),
        )
        side.addView(
            createKey(if (phone) "#" else "@", true, 15f) {
                onCommitCharacter(if (phone) "#" else "@")
            }.apply {
                tag = "key:@"
                markSideKey(this)
            },
            sideKeyParams(includeBottomGap = true),
        )
        side.addView(
            createKey(enterLabel, true, 13f, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            sideKeyParams(),
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

    fun refreshSymbols() {
        val rail = symbolRail ?: return
        SymbolRailRenderer.populate(
            scroll = rail,
            symbols = digitSymbols(),
            tagPrefix = "digit-symbol:",
            onCommit = onCommitCharacter,
            onFeedback = {},
        )
    }

    private fun digitSymbols(): List<String> =
        (
            CustomSymbolRepository.load(context)
                .map { it.symbol }
                .filter { it.isNotBlank() } +
                listOf("%", "+", "−", "＊") +
                ImeData.symbols["常用"].orEmpty()
                    .filter { it.isNotBlank() }
        ).distinct()

    private fun flexKeyParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(
            0,
            toPx(keyRowHeightDp()),
            weight,
        ).apply {
            marginStart = toPx(2)
            marginEnd = toPx(2)
        }

    private fun sideKeyParams(includeBottomGap: Boolean = false) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            toPx(keyRowHeightDp()),
        ).apply {
            if (includeBottomGap) bottomMargin = toPx(ImeGeometryTokens.KEY_ROW_GAP_DP)
        }

    private fun adaptiveColumnParams(weight: Float) =
        LinearLayout.LayoutParams(
            0,
            toPx(nineBodyHeightDp()),
            weight,
        ).apply {
            marginStart = toPx(2)
            marginEnd = toPx(2)
        }

    private companion object {
        const val DIGITS_RAIL_TAG = "digits-symbol-scroll"
        const val DIGITS_CONTENT_TAG = "digits-symbol-scroll-content"
        val DIGIT_ROWS = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        )
    }
}
