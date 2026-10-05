package llc.slacker.openime.keyboard

import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import llc.slacker.openime.candidate.Stroke
import llc.slacker.openime.theme.ImeTypographyTokens
import llc.slacker.openime.widget.ImeKeyView

/**
 * The 笔画 keyboard. It keeps the nine-key frame (symbol rail, 删除/重输/确定
 * column, 123 · 空格 · 中/英 row) so switching between the two moves nothing
 * the user already knows, and puts the five strokes and 通配 in two rows of
 * large keys in their usual order: 一 丨 丿 / 丶 乛 通配, numbered 1–5 as in
 * other stroke keyboards.
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
    private val markSideKey: (ImeKeyView) -> Unit,
    private val markWhiteKey: (ImeKeyView) -> Unit,
    private val onStroke: (String) -> Unit,
    private val swipeUpEnabled: () -> Boolean,
    private val onCommitCharacter: (String) -> Unit,
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

        container.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    createSymbolRail(),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(bodyHeightDp())),
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
        bottom.addView(
            createKey("123", true, null, ImeTypographyTokens.BODY_SP, onDigits).apply { markSideKey(this) },
            keyParams(keyRowHeightDp(), weights.side),
        )
        bottom.addView(createSpaceVoiceKey("空格", onSpace), keyParams(keyRowHeightDp(), weights.space))
        bottom.addView(
            createKey("中/英", true, null, ImeTypographyTokens.BODY_SP, onModeSwitch).apply {
                tag = "key:mode"
                markSideKey(this)
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
        side.addView(createBackspaceKey().apply { markSideKey(this) }, sideKeyParams())
        side.addView(
            createKey("重输", true, null, ImeTypographyTokens.BODY_SP, onRetype).apply {
                tag = "key-retype"
                markSideKey(this)
            },
            sideKeyParams(),
        )
        side.addView(
            createKey(enterLabel, true, null, ImeTypographyTokens.BODY_SP, onEnter).apply {
                tag = "key-enter"
                markSideKey(this)
            },
            sideKeyParams(),
        )
        container.addView(side, columnParams(1f))

        keyboardBody.addView(
            container,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(bodyHeightDp())),
        )
    }

    private fun buildStrokeGrid(): LinearLayout {
        val rowHeightDp = gridHeightDp() / ROWS.size
        val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        ROWS.forEach { strokes ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            strokes.forEach { stroke ->
                val key = if (stroke == null) {
                    createKey("通配", false, null, ImeTypographyTokens.BODY_SP) {
                        onStroke(Stroke.WILDCARD_GLYPH)
                    }.apply {
                        tag = "key-stroke:wildcard"
                        contentDescription = "通配，代替一笔"
                    }
                } else {
                    createKey(stroke.glyph, false, stroke.digit.toString(), ImeTypographyTokens.CANDIDATE_SP) {
                        onStroke(stroke.glyph)
                    }.apply {
                        tag = "key-stroke:${stroke.code}"
                        contentDescription = "${stroke.digit}，${stroke.label}"
                        // Swipe up types the key's digit, as on the nine-key keyboard.
                        onSwipeUp = { onCommitCharacter(stroke.digit.toString()) }
                        swipeUpEnabled = this@StrokeKeyboardRenderer.swipeUpEnabled
                    }
                }
                markWhiteKey(key)
                row.addView(key, keyParams(rowHeightDp))
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(rowHeightDp)))
        }
        return grid
    }

    private fun keyParams(heightDp: Int, weight: Float = 1f) =
        LinearLayout.LayoutParams(0, toPx(heightDp), weight)

    private fun sideKeyParams() =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(bodyHeightDp() / 3))

    private fun columnParams(weight: Float) =
        LinearLayout.LayoutParams(0, toPx(bodyHeightDp()), weight)

    private companion object {
        /** null is 通配. */
        val ROWS: List<List<Stroke?>> = listOf(
            listOf(Stroke.HENG, Stroke.SHU, Stroke.PIE),
            listOf(Stroke.DIAN, Stroke.ZHE, null),
        )
    }
}
