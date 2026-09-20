package llc.slacker.openime

/**
 * Controls that the text-edit panel cannot implement reliably across arbitrary
 * target editors. They remain visible as disabled affordances rather than
 * exposing clickable no-ops.
 */
internal object TextEditControlPolicy {
    private val unavailableLabels = setOf("撤销", "▲", "▼")
    private val passwordUnavailableLabels = setOf("全选", "复制", "剪切", "粘贴")

    fun isUnavailableLabel(label: String, passwordField: Boolean = false): Boolean =
        label in unavailableLabels || (passwordField && label in passwordUnavailableLabels)

    fun unavailableReason(label: String, passwordField: Boolean = false): String =
        if (passwordField && label in passwordUnavailableLabels) "密码输入中不可用" else "当前编辑器暂不支持"
}
