package llc.slacker.openime

/**
 * Prefix lookup over a map without scanning the whole source on every query.
 *
 * Keys are sorted once so [lowerBound] can jump directly to the matching
 * interval. Matching values are returned in the source map's original
 * iteration order, preserving the legacy CandidateEngine ranking contract.
 */
internal class PrefixRangeIndex<V>(source: Map<String, V>) {
    private data class Entry<V>(
        val key: String,
        val value: V,
        val sourceOrder: Int,
    )

    private val entries: List<Entry<V>> = source.entries
        .mapIndexed { index, entry -> Entry(entry.key, entry.value, index) }
        .sortedBy { it.key }

    data class Lookup<V>(
        val values: List<V>,
        val inspectedKeys: Int,
    )

    fun lookup(prefix: String): Lookup<V> {
        if (prefix.isEmpty() || entries.isEmpty()) return Lookup(emptyList(), 0)
        var index = lowerBound(prefix)
        var inspected = 0
        val matches = ArrayList<Entry<V>>()
        while (index < entries.size) {
            val entry = entries[index]
            inspected++
            if (!entry.key.startsWith(prefix)) break
            matches += entry
            index++
        }
        matches.sortBy { it.sourceOrder }
        return Lookup(matches.map { it.value }, inspected)
    }

    private fun lowerBound(target: String): Int {
        var low = 0
        var high = entries.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (entries[middle].key < target) low = middle + 1 else high = middle
        }
        return low
    }
}
