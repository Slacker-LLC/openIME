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

    /**
     * A reading only pays off if some word starts with it, so a split that no
     * word continues is dropped instead of taking a slot in the lattice: the
     * reference enumerates every path of the lattice (ENGINE-SPEC §1.3) and
     * can afford to, but our capped frontier cannot — bogus splits that spell
     * nothing (`a+de+lia+nan+...` for 阿德里安安德烈夫) would otherwise fill it.
     */
    private val wordPrefix: (IntArray) -> Boolean = { ids -> lexicon.hasPrefix(ids, ids.size) }

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
        val found = HashMap<String, Candidate>()
        val budget = Budget()

        // The reading in full first: those words are what the typing spells,
        // and the fragment passes below are a fallback — running them first
        // let them spend the budget, and the word the user actually typed
        // never made it into the list.
        val spellings = segmenter.segment(letters, maxPaths = HEAD_PATHS, alive = wordPrefix)
        for (path in spellings) {
            collectExact(found, path.ids, path.fuzzy, budget, COMPLETE)
            // A word that only starts with the reading is a completion, not a
            // match: 朋克头 spells pengke and three more letters the user has
            // not typed, so it follows 朋克/澎客/彭可.
            collectPrefix(found, path.ids, path.fuzzy, budget, COMPLETION_WORDS, FRAGMENT)
        }

        // Then the readings with an unfinished tail, longest head first: the
        // closer the head is to the whole reading, the fewer letters it leaves
        // for the tail to guess.
        for (split in letters.length - 1 downTo 0) {
            if (budget.exhausted()) break
            val tail = letters.substring(split)
            if (tail.length > PinyinSegmenter.MAX_SYLLABLE_LENGTH) continue
            val headPaths = if (split == 0) {
                listOf(PinyinSegmenter.SegmentPath(IntArray(0), intArrayOf(0), fuzzy = false))
            } else {
                segmenter.segment(
                    letters.substring(0, split),
                    maxPaths = HEAD_PATHS,
                    alive = wordPrefix,
                )
            }
            if (headPaths.isEmpty()) continue
            for (path in headPaths) {
                if (budget.exhausted()) break
                // Words that already match the settled prefix stay candidates
                // while the next syllable is still being typed. An empty
                // prefix would match from the top of the table: skip it.
                if (path.ids.isNotEmpty()) {
                    collectPrefix(found, path.ids, path.fuzzy, budget, FRAGMENT_WORDS, FRAGMENT)
                }
                for (edge in segmenter.syllablesStartingWith(tail, TAIL_SYLLABLES)) {
                    if (budget.exhausted()) break
                    val ids = path.ids.copyOf(path.ids.size + 1)
                    ids[ids.size - 1] = edge.id
                    collectPrefix(found, ids, path.fuzzy || edge.fuzzy, budget, FRAGMENT_WORDS, FRAGMENT)
                    budget.lookups++
                }
            }
        }
        // A reading whose every letter is a syllable but which no single word
        // spells is still a reading: the reference composes it from the words
        // it holds (`jintianxingqiwu` → 今天 + 星期五) and its bar leads with
        // that, so a user who typed a whole sentence sees the sentence and not
        // a word that merely starts the same way. The words must come up empty
        // (the reading spells none) and the tail must be typed in full — a
        // reading that still ends mid-syllable has its own pass above.
        val sentences = if (found.values.none { it.coverage == COMPLETE } && isTypedInFull(letters)) {
            decode(letters, limit = SENTENCE_LIMIT, fuzzyRules = fuzzyRules)
        } else {
            emptyList()
        }

        val ranked = found.entries
            .sortedWith(
                compareBy<Map.Entry<String, Candidate>> { it.value.coverage }
                    .thenByDescending { it.value.score }
                    .thenBy { it.key },
            )
            .map { it.key }
        if (ranked.isEmpty() && sentences.isEmpty()) return emptyList()
        return (sentences + ranked).distinct().take(limit)
    }

    /**
     * Whether [letters] reads as whole syllables, i.e. whether some reading of
     * it uses no single letter as a syllable of its own.
     *
     * A tail that is still half a syllable leaves only readings that end in a
     * single letter (every letter is a syllable of the catalogue), so this is
     * the line between `jintianxingqiwu` — five syllables, no word spells them,
     * a sentence does — and `jintianxingqiw`, which the fragment pass above
     * already answers for and which no sentence can cover.
     */
    private fun isTypedInFull(letters: String): Boolean =
        plainSegmenter.segment(letters, maxPaths = HEAD_PATHS).any { path ->
            (0 until path.ids.size).all { index ->
                path.starts[index + 1] - path.starts[index] >= SPELLED_SYLLABLE
            }
        }

    /** Top sentence readings for [input]; each result is the joined words. */
    fun decode(
        input: String,
        limit: Int = SENTENCE_LIMIT,
        fuzzyRules: Set<FuzzyRule> = emptySet(),
    ): List<String> {
        val letters = normalize(input)
        if (letters.isEmpty() || letters.length > MAX_INPUT) return emptyList()
        // No word-prefix pruning here: a sentence's own spans are words, but
        // the reading up to a span boundary can sit inside one of them
        // (我很好谢谢 = 我+很好+谢谢, and no word starts with 我很好些), so the
        // test the candidate pass uses would drop the sentences themselves.
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

    private fun collectExact(
        found: HashMap<String, Candidate>,
        ids: IntArray,
        fuzzy: Boolean,
        budget: Budget,
        coverage: Int,
    ) {
        val start = lexicon.lowerBound(ids, ids.size)
        val end = lexicon.exactEnd(ids, ids.size)
        var taken = 0
        for (word in start until end) {
            if (taken >= EXACT_WORDS) break
            taken++
            add(found, word, fuzzy, budget, coverage)
        }
    }

    private fun collectPrefix(
        found: HashMap<String, Candidate>,
        ids: IntArray,
        fuzzy: Boolean,
        budget: Budget,
        cap: Int,
        coverage: Int,
    ) {
        val start = lexicon.lowerBound(ids, ids.size)
        val end = lexicon.prefixEnd(ids, ids.size)
        var taken = 0
        for (word in start until end) {
            if (taken >= cap || budget.exhausted()) break
            taken++
            add(found, word, fuzzy, budget, coverage)
        }
    }

    private fun add(
        found: HashMap<String, Candidate>,
        word: Int,
        fuzzy: Boolean,
        budget: Budget,
        coverage: Int,
    ) {
        val text = lexicon.textOf(word)
        if (text.isEmpty()) return
        // A fuzzy reading carries a moderate penalty: a frequent fuzzy word can
        // beat a rare exact one, but marginal fuzzy junk never displaces a
        // solid literal match of similar frequency.
        val adjusted = lexicon.scoreOf(word) - if (fuzzy) FUZZY_SCORE_PENALTY else 0
        val current = found[text]
        if (current == null) {
            found[text] = Candidate(coverage, adjusted)
        } else if (coverage < current.coverage || (coverage == current.coverage && adjusted > current.score)) {
            current.coverage = coverage
            current.score = adjusted
        }
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

    /**
     * One candidate: how much of the typed input the reading explains
     * ([coverage], COMPLETE beats FRAGMENT) and the word's frequency score.
     */
    private class Candidate(var coverage: Int, var score: Int)

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
        private const val HEAD_PATHS = 64
        private const val TAIL_SYLLABLES = 32
        private const val PATH_LIMIT = 32
        private const val MAX_WORD_SYLLABLES = 6
        private const val EXACT_WORDS = 48
        /** Coverage ranks first: a word whose reading spells out every typed
         *  letter (dang -> 当) always beats a more frequent word that only
         *  matches a prefix of it (dang -> 打, from da + a tail that never
         *  becomes a syllable). */
        private const val COMPLETE = 0
        private const val FRAGMENT = 1
        private const val COMPLETION_WORDS = 64
        private const val FRAGMENT_WORDS = 16
        private const val WORDS_PER_SPAN = 24
        private const val TOTAL_WORDS = 1500
        private const val TOTAL_LOOKUPS = 600
        private const val STATE_K = 3
        /** Shortest syllable that still says the tail was typed in full. */
        private const val SPELLED_SYLLABLE = 2
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
