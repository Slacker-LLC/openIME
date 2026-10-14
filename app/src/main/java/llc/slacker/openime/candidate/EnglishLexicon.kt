package llc.slacker.openime.candidate

import android.content.Context

/**
 * Word list for English completions, loaded from english-words.tsv
 * (lower / word / rank, sorted by lower, rank 1 = the most common word).
 * The file ships only in local builds; without it [obtain] returns null and
 * completions fall back to the small hand-written map in `core.ImeData`.
 *
 * Completion is a binary search on the sorted keys plus a bounded scan; the
 * matches are then ranked by the frequency rank. This keeps a keystroke
 * cheap even with tens of thousands of words.
 */
internal class EnglishLexicon private constructor(
    private val lower: Array<String>,
    private val words: Array<String>,
    private val ranks: IntArray,
) {
    fun complete(rawPrefix: String, limit: Int = DEFAULT_LIMIT): List<String> {
        if (limit <= 0) return emptyList()
        val prefix = rawPrefix.lowercase()
        if (prefix.isEmpty()) return emptyList()
        var index = lowerBound(prefix)
        val picked = ArrayList<WordRank>(minOf(limit * 8, MAX_SCAN))
        var scanned = 0
        while (index < lower.size && scanned < MAX_SCAN) {
            if (!lower[index].startsWith(prefix)) break
            picked += WordRank(ranks[index], words[index])
            index++
            scanned++
        }
        if (picked.isEmpty()) return emptyList()
        picked.sortWith(compareBy<WordRank> { it.rank }.thenBy { it.word })
        val result = LinkedHashSet<String>(limit)
        for (candidate in picked) {
            result.add(candidate.word)
            if (result.size >= limit) break
        }
        return result.toList()
    }

    private fun lowerBound(prefix: String): Int {
        var low = 0
        var high = lower.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lower[middle] < prefix) low = middle + 1 else high = middle
        }
        return low
    }

    private data class WordRank(val rank: Int, val word: String)

    companion object {
        const val ASSET = "english-words.tsv"
        const val DEFAULT_LIMIT = 16
        private const val MAX_SCAN = 4000

        @Volatile
        private var cached: EnglishLexicon? = null

        @Volatile
        private var attempted = false

        /** Load off the main thread at service start; a missing file stays silent. */
        fun preload(context: Context) {
            obtain(context)
        }

        fun obtain(context: Context): EnglishLexicon? {
            cached?.let { return it }
            if (attempted) return cached
            return synchronized(this) {
                if (!attempted) {
                    attempted = true
                    cached = runCatching { parse(context) }.getOrNull()
                }
                cached
            }
        }

        /** Visible for tests: build a table from (word, rank) pairs. */
        internal fun fromEntries(entries: List<Pair<String, Int>>): EnglishLexicon {
            val rows = entries
                .map { (word, rank) -> Row(word.lowercase(), word, rank) }
                .sortedWith(compareBy<Row> { it.lower }.thenBy { it.rank }.thenBy { it.word })
            return EnglishLexicon(
                lower = Array(rows.size) { rows[it].lower },
                words = Array(rows.size) { rows[it].word },
                ranks = IntArray(rows.size) { rows[it].rank },
            )
        }

        private data class Row(val lower: String, val word: String, val rank: Int)

        private fun parse(context: Context): EnglishLexicon? {
            val lower = ArrayList<String>(60_000)
            val words = ArrayList<String>(60_000)
            val ranks = ArrayList<Int>(60_000)
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isEmpty() || line[0] == '#') return@forEach
                    val parts = line.split('\t')
                    if (parts.size < 3) return@forEach
                    val key = parts[0]
                    val word = parts[1]
                    val rank = parts[2].toIntOrNull() ?: return@forEach
                    if (key.isEmpty() || word.isEmpty() || rank <= 0) return@forEach
                    lower += key
                    words += word
                    ranks += rank
                }
            }
            if (lower.isEmpty()) return null
            return EnglishLexicon(lower.toTypedArray(), words.toTypedArray(), ranks.toIntArray())
        }
    }
}
