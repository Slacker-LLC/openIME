package llc.slacker.openime.keyboard

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import llc.slacker.openime.core.ImeData
import llc.slacker.openime.data.CustomSymbolRepository
import llc.slacker.openime.editor.EditorInfoAdapter
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * Concrete first-frame renderer for number/decimal/phone keyboards.
 *
 * Keeps the original three-column digit grid, 返回 / 空格 / 0 bottom row,
 * and delete / . / @ / enter action column. Phone remapping is applied while
 * constructing each key, before the view is attached.
 */
internal class NumericKeyboardRenderer(
    private val context: Context,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightDp: () -> Int,
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
    private val markAccentKey: (ImeKeyView) -> Unit,
    private val onCommitCharacter: (String) -> Unit,
    private val onFeedback: () -> Unit,
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
        fun literal(tag: String, fallback: String): String =
            if (phone) PhoneKeypadPolicy.literalByTag[tag] ?: fallback else fallback
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
            cellHeightDp = keyRowHeightDp(),
            toPx = toPx,
            tagPrefix = "digit-symbol:",
            onCommit = onCommitCharacter,
            onFeedback = onFeedback,
        ).also {
            symbolRail = it
            if (phone) it.visibility = View.GONE
        }
        left.addView(
            rail,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp() * 3),
            ),
        )
        left.addView(
            createKey("符号", true, ImeTypographyTokens.BODY_SP, onShowSymbols).apply {
                tag = "key-symbols"
                markSideKey(this)
            },
            sideKeyParams(),
        )
        container.addView(left, adaptiveColumnParams(1f))

        val grid = LinearLayout(context).apply {
            tag = "digits-grid"
            orientation = LinearLayout.VERTICAL
        }
        DIGIT_ROWS.forEach { digits ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            digits.forEach { digit -> row.addView(digitKey(digit), flexKeyParams()) }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(keyRowHeightDp()),
                ),
            )
        }
        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp() * 3),
            ),
        )
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(
            createKey("返回", true, ImeTypographyTokens.BODY_SP, onReturnToText).apply {
                tag = "key:mode"
                markSideKey(this)
            },
            flexKeyParams(),
        )
        bottom.addView(
            if (phone) {
                val value = literal("key-space", "*")
                createKey(value, true, ImeTypographyTokens.KEY_NUMBER_SP) { onCommitCharacter(value) }.apply {
                    tag = "key-space"
                    markWhiteKey(this)
                }
            } else {
                createSpaceVoiceKey("空格", onSpace)
            },
            flexKeyParams(),
        )
        bottom.addView(digitKey(literal("key:0", "0"), tag = "key:0"), flexKeyParams())
        center.addView(
            bottom,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(keyRowHeightDp()),
            ),
        )
        container.addView(center, adaptiveColumnParams(25f / 7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "digits-actions"
        }
        side.addView(createBackspaceKey().apply { markSideKey(this) }, sideKeyParams())
        val period = literal("key:.", ".")
        side.addView(
            createKey(period, true, ImeTypographyTokens.KEY_NUMBER_SP) { onCommitCharacter(period) }.apply {
                tag = "key:."
                markSideKey(this)
            },
            sideKeyParams(),
        )
        // Keep the existing phone policy's # mapping on the restored @ cell.
        val at = literal("key:symbol", "@")
        side.addView(
            createKey(at, true, ImeTypographyTokens.BODY_SP) { onCommitCharacter(at) }.apply {
                tag = "key:@"
                markSideKey(this)
            },
            sideKeyParams(),
        )
        side.addView(
            createKey(enterLabel, true, ImeTypographyTokens.BODY_SP, onEnter).apply {
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

    private fun digitKey(digit: String, tag: String = "key:$digit"): ImeKeyView =
        createKey(digit, false, ImeTypographyTokens.KEY_NUMBER_SP) { onCommitCharacter(digit) }.apply {
            this.tag = tag
            markWhiteKey(this)
        }

    fun refreshSymbols() {
        val rail = symbolRail ?: return
        SymbolRailRenderer.populate(
            scroll = rail,
            symbols = digitSymbols(),
            cellHeightDp = keyRowHeightDp(),
            toPx = toPx,
            tagPrefix = "digit-symbol:",
            onCommit = onCommitCharacter,
            onFeedback = onFeedback,
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
        LinearLayout.LayoutParams(0, toPx(keyRowHeightDp()), weight)

    private fun sideKeyParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(keyRowHeightDp()))

    private fun adaptiveColumnParams(weight: Float) =
        LinearLayout.LayoutParams(0, toPx(nineBodyHeightDp()), weight)

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
