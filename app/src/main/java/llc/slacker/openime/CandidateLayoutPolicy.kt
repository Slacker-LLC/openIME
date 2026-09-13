package llc.slacker.openime

/** Reserve wider cells for phrases while keeping short candidates compact. */
internal fun candidateColumnSpan(text: String): Int = when {
    text.codePointCount(0, text.length) > 8 -> 4
    text.codePointCount(0, text.length) > 4 -> 2
    else -> 1
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
