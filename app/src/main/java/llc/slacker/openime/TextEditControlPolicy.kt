package llc.slacker.openime

/**
 * Controls that the text-edit panel cannot implement reliably across arbitrary
 * target editors. They remain visible as disabled affordances rather than
 * exposing clickable no-ops.
 */
internal object TextEditControlPolicy {
    private val unavailableLabels = setOf("撤销", "▲", "▼")

    fun isUnavailableLabel(label: String): Boolean = label in unavailableLabels
}
