package llc.slacker.openime.engine

import llc.slacker.openime.core.FuzzyRule

/**
 * Splits raw pinyin letters into syllable paths.
 *
 * The engine enumerates *all* paths (the reverse-engineered lattice is a
 * full-path DAG, not longest match): every position emits every matching
 * syllable edge, up to the path and syllable caps. A trailing fragment that
 * is not yet a full syllable is handled by the caller through
 * [syllablesStartingWith].
 *
 * With [fuzzyRules] the lookup also accepts the alternate spelling of each
 * syllable pair (z/zh, n/l, an/ang...): the typed spelling may stand for a
 * syllable it is not literally; such edges carry `fuzzy = true` so callers
 * can rank exact readings first.
 *
 * [segment] takes an optional [alive] test: a path the caller cannot use is
 * dropped before it takes a slot in the next node. The caller's word lookup
 * only ever asks for readings that a word starts with, so a path that no word
 * starts with can never contribute and must not crowd out one that can.
 */
internal class PinyinSegmenter(
    syllables: List<String>,
    fuzzyRules: Set<FuzzyRule> = emptySet(),
) {
    /** One segmentation: syllable ids, their character boundaries and whether any edge was fuzzy. */
    internal class SegmentPath(val ids: IntArray, val starts: IntArray, val fuzzy: Boolean)

    internal class SyllableEdge(val id: Int, val fuzzy: Boolean)

    private val edgeIds: HashMap<String, IntArray>
    private val edgeFuzzy: HashMap<String, BooleanArray>
    private val scanEdges: List<Triple<String, Int, Boolean>>

    init {
        val nameToId = HashMap<String, Int>(syllables.size * 2)
        syllables.forEachIndexed { index, name -> nameToId.putIfAbsent(name, index) }

        val ids = HashMap<String, ArrayList<Int>>(syllables.size * 2)
        val fuzzy = HashMap<String, ArrayList<Boolean>>(syllables.size * 2)
        fun add(spelling: String, id: Int, isFuzzy: Boolean) {
            ids.getOrPut(spelling) { ArrayList(2) }.add(id)
            fuzzy.getOrPut(spelling) { ArrayList(2) }.add(isFuzzy)
        }
        syllables.forEachIndexed { id, name -> add(name, id, isFuzzy = false) }
        if (fuzzyRules.isNotEmpty()) {
            syllables.forEachIndexed { id, name ->
                for (rule in fuzzyRules) {
                    val variant = rule.variant(name) ?: continue
                    if (variant in nameToId) add(variant, id, isFuzzy = true)
                }
            }
        }
        edgeIds = HashMap(ids.size * 2)
        edgeFuzzy = HashMap(fuzzy.size * 2)
        for ((spelling, list) in ids) {
            val flags = fuzzy.getValue(spelling)
            // The literal reading comes first and wins duplicates; a spelling
            // that is both a syllable and another syllable's variant keeps both.
            val unique = LinkedHashMap<Int, Boolean>(list.size)
            list.forEachIndexed { index, id -> unique.putIfAbsent(id, flags[index]) }
            val idArray = IntArray(unique.size)
            val flagArray = BooleanArray(unique.size)
            unique.entries.forEachIndexed { index, entry ->
                idArray[index] = entry.key
                flagArray[index] = entry.value
            }
            edgeIds[spelling] = idArray
            edgeFuzzy[spelling] = flagArray
        }

        val scan = ArrayList<Triple<String, Int, Boolean>>(syllables.size * 2)
        syllables.forEachIndexed { id, name -> scan += Triple(name, id, false) }
        if (fuzzyRules.isNotEmpty()) {
            syllables.forEachIndexed { id, name ->
                for (rule in fuzzyRules) {
                    val variant = rule.variant(name) ?: continue
                    if (variant in nameToId) scan += Triple(variant, id, true)
                }
            }
        }
        scanEdges = scan
    }

    fun segment(
        letters: String,
        maxPaths: Int = 32,
        maxSyllables: Int = MAX_SYLLABLES,
        alive: ((IntArray) -> Boolean)? = null,
    ): List<SegmentPath> {
        val length = letters.length
        if (length == 0) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val frontier = arrayOfNulls<ArrayList<SegmentPath>>(length + 1)
        for (index in 0..length) frontier[index] = ArrayList(4)
        frontier[0]!!.add(SegmentPath(IntArray(0), intArrayOf(0), fuzzy = false))

        for (position in 0 until length) {
            val at = frontier[position] ?: continue
            if (at.isEmpty()) continue
            val maxEdge = minOf(MAX_SYLLABLE_LENGTH, length - position)
            for (path in at) {
                if (path.ids.size >= maxSyllables) continue
                for (edge in 1..maxEdge) {
                    val spelling = letters.substring(position, position + edge)
                    val targets = edgeIds[spelling] ?: continue
                    val flags = edgeFuzzy.getValue(spelling)
                    val next = frontier[position + edge] ?: continue
                    for (index in targets.indices) {
                        val ids = path.ids.copyOf(path.ids.size + 1)
                        ids[ids.size - 1] = targets[index]
                        // A path that ends the input is a reading of it even
                        // when no word starts with it (the sentence pass takes
                        // those); only a path that still has letters to go is
                        // worth keeping solely for the word it may reach.
                        if (alive != null && position + edge < length && !alive(ids)) continue
                        val starts = path.starts.copyOf(path.starts.size + 1)
                        starts[starts.size - 1] = position + edge
                        addPath(next, SegmentPath(ids, starts, path.fuzzy || flags[index]), maxPaths)
                    }
                }
            }
        }
        return frontier[length] ?: emptyList()
    }

    /**
     * Keep the best [maxPaths] paths of one lattice node.
     *
     * The reference enumerates every path of the lattice and only ranks them
     * afterwards (a 0.99 decay per syllable edge), so a path that arrives once
     * the cap is full must evict a worse one rather than be dropped: fewer
     * syllables is always the better reading (`luanan` is luan+an, not lu+a+nan).
     */
    private fun addPath(into: ArrayList<SegmentPath>, path: SegmentPath, maxPaths: Int) {
        var at = 0
        while (at < into.size && into[at].ids.size <= path.ids.size) at++
        if (at >= maxPaths) return
        if (into.size == maxPaths) into.removeAt(into.size - 1)
        into.add(at, path)
    }

    /** One syllable edge of the lattice: it consumes letters up to [end]. */
    internal class LatticeEdge(val end: Int, val id: Int, val fuzzy: Boolean)

    /**
     * The syllable lattice of [letters]: for every position, every syllable of
     * the catalogue that matches there.
     *
     * The reference builds its lattice exactly this way — `BuildLattice` walks
     * its syllable trie from each reachable position and adds one edge per
     * match (`ENGINE-SPEC.md` §1.1, §1.3) — and reads words off the edges. A
     * caller that reads words off whole paths instead looks the same window up
     * once per path that crosses it.
     */
    fun lattice(letters: String): List<List<LatticeEdge>> {
        val length = letters.length
        return (0 until length).map { position ->
            val out = ArrayList<LatticeEdge>(4)
            val maxEdge = minOf(MAX_SYLLABLE_LENGTH, length - position)
            for (edge in 1..maxEdge) {
                val spelling = letters.substring(position, position + edge)
                val targets = edgeIds[spelling] ?: continue
                val flags = edgeFuzzy.getValue(spelling)
                for (index in targets.indices) {
                    out.add(LatticeEdge(position + edge, targets[index], flags[index]))
                }
            }
            out
        }
    }

    /** Syllable edges whose spelling starts with the trailing fragment [fragment]. */
    fun syllablesStartingWith(fragment: String, max: Int = 32): List<SyllableEdge> {
        if (fragment.isEmpty()) return emptyList()
        val out = ArrayList<SyllableEdge>(max)
        val seen = HashSet<Int>(max * 2)
        for ((spelling, id, isFuzzy) in scanEdges) {
            if (spelling.length >= fragment.length && spelling.startsWith(fragment)) {
                if (isFuzzy && !seen.add(id)) continue
                if (!isFuzzy) seen.add(id)
                out.add(SyllableEdge(id, isFuzzy))
                if (out.size >= max) break
            }
        }
        return out
    }

    companion object {
        const val MAX_SYLLABLE_LENGTH = 6
        const val MAX_SYLLABLES = 10
    }
}
