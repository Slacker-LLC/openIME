package llc.slacker.openime

/**
 * Controls that the text-edit panel cannot implement reliably across arbitrary
 * target editors. They remain visible as disabled affordances rather than
 * exposing clickable no-ops.
 */
internal object TextEditControlPolicy {
    private val unavailableLabels = setOf("撤销", "▲", "▼")

    fun isUnavailableLabel(label: String, passwordField: Boolean = false): Boolean =
        label in unavailableLabels || (passwordField && label == "粘贴")

    fun unavailableReason(label: String, passwordField: Boolean = false): String =
        if (passwordField && label == "粘贴") "密码输入中不可用" else "当前编辑器暂不支持"
}
