package llc.slacker.openime

/**
 * Controls that the legacy text-edit panel renders but cannot implement
 * reliably across arbitrary target editors. Production keeps them visible as
 * disabled affordances rather than exposing clickable no-ops.
 */
internal object TextEditControlPolicy {
    private val unavailableLabels = setOf("撤销", "▲", "▼")

    fun isUnavailableLabel(label: String): Boolean = label in unavailableLabels
}
