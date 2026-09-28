package llc.slacker.openime

/**
 * Controls that must remain unavailable for sensitive editors. Cursor movement
 * and undo are routed to the target editor, so they are no longer dead UI.
 */
internal object TextEditControlPolicy {
    // Sensitive editors must not expose clipboard actions through the IME
    // surface. This keeps the UI contract aligned with persistent-history
    // privacy and prevents controls from advertising actions the IME blocks.
    private val passwordUnavailableLabels = setOf("全选", "复制", "剪切", "粘贴")

    fun isUnavailableLabel(label: String, passwordField: Boolean = false): Boolean =
        passwordField && label in passwordUnavailableLabels

    fun unavailableReason(label: String, passwordField: Boolean = false): String =
        if (passwordField && label in passwordUnavailableLabels) "密码输入中不可用" else "当前编辑器暂不支持"
}
