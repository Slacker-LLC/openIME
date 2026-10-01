package llc.slacker.openime

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns the text-edit panel and direct references to its actionable controls.
 *
 * Availability updates are O(number of actions) and no longer recurse through
 * the panel hierarchy to rediscover controls by tag.
 */
internal class TextEditorPanelController(
    private val context: Context,
    private val expandedPanel: LinearLayout,
    private val toPx: (Int) -> Int,
    private val panelBodyHeightPx: () -> Int,
    private val createHeader: (String) -> LinearLayout,
    private val createKey: (
        text: String,
        textSize: Float?,
        onTap: () -> Unit,
    ) -> ImeKeyView,
    private val createPanelButton: (String, Float, Boolean) -> TextView,
    private val isPasswordField: () -> Boolean,
    private val onTextEdit: (String) -> Unit,
    private val onFeedback: () -> Unit,
) {
    private data class ActionControl(
        val label: String,
        val view: View,
    )

    private val actionControls = linkedMapOf<String, ActionControl>()

    fun render() {
        actionControls.clear()
        expandedPanel.addView(
            createHeader("文本编辑"),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ),
        )

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(toPx(12), toPx(18), toPx(12), toPx(18))
            tag = "text_editor_panel"
        }
        val cross = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = "textedit-cross" }
        fun cell(label: String = "", action: String? = null, center: Boolean = false): TextView =
            createPanelButton(label, if (center) 14f else 24f, false).apply {
                if (action != null) {
                    contentDescription = when (action) { "up" -> "上"; "down" -> "下"; "left" -> "左"; else -> "右" }
                    setOnClickListener { onFeedback(); onTextEdit(action) }
                } else {
                    tag = if (center) "textedit-center" else "textedit-spacer"
                    isClickable = false; isFocusable = false
                }
            }
        listOf(
            listOf(cell(), cell("↑", "up"), cell()),
            listOf(cell("←", "left"), cell("光标", center = true), cell("→", "right")),
            listOf(cell(), cell("↓", "down"), cell()),
        ).forEach { items ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEach { row.addView(it, LinearLayout.LayoutParams(0, toPx(56), 1f).apply { setMargins(toPx(3), toPx(3), toPx(3), toPx(3)) }) }
            cross.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(62)))
        }
        body.addView(cross, LinearLayout.LayoutParams(0, toPx(186), 1.15f).apply { marginEnd = toPx(12) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        listOf(listOf("全选" to "select-all", "撤销" to "undo"), listOf("复制" to "copy", "剪切" to "cut"), listOf("粘贴" to "paste")).forEach { entries ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            entries.forEach { (label, action) ->
                val control = createKey(label, ImeTypographyTokens.BODY_SP) { onTextEdit(action) }.apply { tag = "textedit-action:$action" }
                actionControls[action] = ActionControl(label, control)
                row.addView(control, LinearLayout.LayoutParams(0, toPx(62), 1f))
            }
            actions.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, toPx(62)))
        }
        body.addView(actions, LinearLayout.LayoutParams(0, toPx(186), 1f))

        expandedPanel.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                panelBodyHeightPx(),
            ),
        )
        applyPasswordPolicy()
    }

    fun refreshAvailability(
        selectionAvailable: Boolean,
        clipboardAvailable: Boolean,
    ) {
        actionControls.forEach { (_, control) ->
            val dynamicReason = when {
                TextEditControlPolicy.isUnavailableLabel(
                    control.label,
                    isPasswordField(),
                ) -> TextEditControlPolicy.unavailableReason(
                    control.label,
                    isPasswordField(),
                )
                control.label in setOf("复制", "剪切") && !selectionAvailable ->
                    "请先选择文本"
                control.label == "粘贴" && !clipboardAvailable ->
                    "剪贴板暂无文本"
                else -> null
            }
            applyAvailability(control, dynamicReason)
        }
    }

    private fun applyPasswordPolicy() {
        actionControls.forEach { (_, control) ->
            if (
                TextEditControlPolicy.isUnavailableLabel(
                    control.label,
                    isPasswordField(),
                )
            ) {
                applyAvailability(
                    control,
                    TextEditControlPolicy.unavailableReason(
                        control.label,
                        isPasswordField(),
                    ),
                )
            }
        }
    }

    private fun applyAvailability(
        control: ActionControl,
        reason: String?,
    ) {
        val unavailable = reason != null
        control.view.isEnabled = !unavailable
        control.view.isClickable = !unavailable
        control.view.alpha = if (unavailable) ImeSurfacePolicy.DISABLED_ALPHA else 1f
        control.view.contentDescription =
            if (unavailable && Build.VERSION.SDK_INT < 30) {
                "${control.label}，不可用：$reason"
            } else {
                control.label
            }
        if (Build.VERSION.SDK_INT >= 30) {
            control.view.stateDescription = if (unavailable) reason else "可用"
        }
    }

    private companion object {
        val QUICK_ACTIONS = listOf(
            "全选" to "select-all",
            "复制" to "copy",
            "剪切" to "cut",
            "粘贴" to "paste",
            "撤销" to "undo",
        )
    }
}
