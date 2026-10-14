package llc.slacker.openime.keyboard

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import llc.slacker.openime.core.ImeData
import llc.slacker.openime.data.CustomSymbolRepository
import llc.slacker.openime.editor.EditorInfoAdapter
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * Concrete first-frame renderer for the number/decimal/phone page.
 *
 * The page is the reference's own number layout
 * (`input_kbd_number_from26.xml:5-42`): a symbol rail over the return key on
 * the left, three columns of digits whose last cell is 符号 / 0 / `.`, and a
 * delete / space / send column on the right.
 *
 * Phone remapping and the symbol rail are resolved before the view is attached,
 * so no post-build hierarchy scan is needed.
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

        // The rail takes three key rows and the return key the fourth, as the
        // reference splits the column (input_kbd_number_from26.xml:10-15).
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
        }
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
                toPx(keyRowHeightDp() * NumberPadPolicy.RAIL_ROWS),
            ),
        )
        left.addView(
            createKey("返回", true, ImeTypographyTokens.BODY_SP, onReturnToText).apply {
                tag = "key:mode"
                markSideKey(this)
            },
            columnCellParams(keyRowHeightDp() * NumberPadPolicy.BACK_ROWS),
        )
        container.addView(left, adaptiveColumnParams(NumberPadPolicy.RAIL_COLUMN_WEIGHT))

        // Three digit columns, each closed by the reference's own fourth cell
        // (input_kbd_number_from26.xml:17-34).
        val stacks = NumberPadPolicy.DIGIT_COLUMN_TOPS.map { column ->
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                column.forEach { digit -> addView(digitKey(digit), columnCellParams()) }
            }
        }
        val tails = NumberPadPolicy.COLUMN_TAILS
        stacks[SYMBOL_COLUMN].addView(
            createKey(literal(SYMBOL_CELL_TAG, tails[SYMBOL_COLUMN]), true, ImeTypographyTokens.BODY_SP) {
                if (phone) onCommitCharacter(literal(SYMBOL_CELL_TAG, tails[SYMBOL_COLUMN])) else onShowSymbols()
            }.apply {
                tag = SYMBOL_CELL_TAG
                markWhiteKey(this)
            },
            columnCellParams(),
        )
        stacks[ZERO_COLUMN].addView(
            digitKey(tails[ZERO_COLUMN], value = literal("key:0", tails[ZERO_COLUMN]), tag = "key:0"),
            columnCellParams(),
        )
        stacks[PERIOD_COLUMN].addView(
            digitKey(literal("key:.", tails[PERIOD_COLUMN]), tag = "key:."),
            columnCellParams(),
        )
        stacks.forEach { container.addView(it, adaptiveColumnParams(NumberPadPolicy.DIGIT_COLUMN_WEIGHT)) }

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "digits-actions"
        }
        side.addView(
            createBackspaceKey().apply { markSideKey(this) },
            columnCellParams(),
        )
        side.addView(
            createSpaceVoiceKey("空格", onSpace),
            columnCellParams(),
        )
        side.addView(
            createKey(enterLabel, true, ImeTypographyTokens.BODY_SP, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            // The reference draws send twice as tall as the keys above it
            // (input_kbd_number_from26.xml:38-40).
            columnCellParams(keyRowHeightDp() * NumberPadPolicy.SEND_ROWS),
        )
        container.addView(side, adaptiveColumnParams(NumberPadPolicy.SEND_COLUMN_WEIGHT))

        keyboardBody.addView(
            container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(nineBodyHeightDp()),
            ),
        )
    }

    /** A digit cell: the reference draws every digit on a white key (`cls_keyboard_number9_button`). */
    private fun digitKey(digit: String, value: String = digit, tag: String = "key:$digit"): ImeKeyView =
        createKey(digit, false, ImeTypographyTokens.KEY_NUMBER_SP) { onCommitCharacter(value) }.apply {
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

    private fun columnCellParams(heightDp: Int = keyRowHeightDp()) =
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
        const val DIGITS_RAIL_TAG = "digits-symbol-scroll"
        const val DIGITS_CONTENT_TAG = "digits-symbol-scroll-content"

        /** The symbol-page cell of the first column; a phone field puts the dialer's `#` here. */
        const val SYMBOL_CELL_TAG = "key:symbol"

        const val SYMBOL_COLUMN = 0
        const val ZERO_COLUMN = 1
        const val PERIOD_COLUMN = 2
    }
}
