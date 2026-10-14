package llc.slacker.openime.keyboard

/** Reserve wider cells for phrases while keeping short candidates compact. */
internal fun candidateColumnSpan(text: String): Int = when {
    text.codePointCount(0, text.length) > 8 -> 4
    text.codePointCount(0, text.length) > 4 -> 2
    else -> 1
}

/**
 * The expanded page's 全部 / 单字 chips.
 *
 * The reference ships the two as one option group with 全部 selected
 * (`input_more_cand.xml:9-13`), so the page opens unfiltered.
 */
internal enum class ExpandedCandidateFilter(val label: String) {
    ALL("全部"),
    SINGLE_CHAR("单字"),
    ;

    fun apply(candidates: List<String>): List<String> = when (this) {
        ALL -> candidates
        SINGLE_CHAR -> candidates.filter { it.codePointCount(0, it.length) == 1 }
    }
}

internal fun expandedCandidateRows(candidates: List<String>): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var used = 0
    for (candidate in candidates) {
        val span = candidateColumnSpan(candidate)
        if (used + span > 4) {
            rows.add(row)
            row = mutableListOf()
            used = 0
        }
        row.add(candidate)
        used += span
    }
    if (row.isNotEmpty()) rows.add(row)
    return rows
}
