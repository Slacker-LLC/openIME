package llc.slacker.openime.candidate

import android.content.Context

/**
 * Classical lines that go with what is being typed ("联想推荐诗词").
 *
 * The reference recommends poetry lines from a bundled grammar dictionary —
 * its own settings text names them among what 联想 recommends
 * (`关闭联想后，将不会推荐诗词、计算结果、emoji表情。`) — and the lines are keyed
 * by the pinyin they are typed with. This index does the same: as soon as the
 * composition spells [MIN_SYLLABLES] syllables of a line, the line is offered,
 * and a line the user spelled out completely also offers the line that follows
 * it in its own poem (`春眠不觉晓` -> `处处闻啼鸟`).
 *
 * Three syllables is the measured floor: of the 3,000 most frequent pinyin
 * keys of the merged tables, 380 (12.7%) are a two-syllable prefix of some
 * line and only 3 (0.1%) are a three-syllable one, and a line is worth
 * recommending only when it is what the user is typing and not what they are
 * about to type anyway.
 */
class PoemIndex internal constructor(private val lines: List<PoemLine>) {

    /** One line of the table: its pinyin, where its syllables end, its text. */
    internal data class PoemLine(
        /** The line's pinyin, syllables joined: `chunmianbujuexiao`. */
        val letters: String,
        /** Letter count at the end of each syllable, ascending. */
        val ends: IntArray,
        val text: String,
        /** The next line of the same poem; empty when the table carries none. */
        val follow: String,
    )

    /** The lines the table carries. */
    val size: Int get() = lines.size

    /**
     * Up to [limit] lines for [composition], the line the user spelled out
     * first. Empty while the composition is shorter than three syllables, cuts
     * a syllable open, or matches nothing.
     */
    fun suggestionsFor(composition: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        if (lines.isEmpty() || limit <= 0) return emptyList()
        val typed = composition.lowercase().filter { it in 'a'..'z' }
        if (typed.isEmpty()) return emptyList()

        val complete = mutableListOf<PoemLine>()
        val prefixes = mutableListOf<PoemLine>()
        for (line in lines) {
            if (typed.length > line.letters.length) continue
            if (!line.letters.startsWith(typed)) continue
            if (syllablesSpelled(line.ends, typed.length) < MIN_SYLLABLES) continue
            if (typed.length == line.letters.length) complete += line else prefixes += line
        }

        val suggestions = LinkedHashSet<String>()
        complete.sortedBy { it.text }.forEach { line ->
            suggestions += line.text
            if (line.follow.isNotEmpty()) suggestions += line.follow
        }
        prefixes.sortedBy { it.text }.forEach { suggestions += it.text }
        return suggestions.take(limit)
    }

    /** Syllables [length] letters of a line spell, or 0 when they cut one open. */
    private fun syllablesSpelled(ends: IntArray, length: Int): Int {
        var spelled = 0
        for (end in ends) {
            if (end == length) return spelled + 1
            if (end > length) return 0
            spelled++
        }
        return 0
    }

    companion object {
        /** `春风` is every day's typing; three syllables is a line worth offering. */
        const val MIN_SYLLABLES = 3
        const val MAX_SUGGESTIONS = 2
        private const val ASSET = "poem/lines.tsv"

        val EMPTY = PoemIndex(emptyList())

        /**
         * Lines are `pinyin syllables<TAB>line<TAB>next line`; the third column
         * is empty when the table has no following line, and `#` starts a
         * comment.
         */
        fun parse(lines: Sequence<String>): PoemIndex {
            val parsed = ArrayList<PoemLine>()
            lines.forEach { raw ->
                val line = raw.trimEnd()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split('\t')
                if (parts.size < 2) return@forEach
                val syllables = parts[0].trim().split(' ').filter { it.isNotEmpty() }
                val text = parts[1].trim()
                val follow = parts.getOrNull(2)?.trim().orEmpty()
                if (syllables.isEmpty() || text.isEmpty()) return@forEach
                val ends = IntArray(syllables.size)
                var letters = 0
                syllables.forEachIndexed { index, syllable ->
                    letters += syllable.length
                    ends[index] = letters
                }
                parsed += PoemLine(syllables.joinToString(""), ends, text, follow)
            }
            return PoemIndex(parsed)
        }

        fun load(context: Context): PoemIndex =
            runCatching {
                parse(
                    context.assets.open(ASSET).bufferedReader(Charsets.UTF_8)
                        .use { it.readLines() }
                        .asSequence(),
                )
            }.getOrDefault(EMPTY)

        @Volatile
        private var cached: PoemIndex? = null

        /** The parsed table, built once; [preload] fills this off the first keystroke. */
        fun obtain(context: Context): PoemIndex =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        /** Read the table on a background thread so the first keystroke does not. */
        fun preload(context: Context) {
            obtain(context)
        }
    }
}
