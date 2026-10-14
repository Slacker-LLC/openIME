package llc.slacker.openime.engine

import android.content.Context
import llc.slacker.openime.core.FuzzyRule
import kotlin.math.ln

/**
 * Clean-room pinyin engine for openIME, built from the reverse-engineered
 * behaviour of a production IME (see ENGINE-SPEC): the input becomes a
 * full-path syllable DAG, every path queries the word index, a trailing
 * fragment expands to the syllables it may become, and sentences come from a
 * Viterbi pass over word edges with a bigram bonus.
 *
 * Fuzzy pairs (z/zh, n/l, an/ang...) expand the lookup when the caller passes
 * the enabled [FuzzyRule]s: exact readings always rank above fuzzy ones.
 *
 * The tables it reads are compiled locally by scripts/build_imengine_data.py
 * and are not part of the repository; without them [obtain] returns null and
 * the caller keeps using whatever engine it had.
 */
class PinyinEngine internal constructor(
    private val lexicon: LexiconIndex,
    private val bigram: BigramIndex?,
) {
    private val plainSegmenter = PinyinSegmenter(lexicon.syllables)
    private val fuzzySegmenters = HashMap<Set<FuzzyRule>, PinyinSegmenter>()

    val wordCount: Int get() = lexicon.size

    /** Candidate words for [input] (raw pinyin letters), best first. */
    fun candidates(
        input: String,
        limit: Int = CANDIDATE_LIMIT,
        fuzzyRules: Set<FuzzyRule> = emptySet(),
    ): List<String> {
        val letters = normalize(input)
        if (letters.isEmpty() || letters.length > MAX_INPUT) return emptyList()
        val segmenter = segmenterFor(fuzzyRules)
        val found = HashMap<String, Int>()
        val budget = Budget()

        for (split in 0..letters.length) {
            if (budget.exhausted()) break
            val tail = letters.substring(split)
            if (tail.length > PinyinSegmenter.MAX_SYLLABLE_LENGTH) continue
            val headPaths = if (split == 0) {
                listOf(PinyinSegmenter.SegmentPath(IntArray(0), intArrayOf(0), fuzzy = false))
            } else {
                segmenter.segment(letters.substring(0, split), maxPaths = HEAD_PATHS)
            }
            if (headPaths.isEmpty()) continue
            for (path in headPaths) {
                if (budget.exhausted()) break
                if (tail.isEmpty()) {
                    collectExact(found, path.ids, path.fuzzy, budget)
                    collectPrefix(found, path.ids, path.fuzzy, budget, COMPLETION_WORDS)
                } else {
                    // Words that already match the settled prefix stay candidates
                    // while the next syllable is still being typed. An empty
                    // prefix would match from the top of the table: skip it.
                    if (path.ids.isNotEmpty()) {
                        collectPrefix(found, path.ids, path.fuzzy, budget, FRAGMENT_WORDS)
                    }
                    for (edge in segmenter.syllablesStartingWith(tail, TAIL_SYLLABLES)) {
                        if (budget.exhausted()) break
                        val ids = path.ids.copyOf(path.ids.size + 1)
                        ids[ids.size - 1] = edge.id
                        collectPrefix(found, ids, path.fuzzy || edge.fuzzy, budget, FRAGMENT_WORDS)
                        budget.lookups++
                    }
                }
            }
        }
        if (found.isEmpty()) return emptyList()
        return found.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { it.key }
    }

    /** Top sentence readings for [input]; each result is the joined words. */
    fun decode(
        input: String,
        limit: Int = SENTENCE_LIMIT,
        fuzzyRules: Set<FuzzyRule> = emptySet(),
    ): List<String> {
        val letters = normalize(input)
        if (letters.isEmpty() || letters.length > MAX_INPUT) return emptyList()
        val paths = segmenterFor(fuzzyRules).segment(letters, maxPaths = PATH_LIMIT)
        if (paths.isEmpty()) return emptyList()
        val length = letters.length

        val edges = Array(length + 1) { ArrayList<Edge>(8) }
        for (path in paths) {
            val syllables = path.ids.size
            for (from in 0 until syllables) {
                val charFrom = path.starts[from]
                var to = from
                while (to < syllables && to - from < MAX_WORD_SYLLABLES) {
                    val charTo = path.starts[to + 1]
                    val span = path.ids.copyOfRange(from, to + 1)
                    val start = lexicon.lowerBound(span, span.size)
                    val end = lexicon.exactEnd(span, span.size)
                    var taken = 0
                    for (word in start until end) {
                        if (taken >= WORDS_PER_SPAN) break
                        taken++
                        edges[charFrom].add(Edge(charTo, word, path.fuzzy))
                    }
                    to++
                }
            }
        }

        val states = arrayOfNulls<ArrayList<State>>(length + 1)
        states[0] = arrayListOf(State(0.0, -1, 0, -1))
        for (position in 0..length) {
            val at = states[position] ?: continue
            for (edge in edges[position]) {
                if (edge.to <= position) continue
                val target = states[edge.to] ?: ArrayList<State>(STATE_K).also { states[edge.to] = it }
                for ((index, state) in at.withIndex()) {
                    val penalty = state.score +
                        unigramPenalty(edge.word) +
                        bigramPenalty(state.word, edge.word) +
                        if (edge.fuzzy) FUZZY_SENTENCE_COST else 0.0
                    target.add(State(penalty, edge.word, position, index))
                }
                if (target.size > STATE_K * 4) {
                    val trimmed = target.sortedBy { it.score }.take(STATE_K * 2)
                    target.clear()
                    target.addAll(trimmed)
                }
            }
        }

        val ends = states[length] ?: return emptyList()
        return ends.sortedBy { it.score }
            .take(limit)
            .mapNotNull { reading(it, states) }
            .distinct()
    }

    private fun segmenterFor(rules: Set<FuzzyRule>): PinyinSegmenter {
        if (rules.isEmpty()) return plainSegmenter
        synchronized(fuzzySegmenters) {
            return fuzzySegmenters.getOrPut(rules) { PinyinSegmenter(lexicon.syllables, rules) }
        }
    }

    private fun collectExact(found: HashMap<String, Int>, ids: IntArray, fuzzy: Boolean, budget: Budget) {
        val start = lexicon.lowerBound(ids, ids.size)
        val end = lexicon.exactEnd(ids, ids.size)
        var taken = 0
        for (word in start until end) {
            if (taken >= EXACT_WORDS) break
            taken++
            add(found, word, fuzzy, budget)
        }
    }

    private fun collectPrefix(
        found: HashMap<String, Int>,
        ids: IntArray,
        fuzzy: Boolean,
        budget: Budget,
        cap: Int,
    ) {
        val start = lexicon.lowerBound(ids, ids.size)
        val end = lexicon.prefixEnd(ids, ids.size)
        var taken = 0
        for (word in start until end) {
            if (taken >= cap || budget.exhausted()) break
            taken++
            add(found, word, fuzzy, budget)
        }
    }

    private fun add(found: HashMap<String, Int>, word: Int, fuzzy: Boolean, budget: Budget) {
        val text = lexicon.textOf(word)
        if (text.isEmpty()) return
        // A fuzzy reading carries a moderate penalty: a frequent fuzzy word can
        // beat a rare exact one, but marginal fuzzy junk never displaces a
        // solid literal match of similar frequency.
        val adjusted = lexicon.scoreOf(word) - if (fuzzy) FUZZY_SCORE_PENALTY else 0
        val current = found[text]
        if (current == null || adjusted > current) found[text] = adjusted
        budget.words++
    }

    private fun unigramPenalty(word: Int): Double =
        UNIGRAM_BASE - ln(1.0 + lexicon.scoreOf(word))

    private fun bigramPenalty(previous: Int, word: Int): Double {
        if (previous < 0) return 0.0
        val table = bigram ?: return BIGRAM_UNKNOWN_PENALTY
        val score = table.score(previous, word)
        if (score <= 0) return BIGRAM_UNKNOWN_PENALTY
        return BIGRAM_MAX_PENALTY * (1.0 - score / 65535.0)
    }

    private fun reading(final: State, states: Array<ArrayList<State>?>): String? {
        val words = ArrayList<String>(8)
        var current: State? = final
        var guard = 0
        while (current != null && current.word >= 0 && guard++ < MAX_WORD_SYLLABLES * 4) {
            words.add(lexicon.textOf(current.word))
            val list = states[current.from] ?: return null
            current = list.getOrNull(current.previousIndex)
        }
        words.reverse()
        return if (words.isEmpty()) null else words.joinToString("")
    }

    private fun normalize(input: String): String {
        val out = StringBuilder(input.length)
        for (char in input.lowercase()) {
            if (char in 'a'..'z') out.append(char)
        }
        return out.toString()
    }

    private class Budget {
        var lookups = 0
        var words = 0
        fun exhausted(): Boolean = words >= TOTAL_WORDS || lookups >= TOTAL_LOOKUPS
    }

    private class Edge(val to: Int, val word: Int, val fuzzy: Boolean)

    private class State(
        val score: Double,
        val word: Int,
        /** Character position this word starts at; its predecessor lives there. */
        val from: Int,
        val previousIndex: Int,
    )

    companion object {
        private const val CANDIDATE_LIMIT = 60
        private const val SENTENCE_LIMIT = 3
        private const val MAX_INPUT = 64
        private const val HEAD_PATHS = 4
        private const val TAIL_SYLLABLES = 32
        private const val PATH_LIMIT = 32
        private const val MAX_WORD_SYLLABLES = 6
        private const val EXACT_WORDS = 48
        private const val COMPLETION_WORDS = 64
        private const val FRAGMENT_WORDS = 64
        private const val WORDS_PER_SPAN = 24
        private const val TOTAL_WORDS = 1500
        private const val TOTAL_LOOKUPS = 600
        private const val STATE_K = 3
        /** Fuzzy candidates keep their frequency but pay this much score
         *  (below the 6571-vs-4655 gap of 师傅/私服, so a clearly more common
         *  fuzzy word still wins against a mediocre literal one). */
        private const val FUZZY_SCORE_PENALTY = 1500
        /** Cost constants: a word costs UNIGRAM_BASE - ln(1 + score); a bad
         *  pair adds up to BIGRAM_MAX_PENALTY, an unknown pair a flat cost. */
        private const val UNIGRAM_BASE = 12.0
        private const val BIGRAM_UNKNOWN_PENALTY = 1.5
        private const val BIGRAM_MAX_PENALTY = 4.0
        /** A sentence that needs a fuzzy reading pays this per fuzzy word. */
        private const val FUZZY_SENTENCE_COST = 2.0

        @Volatile
        private var cached: PinyinEngine? = null

        @Volatile
        private var attempted = false

        /** Load off the main thread; a missing table stays silent. */
        fun preload(context: Context) {
            obtain(context)
        }

        fun obtain(context: Context): PinyinEngine? {
            cached?.let { return it }
            if (attempted) return cached
            return synchronized(this) {
                if (!attempted) {
                    attempted = true
                    cached = runCatching {
                        val lexFile = EngineAssets.lexFile(context) ?: return@runCatching null
                        val bigramFile = EngineAssets.bigramFile(context)
                        PinyinEngine(
                            LexiconIndex(EngineAssets.map(lexFile)),
                            bigramFile?.let { BigramIndex(EngineAssets.map(it)) },
                        )
                    }.getOrNull()
                }
                cached
            }
        }
    }
}
