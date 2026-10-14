package llc.slacker.openime.keyboard

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * The 笔画 keyboard. It keeps the nine-key frame (symbol rail over 符号,
 * 删除/重输/确定 column, 123 · 空格 · 中/英 row) so switching between the two moves nothing
 * the user already knows, and lays the strokes out as the reference does: a
 * 3x3 grid whose last row carries 通配, @# and the two punctuation cells
 * (`input_kbd_stroke.xml:16-66`, see [StrokeGridPolicy]).
 */
internal class StrokeKeyboardRenderer(
    private val context: Context,
    private val keyboardBody: LinearLayout,
    private val toPx: (Int) -> Int,
    private val keyRowHeightDp: () -> Int,
    private val gridHeightDp: () -> Int,
    private val bodyHeightDp: () -> Int,
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
    private val onShowChoicePopup: (View, List<String>) -> Unit,
    private val markSideKey: (ImeKeyView) -> Unit,
    private val markWhiteKey: (ImeKeyView) -> Unit,
    private val onStroke: (String) -> Unit,
    private val swipeUpEnabled: () -> Boolean,
    private val onCommitCharacter: (String) -> Unit,
    private val onShowSymbols: () -> Unit,
    private val onDigits: () -> Unit,
    private val onSpace: () -> Unit,
    private val onModeSwitch: () -> Unit,
    private val onRetype: () -> Unit,
    private val onEnter: () -> Unit,
) {
    fun render(enterLabel: String) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = "stroke-layout"
        }

        // Symbol rail over a 符号 key, as on the nine-key and numeric keyboards.
        container.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    createSymbolRail(),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(gridHeightDp())),
                )
                addView(
                    createKey("符号", true, null, ImeTypographyTokens.BODY_SP, onShowSymbols).apply {
                        tag = "key-symbols"
                        markSideKey(this)
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(keyRowHeightDp())),
                )
            },
            columnParams(1f),
        )

        val center = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        center.addView(
            buildStrokeGrid().apply { tag = "stroke-grid" },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(gridHeightDp())),
        )
        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val weights = ProductionKeyPolicy.nineKeyBottomRowWeights()
        // White circles either side of the space bar, as the nine-key row draws
        // them: `cls_keyboard9_button_func_white_circle` for 123
        // (`input_kbd_stroke.xml:70`) and `cls_bk_white_circle` under
        // `cls_button_stroke2en` for 中/英 (`:82`).
        bottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, onDigits).apply { markWhiteKey(this) },
            keyParams(keyRowHeightDp(), weights.side),
        )
        bottom.addView(createSpaceVoiceKey("空格", onSpace), keyParams(keyRowHeightDp(), weights.space))
        bottom.addView(
            createKey("中/英", true, null, ImeTypographyTokens.BODY_SP, onModeSwitch).apply {
                tag = "key:mode"
                markWhiteKey(this)
            },
            keyParams(keyRowHeightDp(), weights.side),
        )
        center.addView(
            bottom,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(keyRowHeightDp())),
        )
        container.addView(center, columnParams(25f / 7f))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "stroke-actions"
        }
        // Delete, retype and send, with send twice as tall, as the reference
        // keyboard draws this column (the nine-key column has the same shape).
        val sideQuarterDp = bodyHeightDp() / 4
        side.addView(createBackspaceKey().apply { markSideKey(this) }, sideKeyParams(sideQuarterDp))
        side.addView(
            createKey("重输", true, null, ImeTypographyTokens.BODY_SP, onRetype).apply {
                tag = "key-retype"
                markSideKey(this)
            },
            sideKeyParams(sideQuarterDp),
        )
        side.addView(
            createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            sideKeyParams(sideQuarterDp * 2),
        )
        container.addView(side, columnParams(1f))

        keyboardBody.addView(
            container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(bodyHeightDp())),
        )
    }

    private fun buildStrokeGrid(): LinearLayout {
        val rows = StrokeGridPolicy.ROWS
        val rowHeightDp = gridHeightDp() / rows.size
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { cells ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            cells.forEach { cell -> row.addView(strokeCellKey(cell), keyParams(rowHeightDp)) }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(rowHeightDp)))
        }
        return grid
    }

    /** One cell of the grid: a stroke, 通配, or a mark the cell commits itself. */
    private fun strokeCellKey(cell: StrokeGridPolicy.Cell): ImeKeyView {
        val stroke = cell.stroke
        val textSize = strokeTextSize(cell)
        val key = if (stroke != null) {
            createKey(cell.label, false, cell.corner, textSize) { onStroke(stroke) }
        } else {
            val commit = cell.commit.orEmpty()
            createKey(cell.label, false, cell.corner, textSize) { onCommitCharacter(commit) }
        }
        return key.apply {
            tag = cell.tag
            contentDescription = if (stroke != null && cell.label == WILDCARD_LABEL) {
                "${cell.corner}，通配，代替一笔"
            } else if (stroke != null) {
                "${cell.corner}，${cell.label}"
            } else {
                "${cell.label}，角标 ${cell.corner}"
            }
            // Swipe up types the key's digit, as on the nine-key keyboard.
            val digit = cell.corner
            onSwipeUp = { onCommitCharacter(digit) }
            swipeUpEnabled = this@StrokeKeyboardRenderer.swipeUpEnabled
            if (cell.tag == AT_SYMBOL_TAG) {
                setOnLongClickListener {
                    onShowChoicePopup(this, StrokeGridPolicy.AT_SYMBOL_CHOICES)
                    true
                }
            }
            markWhiteKey(this)
        }
    }

    /** The reference's 笔画 faces: one per kind of cell (`style.xml:9-13`). */
    private fun strokeTextSize(cell: StrokeGridPolicy.Cell): Float = when {
        // `key_stroke_wildcard`, 44 units — the same 44 its function keys use.
        cell.label == WILDCARD_LABEL -> ImeTypographyTokens.BODY_SP
        // `key_stroke_at_symbol` / `key_stroke_punctuation`, both 54.
        cell.commit != null -> STROKE_MARK_SP
        // The five strokes themselves: `key_stroke`, 60 units.
        else -> STROKE_SP
    }

    private fun keyParams(heightDp: Int, weight: Float = 1f) =
        LinearLayout.LayoutParams(0, toPx(heightDp), weight)

    private fun sideKeyParams(heightDp: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(heightDp))

    private fun columnParams(weight: Float) =
        LinearLayout.LayoutParams(0, toPx(bodyHeightDp()), weight)

    private companion object {
        const val WILDCARD_LABEL = "通配"
        const val AT_SYMBOL_TAG = "key-stroke:at"

        /** `key_stroke`, 60 units over the reference's step of 3. */
        const val STROKE_SP = 20f

        /** `key_stroke_at_symbol` and `key_stroke_punctuation`, both 54 units. */
        const val STROKE_MARK_SP = 18f
    }
}
