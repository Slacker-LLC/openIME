package llc.slacker.openime

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Fast, frequency-aware local fallback for Chinese 9-key.
 *
 * Rime is authoritative once its async result arrives. This decoder exists so
 * the first frame is still useful while librime starts/queries, without scanning
 * every entry in the same first-digit bucket on every key press.
 */
internal class NineKeyLocalDecoder(
    private val engine: CandidateEngine,
) {
    data class Resolution(
        val previewSuffix: String,
        val pinyinSuffixes: List<String>,
        val candidates: List<String>,
    )

    private data class Entry(
        val pinyin: String,
        val digits: String,
        val candidates: List<String>,
        val phrase: Boolean,
        val weight: Int,
    )

    private data class Decode(
        val pinyin: String,
        val text: String,
        val score: Int,
        val parts: Int,
    )

    private class TrieNode {
        val children = HashMap<Char, TrieNode>()
        val exact = ArrayList<Entry>()
        var bestDescendant: Entry? = null
    }

    private val root = TrieNode()
    private var previousDigits = ""
    private var previousPreview = ""

    init {
        buildEntries().forEach(::insert)
        sortTrie(root)
    }

    @Synchronized
    fun resolve(
        digits: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): Resolution {
        val bounded = digits.filter { it in '2'..'9' }.take(MAX_DIGITS)
        if (bounded.isEmpty()) {
            previousDigits = ""
            previousPreview = ""
            return Resolution("", emptyList(), emptyList())
        }

        val node = nodeFor(bounded)
        val exactEntries = node?.exact.orEmpty().take(MAX_PATHS)
        val decoded = decodePaths(bounded)
        val preferred = preferredSuffix
            ?.lowercase()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val preferredDigits = preferred?.let(::digitsForPinyin)
        val stable = preferred?.takeIf { preferredDigits == bounded }

        val validPreset = NineKeyPresets.combinations[bounded]
            .orEmpty()
            .filter { digitsForPinyin(it) == bounded }

        val rankedPool = buildList {
            addAll(validPreset)
            addAll(exactEntries.map { it.pinyin })
            addAll(decoded.map { it.pinyin })
        }
            .filter { it.isNotBlank() }
            .distinct()

        val continuationBase = when {
            preferred != null &&
                preferredDigits != null &&
                bounded.length > preferredDigits.length &&
                bounded.startsWith(preferredDigits) -> preferred
            previousDigits.isNotEmpty() &&
                bounded.length > previousDigits.length &&
                bounded.startsWith(previousDigits) &&
                previousPreview.isNotEmpty() -> previousPreview
            else -> null
        }
        val continuous = continuationBase?.let { base ->
            rankedPool.firstOrNull { candidate ->
                candidate.startsWith(base) && digitsForPinyin(candidate) == bounded
            }
        }

        val paths = buildList {
            stable?.let(::add)
            continuous?.let(::add)
            addAll(rankedPool)
        }
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_PATHS)
            .toList()

        val batches = ArrayList<List<String>>()
        exactEntries.forEach { entry ->
            val clean = entry.candidates.filter(::isDisplayCandidate).take(PER_PATH_CANDIDATES)
            if (clean.isNotEmpty()) batches += clean
        }
        decoded.forEach { state ->
            if (state.text.isNotBlank()) batches += listOf(state.text)
        }
        paths.forEach { path ->
            val clean = engine.getCandidates(path, fuzzy)
                .asSequence()
                .filter(::isDisplayCandidate)
                .filter { it != path }
                .take(PER_PATH_CANDIDATES)
                .toList()
            if (clean.isNotEmpty()) batches += clean
        }

        // A prefix that is not yet a complete dictionary spelling must still
        // look like the path the user is building. For an explicit/continuous
        // path choice, keep that descendant ahead of an automatically decoded
        // segmented path. Future words remain preview-only until their full
        // digit spelling has actually been typed.
        val preferredDescendant = continuationBase?.let { base ->
            bestDescendantStartingWith(node, base)
        }
        val prefixPreview = (preferredDescendant ?: node?.bestDescendant)
            ?.pinyin
            ?.take(bounded.length)
            ?.takeIf { digitsForPinyin(it) == bounded }
        val preferredFallbackPreview = continuationBase?.let { base ->
            val baseDigits = digitsForPinyin(base) ?: return@let null
            if (!bounded.startsWith(baseDigits) || bounded.length <= baseDigits.length) {
                return@let null
            }
            base + fallbackLetters(bounded.removePrefix(baseDigits))
        }
        val preview = when {
            stable != null -> stable
            continuous != null -> continuous
            continuationBase != null && prefixPreview != null -> prefixPreview
            preferredFallbackPreview != null -> preferredFallbackPreview
            paths.isNotEmpty() -> paths.first()
            prefixPreview != null -> prefixPreview
            else -> fallbackLetters(bounded)
        }

        previousDigits = bounded
        previousPreview = preview
        return Resolution(
            previewSuffix = preview,
            pinyinSuffixes = paths,
            candidates = roundRobin(batches, MAX_CANDIDATES),
        )
    }

    private fun buildEntries(): List<Entry> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        ImeData.phraseDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        // Put the compact common-character table ahead of the generated Unihan
        // coverage table. The latter is not frequency ordered.
        ImeData.pinyinDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        PinyinLexicon.current().forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }

        return merged.mapNotNull { (pinyin, rawValues) ->
            val digits = digitsForPinyin(pinyin) ?: return@mapNotNull null
            val values = rawValues.distinct().take(MAX_CANDIDATES)
            if (values.isEmpty()) return@mapNotNull null
            val corpusWeight = values.maxOfOrNull { value ->
                PinyinLexicon.weightFor(pinyin, value)
            } ?: 0
            Entry(
                pinyin = pinyin,
                digits = digits,
                candidates = values,
                phrase = pinyin in ImeData.phraseDict || values.any { value ->
                    value.codePointCount(0, value.length) > 1
                },
                weight = corpusWeight,
            )
        }
    }

    private fun insert(entry: Entry) {
        var node = root
        entry.digits.forEach { digit ->
            node = node.children.getOrPut(digit) { TrieNode() }
        }
        node.exact += entry
    }

    /** Sort exact entries and cache the best scoring entry reachable below each prefix. */
    private fun sortTrie(node: TrieNode): Entry? {
        node.exact.sortWith(
            compareByDescending<Entry>(::entryScore)
                .thenBy { it.pinyin },
        )
        val childBest = node.children.values.mapNotNull(::sortTrie)
        node.bestDescendant = (node.exact.asSequence() + childBest.asSequence())
            .maxWithOrNull(
                compareBy<Entry>(::entryScore)
                    .thenByDescending { it.pinyin },
            )
        return node.bestDescendant
    }

    private fun bestDescendantStartingWith(node: TrieNode?, pinyinPrefix: String): Entry? {
        if (node == null) return null
        var best = node.exact
            .asSequence()
            .filter { it.pinyin.startsWith(pinyinPrefix) }
            .maxWithOrNull(compareBy<Entry>(::entryScore).thenByDescending { it.pinyin })
        node.children.values.forEach { child ->
            val candidate = bestDescendantStartingWith(child, pinyinPrefix) ?: return@forEach
            val current = best
            if (current == null || entryScore(candidate) > entryScore(current) ||
                (entryScore(candidate) == entryScore(current) && candidate.pinyin < current.pinyin)
            ) {
                best = candidate
            }
        }
        return best
    }

    private fun nodeFor(digits: String): TrieNode? {
        var node = root
        digits.forEach { digit ->
            node = node.children[digit] ?: return null
        }
        return node
    }

    /** Beam DP over the digit trie; boundaries are retained as spaces. */
    private fun decodePaths(digits: String): List<Decode> {
        val states = Array(digits.length + 1) { mutableListOf<Decode>() }
        states[0] += Decode(pinyin = "", text = "", score = 0, parts = 0)

        for (start in digits.indices) {
            val previous = states[start].take(MAX_BEAM)
            if (previous.isEmpty()) continue
            var node = root
            for (end in start until digits.length) {
                node = node.children[digits[end]] ?: break
                if (node.exact.isEmpty()) continue
                node.exact.take(MAX_BRANCHES).forEach { entry ->
                    previous.forEach { before ->
                        val candidate = Decode(
                            pinyin = if (before.pinyin.isEmpty()) {
                                entry.pinyin
                            } else {
                                before.pinyin + " " + entry.pinyin
                            },
                            text = before.text + entry.candidates.firstOrNull().orEmpty(),
                            score = before.score + entryScore(entry) - PART_PENALTY,
                            parts = before.parts + 1,
                        )
                        states[end + 1] += candidate
                    }
                }
                states[end + 1] = states[end + 1]
                    .distinctBy { it.pinyin to it.text }
                    .sortedWith(
                        compareByDescending<Decode> { it.score }
                            .thenBy { it.parts },
                    )
                    .take(MAX_BEAM)
                    .toMutableList()
            }
        }

        return states[digits.length]
            .filter { it.parts > 0 }
            .sortedWith(compareByDescending<Decode> { it.score }.thenBy { it.parts })
            .take(MAX_PATHS)
    }

    private fun entryScore(entry: Entry): Int {
        val corpus = if (entry.weight > 0) {
            (ln(entry.weight.toDouble() + 1.0) * 120.0).roundToInt()
        } else {
            0
        }
        val phraseBoost = if (entry.phrase) 420 else 0
        return corpus + phraseBoost + entry.pinyin.length * 4
    }

    private fun roundRobin(batches: List<List<String>>, limit: Int): List<String> {
        if (batches.isEmpty() || limit <= 0) return emptyList()
        // Batches are already ordered by path quality. Round-robin made the
        // first result of a weak path outrank every second result of the best
        // path, which produced surprising candidates for ambiguous digits.
        // Aggregate rank evidence instead, while retaining first-seen order as
        // a deterministic tie breaker.
        data class Ranked(val value: String, val score: Int, val order: Int)
        val scores = LinkedHashMap<String, Ranked>()
        var order = 0
        batches.forEachIndexed { batchIndex, batch ->
            batch.forEachIndexed { rank, value ->
                val score = (batches.size - batchIndex) * 100 - rank * 8
                val current = scores[value]
                if (current == null || score > current.score) {
                    scores[value] = Ranked(value, score, current?.order ?: order)
                }
                order++
            }
        }
        return scores.values
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.order })
            .take(limit)
            .map { it.value }
    }

    private fun isDisplayCandidate(value: String): Boolean =
        value.isNotBlank() && value.none(Char::isDigit) && value.any { it.code > 0x7f }

    private fun fallbackLetters(digits: String): String = buildString(digits.length) {
        digits.forEach { digit ->
            append(
                when (digit) {
                    '2' -> 'a'
                    '3' -> 'd'
                    '4' -> 'g'
                    '5' -> 'j'
                    '6' -> 'm'
                    '7' -> 'p'
                    '8' -> 't'
                    '9' -> 'w'
                    else -> return@forEach
                },
            )
        }
    }

    companion object {
        const val MAX_DIGITS = 64
        private const val MAX_PATHS = 12
        private const val MAX_BEAM = 12
        private const val MAX_BRANCHES = 10
        private const val MAX_CANDIDATES = 96
        private const val PER_PATH_CANDIDATES = 24
        private const val PART_PENALTY = 55

        fun digitsForPinyin(pinyin: String): String? {
            if (pinyin.isBlank()) return null
            val digits = StringBuilder(pinyin.length)
            pinyin.lowercase().forEach { ch ->
                val mapped = when (ch) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v', 'ü' -> '8'
                    in 'w'..'z' -> '9'
                    ' ', '\'', '|' -> return null
                    else -> return null
                }
                digits.append(mapped)
            }
            return digits.toString().ifEmpty { null }
        }

        /** Build the one authoritative Rime code for an explicitly segmented composition. */
        fun nativeCode(segmentPrefix: String, suffixDigits: String): String? {
            val out = StringBuilder(segmentPrefix.length + suffixDigits.length)
            segmentPrefix.lowercase().forEach { ch ->
                when {
                    ch in 'a'..'z' || ch == 'ü' -> {
                        val digit = digitsForPinyin(ch.toString()) ?: return null
                        out.append(digit)
                    }
                    ch == '|' || ch == '\'' || ch.isWhitespace() -> {
                        if (out.isNotEmpty() && out.last() != '\'') out.append('\'')
                    }
                    else -> return null
                }
            }
            suffixDigits.filter { it in '2'..'9' }.forEach(out::append)
            return out.toString().trim('\'').ifBlank { null }
        }
    }
}
