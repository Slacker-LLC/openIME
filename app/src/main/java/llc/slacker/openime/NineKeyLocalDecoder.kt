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
    }

    private val root = TrieNode()

    init {
        buildEntries().forEach(::insert)
        sortTrie(root)
    }

    fun resolve(
        digits: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): Resolution {
        val bounded = digits.filter { it in '2'..'9' }.take(MAX_DIGITS)
        if (bounded.isEmpty()) return Resolution("", emptyList(), emptyList())

        val exactEntries = nodeFor(bounded)?.exact.orEmpty().take(MAX_PATHS)
        val decoded = decodePaths(bounded)
        val stable = preferredSuffix
            ?.lowercase()
            ?.takeIf { digitsForPinyin(it) == bounded }

        val validPreset = NineKeyPresets.combinations[bounded]
            .orEmpty()
            .filter { digitsForPinyin(it) == bounded }

        val paths = buildList {
            stable?.let(::add)
            addAll(validPreset)
            addAll(exactEntries.map { it.pinyin })
            addAll(decoded.map { it.pinyin })
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

        return Resolution(
            previewSuffix = paths.firstOrNull() ?: fallbackLetters(bounded),
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

    private fun sortTrie(node: TrieNode) {
        node.exact.sortWith(
            compareByDescending<Entry>(::entryScore)
                .thenBy { it.pinyin },
        )
        node.children.values.forEach(::sortTrie)
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
        val result = ArrayList<String>(limit)
        val seen = HashSet<String>()
        val max = batches.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until max) {
            batches.forEach { batch ->
                val value = batch.getOrNull(rank) ?: return@forEach
                if (seen.add(value)) result += value
                if (result.size >= limit) return result
            }
        }
        return result
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
            return buildString(pinyin.length) {
                pinyin.lowercase().forEach { ch ->
                    append(
                        when (ch) {
                            in 'a'..'c' -> '2'
                            in 'd'..'f' -> '3'
                            in 'g'..'i' -> '4'
                            in 'j'..'l' -> '5'
                            in 'm'..'o' -> '6'
                            in 'p'..'s' -> '7'
                            in 't'..'v', 'ü' -> '8'
                            in 'w'..'z' -> '9'
                            else -> return null
                        },
                    )
                }
            }
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
