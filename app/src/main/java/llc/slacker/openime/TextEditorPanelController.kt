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
            orientation = LinearLayout.VERTICAL
            setPadding(toPx(10), toPx(10), toPx(10), toPx(10))
            tag = "text_editor_panel"
        }

        val quick = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        QUICK_ACTIONS.forEach { (label, action) ->
            val control = createKey(label, 10f) { onTextEdit(action) }.apply {
                tag = "textedit-action:$action"
            }
            actionControls[action] = ActionControl(label, control)
            quick.addView(
                control,
                LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply {
                    marginEnd = toPx(5)
                },
            )
        }
        body.addView(
            quick,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
            ).apply { bottomMargin = toPx(10) },
        )

        val cross = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            tag = "textedit-cross"
        }
        fun cell(
            label: String? = null,
            action: String? = null,
            center: Boolean = false,
        ): TextView = createPanelButton(
            label ?: "",
            if (center) 9f else 14f,
            !center,
        ).apply {
            if (action != null) {
                setOnClickListener {
                    onFeedback()
                    onTextEdit(action)
                }
            } else {
                tag = "textedit-spacer"
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                contentDescription = null
            }
            if (center) text = "光标"
        }

        listOf(
            listOf(cell(), cell("▲", "up"), cell()),
            listOf(cell("◀", "left"), cell(center = true), cell("▶", "right")),
            listOf(cell(), cell("▼", "down"), cell()),
        ).forEach { rowItems ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            rowItems.forEach { item ->
                row.addView(
                    item,
                    LinearLayout.LayoutParams(0, toPx(ImeGeometryTokens.TOUCH_TARGET_DP), 1f).apply {
                        marginEnd = toPx(5)
                    },
                )
            }
            cross.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    toPx(ImeGeometryTokens.TOUCH_TARGET_DP),
                ).apply { bottomMargin = toPx(5) },
            )
        }
        body.addView(
            cross,
            LinearLayout.LayoutParams(toPx(158), toPx(150)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

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
        control.view.alpha = if (unavailable) 0.38f else 1f
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
