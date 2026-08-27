package llc.slacker.openime

/**
 * Kotlin port of ui-suite/js/ime-engine.js IMEEngine.
 * Candidate ordering and fallback behavior mirror the web prototype.
 */
class CandidateEngine(externalPinyin: Map<String, List<String>> = emptyMap()) {

    private val pinyinDict: Map<String, List<String>> = LinkedHashMap<String, List<String>>().apply {
        externalPinyin.forEach { (key, values) ->
            this[key] = values.distinct().take(96)
        }
        ImeData.pinyinDict.forEach { (key, values) ->
            // The generated full lexicon is the useful immediate fallback.
            // The compact hand-written table only fills gaps behind it and
            // must not silently replace its frequency order.
            this[key] = (get(key).orEmpty() + values).distinct().take(96)
        }
    }

    private val sortedPinyinKeys = pinyinDict.keys.sorted()
    private val syllablesByFirst: Map<Char, List<String>> = pinyinDict
        .asSequence()
        .filter { (key, values) ->
            key.length <= 6 && values.any { value ->
                value.codePointCount(0, value.length) == 1
            }
        }
        .map { it.key }
        .distinct()
        .sortedByDescending { it.length }
        .groupBy { it.first() }

    private data class SyllableState(
        val score: Int,
        val parts: Int,
        val initials: String,
    )

    /**
     * Initial-letter input is intentionally indexed separately from full
     * Pinyin. It lets Chinese 26-key users type `nh` for `你好` without
     * changing the normal full-Pinyin path.
     */
    /**
     * Build this index during IME construction rather than on the first key
     * press. The previous lazy property made the first `n`/`n`+`i` event do
     * all dictionary segmentation on the input thread, which looked like a
     * keyboard freeze on a real device.
     */
    private val initialIndex: Map<String, List<String>> =
        PinyinInitialIndexCache.get(externalPinyin) { buildInitialIndex() }

    private fun buildInitialIndex(): Map<String, List<String>> {
        val index = LinkedHashMap<String, MutableList<String>>()
        fun add(pinyin: String, values: List<String>) {
            pinyinInitials(pinyin)?.let { initials ->
                val list = index.getOrPut(initials) { mutableListOf() }
                values.forEach { value ->
                    if (value !in list && list.size < 96) list.add(value)
                }
            }
        }
        // Phrase entries go first so common multi-character words outrank
        // the very large single-character fallback list.
        ImeData.phraseDict.forEach { (pinyin, values) -> add(pinyin, values) }
        // Explicitly segmented phrases also contribute their ordinary
        // initials: xi|an -> xa. This keeps abbreviation input useful even
        // when the compact phrase table stores the word with a boundary.
        ImeData.segmentedPhraseDict.forEach { (segmented, values) ->
            val initials = segmented.split('|')
                .mapNotNull { it.firstOrNull() }
                .joinToString("")
            if (initials.isNotEmpty()) {
                val list = index.getOrPut(initials) { mutableListOf() }
                values.forEach { value ->
                    if (value !in list && list.size < 96) list.add(value)
                }
            }
        }
        pinyinDict.forEach { (pinyin, values) -> add(pinyin, values) }
        return index.mapValues { (_, values) -> values.toList() }
    }

    private data class SegmentationToken(
        val pinyin: String,
        val candidate: String,
        val phrase: Boolean,
    )

    private data class SegmentationState(
        val score: Int,
        val parts: Int,
        val text: String,
    )

    private val segmentationTokens: List<SegmentationToken> by lazy {
        buildList {
            ImeData.phraseDict.forEach { (pinyin, candidates) ->
                candidates.firstOrNull()?.let { candidate ->
                    add(SegmentationToken(pinyin, candidate, phrase = true))
                }
            }
            pinyinDict.forEach { (pinyin, candidates) ->
                if (!ImeData.phraseDict.containsKey(pinyin)) {
                    candidates.firstOrNull()?.let { candidate ->
                        add(SegmentationToken(pinyin, candidate, phrase = false))
                    }
                }
            }
        }.sortedWith(
            compareByDescending<SegmentationToken> { it.pinyin.length }
                .thenByDescending { it.phrase },
        )
    }
    private val segmentationTokensByFirst: Map<Char, List<SegmentationToken>> by lazy {
        segmentationTokens.groupBy { it.pinyin.first() }
    }

    /**
     * A compact Chinese 9-key Pinyin index. It must never enumerate every
     * possible letter combination: 3^50 combinations would freeze the IME
     * main thread before the user can see the next key feedback.
     */
    private data class NineKeyEntry(
        val pinyin: String,
        val digits: String,
        val candidates: List<String>,
        val phrase: Boolean,
    )

    private data class NineKeyDecode(
        val pinyin: String,
        val text: String,
        val score: Int,
        val parts: Int,
    )

    private val nineKeyEntries: List<NineKeyEntry> = buildNineKeyEntries()
    private val nineKeyEntriesByFirstDigit: Map<Char, List<NineKeyEntry>> =
        nineKeyEntries.groupBy { it.digits.first() }

    data class NineKeyResult(
        val pinyins: List<String>,
        val candidates: List<String>,
    )

    fun getCandidates(rawPinyin: String, fuzzy: Boolean = false): List<String> {
        val py = rawPinyin.lowercase().trim()
        if (py.isEmpty()) return emptyList()

        // A space or | is an explicit syllable boundary from the 9-key
        // "分词" key. Keep the boundary in the composing field so it stays
        // editable, but never expose it as a literal candidate character.
        if (py.any { it == '|' || it.isWhitespace() }) {
            return getSegmentedCandidates(py, fuzzy)
        }

        val result = linkedSetOf<String>()
        if (fuzzy) {
            fuzzyVariants(py).forEach { variant ->
                result.addAll(ImeData.phraseDict[variant].orEmpty())
            }
        }
        result.addAll(ImeData.phraseDict[py].orEmpty())

        if (fuzzy) {
            fuzzyVariants(py).forEach { variant ->
                result.addAll(pinyinDict[variant].orEmpty())
            }
        }
        result.addAll(pinyinDict[py].orEmpty())

        ImeData.phraseDict.forEach { (key, list) ->
            if (key.startsWith(py)) result.addAll(list)
        }
        var prefixIndex = lowerBound(sortedPinyinKeys, py)
        while (prefixIndex < sortedPinyinKeys.size) {
            val key = sortedPinyinKeys[prefixIndex]
            if (!key.startsWith(py)) break
            result.addAll(pinyinDict[key].orEmpty().take(3))
            if (result.size >= 96) break
            prefixIndex++
        }

        if (result.isEmpty() && py.length > 2) {
            val max = minOf(py.length, 6)
            for (i in 2..max) {
                val p1 = py.substring(0, i)
                val p2 = py.substring(i)
                val w1 = pinyinDict[p1]?.firstOrNull() ?: continue
                val w2 = if (p2.isEmpty()) "" else pinyinDict[p2]?.firstOrNull() ?: continue
                val word = w1 + w2
                if (word.isNotEmpty()) result.add(word)
            }
        }

        // Keep continuous Pinyin useful even when it is not one dictionary
        // phrase. The first candidate is a deterministic longest-match
        // segmentation (for example nihao + shurufa + kaifa), while the
        // normal prefix/fallback candidates remain available after it.
        segmentedCandidate(py)?.let { segmented ->
            if (segmented != py) result.add(segmented)
        }

        // Every alphabetic input may also be read as a stream of initials.
        // Full Pinyin stays first; abbreviation candidates are appended so
        // typing "xian" keeps "先" while also allowing a phrase match.
        if (isAlphabeticInput(py)) result.addAll(initialCandidates(py))

        if (result.isEmpty()) result.add(py)
        return result.take(96)
    }

    /** Return next-word suggestions for the committed Chinese context. */
    fun getAssociations(context: String): List<String> {
        if (context.isEmpty()) return emptyList()
        val result = linkedSetOf<String>()
        ImeData.associationDict.keys
            .sortedByDescending { it.length }
            .firstOrNull { context.endsWith(it) }
            ?.let { result.addAll(ImeData.associationDict[it].orEmpty()) }
        context.lastOrNull()?.toString()?.let { result.addAll(ImeData.associationDict[it].orEmpty()) }
        return result.filter { it.isNotEmpty() }.take(12)
    }

    private fun isAlphabeticInput(py: String): Boolean =
        py.length <= 32 && py.all { it in 'a'..'z' }

    private fun initialCandidates(initials: String): List<String> {
        val result = linkedSetOf<String>()
        initialIndex[initials]?.let { result.addAll(it) }
        if (result.isEmpty()) {
            initialIndex.forEach { (key, values) ->
                if (key.startsWith(initials)) result.addAll(values)
            }
        }
        return result.take(96)
    }

    /** Resolve a composition containing explicit syllable boundaries. */
    private fun getSegmentedCandidates(raw: String, fuzzy: Boolean): List<String> {
        val parts = raw
            .replace('|', ' ')
            .trim()
            .split(Regex("\\s+"))
            .map { it.filter(Char::isLetter) }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        if (parts.size == 1) return getCandidates(parts.first(), fuzzy)

        val key = parts.joinToString("|")
        val result = linkedSetOf<String>()
        result.addAll(ImeData.segmentedPhraseDict[key].orEmpty())

        // The general fallback handles arbitrary boundaries even when a
        // phrase is not yet in the compact phrase table. Limit the product so
        // this remains cheap on the IME input thread.
        var product = listOf("")
        parts.forEach { part ->
            val choices = getCandidates(part, fuzzy)
                .filter { it != part }
                .take(12)
                .ifEmpty { listOf(part) }
            product = product.flatMap { prefix ->
                choices.map { prefix + it }
            }.take(96)
        }
        result.addAll(product.filter { it.isNotEmpty() })
        return result.take(96).ifEmpty { listOf(parts.joinToString("")) }
    }

    /** Split a known Pinyin string into syllables and return first letters. */
    private fun pinyinInitials(pinyin: String): String? {
        if (pinyin.isEmpty()) return null
        val best = arrayOfNulls<SyllableState>(pinyin.length + 1)
        best[0] = SyllableState(score = 0, parts = 0, initials = "")
        for (start in pinyin.indices) {
            val previous = best[start] ?: continue
            syllablesByFirst[pinyin[start]].orEmpty().forEach { syllable ->
                if (!pinyin.startsWith(syllable, start)) return@forEach
                val end = start + syllable.length
                val next = SyllableState(
                    score = previous.score + syllable.length * 10,
                    parts = previous.parts + 1,
                    initials = previous.initials + syllable.first(),
                )
                val current = best[end]
                if (current == null || next.score > current.score ||
                    (next.score == current.score && next.parts < current.parts)
                ) {
                    best[end] = next
                }
            }
        }
        return best[pinyin.length]?.initials
    }

    /**
     * Segment a continuous lowercase Pinyin string into known phrases/syllables.
     * A result is returned only when every input letter is consumed, so unknown
     * text is never silently converted into a made-up Chinese candidate.
     */
    private fun segmentedCandidate(py: String): String? {
        if (py.length < 2 || py.any { !it.isLetter() }) return null
        val best = arrayOfNulls<SegmentationState>(py.length + 1)
        best[0] = SegmentationState(score = 0, parts = 0, text = "")
        for (start in py.indices) {
            val previous = best[start] ?: continue
            segmentationTokensByFirst[py[start]].orEmpty().forEach { token ->
                if (!py.startsWith(token.pinyin, start)) return@forEach
                val end = start + token.pinyin.length
                val next = SegmentationState(
                    score = previous.score + token.pinyin.length * 10 + if (token.phrase) 25 else 0,
                    parts = previous.parts + 1,
                    text = previous.text + token.candidate,
                )
                val current = best[end]
                if (current == null || next.score > current.score ||
                    (next.score == current.score && next.parts < current.parts)
                ) {
                    best[end] = next
                }
            }
        }
        return best[py.length]?.takeIf { it.parts >= 2 }?.text
    }

    private fun fuzzyVariants(py: String): List<String> {
        val out = mutableSetOf<String>()
        val pairs = listOf(
            "z" to "zh",
            "c" to "ch",
            "s" to "sh",
        )
        for ((short, long) in pairs) {
            if (py.startsWith(short) && !py.startsWith(long)) {
                out.add(long + py.substring(short.length))
            }
            if (py.startsWith(long)) {
                out.add(short + py.substring(long.length))
            }
        }
        return out.toList()
    }

    fun get9KeyCandidates(numberStr: String): NineKeyResult {
        val digits = numberStr
            .take(MAX_NINE_KEY_DIGITS)
            .filter { it in '2'..'9' }
        if (digits.isEmpty()) return NineKeyResult(emptyList(), emptyList())

        val pinyins = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()

        fun addEntry(entry: NineKeyEntry, resolvePinyin: Boolean) {
            pinyins.add(entry.pinyin)
            candidates.addAll(entry.candidates)
            if (resolvePinyin && entry.pinyin.length <= MAX_LOCAL_RESOLVE_LENGTH) {
                candidates.addAll(getCandidates(entry.pinyin))
            }
        }

        // Keep the hand-written high-confidence mappings first, then enrich
        // them with the complete embedded dictionary below.
        ImeData.keypad9Combinations[digits].orEmpty().forEach { pinyin ->
            pinyins.add(pinyin)
            candidates.addAll(getCandidates(pinyin))
        }

        val matchingEntries = nineKeyEntriesByFirstDigit[digits.first()].orEmpty()
        matchingEntries
            .asSequence()
            .filter { it.digits == digits }
            .take(MAX_NINE_MATCHES)
            .forEach { addEntry(it, resolvePinyin = true) }

        // Resolve a long stream as a sequence of known Pinyin words/syllables
        // rather than trying to materialize its exponential letter product.
        decodeNineKey(digits)?.let { decoded ->
            pinyins.add(decoded.pinyin)
            if (decoded.text.isNotEmpty()) candidates.add(decoded.text)
            if (decoded.pinyin.length <= MAX_LOCAL_RESOLVE_LENGTH) {
                candidates.addAll(getCandidates(decoded.pinyin))
            }
        }

        // While the current key stream is only a prefix, expose likely full
        // Pinyin entries so the candidate strip remains useful immediately.
        matchingEntries
            .asSequence()
            .filter { it.digits.startsWith(digits) }
            .sortedWith(
                compareByDescending<NineKeyEntry> { it.phrase }
                    .thenByDescending { it.digits.length },
            )
            .take(MAX_NINE_MATCHES)
            .forEach { addEntry(it, resolvePinyin = false) }

        return NineKeyResult(
            pinyins = pinyins.take(MAX_NINE_MATCHES),
            candidates = candidates.filter { it.isNotEmpty() }.take(96),
        )
    }

    private fun buildNineKeyEntries(): List<NineKeyEntry> {
        val merged = LinkedHashMap<String, MutableList<String>>()
        ImeData.phraseDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        pinyinDict.forEach { (pinyin, values) ->
            merged.getOrPut(pinyin) { mutableListOf() }.addAll(values)
        }
        val phraseKeys = ImeData.phraseDict.keys
        return merged.mapNotNull { (pinyin, values) ->
            val digits = pinyinToNineDigits(pinyin) ?: return@mapNotNull null
            NineKeyEntry(
                pinyin = pinyin,
                digits = digits,
                candidates = values.distinct().take(96),
                phrase = pinyin in phraseKeys,
            )
        }.sortedWith(
            compareByDescending<NineKeyEntry> { it.phrase }
                .thenByDescending { it.digits.length }
                .thenBy { it.pinyin },
        )
    }

    private fun pinyinToNineDigits(pinyin: String): String? {
        val digits = StringBuilder(pinyin.length)
        pinyin.lowercase().forEach { ch ->
            val digit = when (ch) {
                in 'a'..'c' -> '2'
                in 'd'..'f' -> '3'
                in 'g'..'i' -> '4'
                in 'j'..'l' -> '5'
                in 'm'..'o' -> '6'
                in 'p'..'s' -> '7'
                in 't'..'v' -> '8'
                in 'w'..'z' -> '9'
                else -> return null
            }
            digits.append(digit)
        }
        return digits.toString().ifEmpty { null }
    }

    private fun decodeNineKey(digits: String): NineKeyDecode? {
        val best = arrayOfNulls<NineKeyDecode>(digits.length + 1)
        best[0] = NineKeyDecode(pinyin = "", text = "", score = 0, parts = 0)
        for (start in digits.indices) {
            val previous = best[start] ?: continue
            nineKeyEntriesByFirstDigit[digits[start]].orEmpty().forEach { entry ->
                val end = start + entry.digits.length
                if (end > digits.length || !digits.regionMatches(start, entry.digits, 0, entry.digits.length)) {
                    return@forEach
                }
                val next = NineKeyDecode(
                    pinyin = previous.pinyin + entry.pinyin,
                    text = previous.text + entry.candidates.firstOrNull().orEmpty(),
                    score = previous.score + entry.digits.length * 10 + if (entry.phrase) 40 else 0,
                    parts = previous.parts + 1,
                )
                val current = best[end]
                if (
                    current == null ||
                    next.score > current.score ||
                    (next.score == current.score && next.parts < current.parts)
                ) {
                    best[end] = next
                }
            }
        }
        return best[digits.length]?.takeIf { it.parts > 0 }
    }

    fun getEnglishCompletions(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        ImeData.englishDict[p]?.let { return it }
        return listOf(
            prefix,
            prefix.uppercase(),
            prefix.replaceFirstChar { it.uppercase() },
        )
    }

    /**
     * English T9 was removed from the product. Keep this empty compatibility
     * surface only while legacy view/state code still references the old mode;
     * it deliberately performs no dictionary scan or digit-to-word matching.
     */
    @Deprecated("English T9 is no longer supported")
    fun getT9EnglishCandidates(@Suppress("UNUSED_PARAMETER") digits: String): List<String> = emptyList()

    private fun lowerBound(values: List<String>, target: String): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (values[middle] < target) low = middle + 1 else high = middle
        }
        return low
    }

    internal companion object {
        const val MAX_NINE_KEY_DIGITS = 64
        private const val MAX_NINE_MATCHES = 12
        private const val MAX_LOCAL_RESOLVE_LENGTH = 32
    }
}

/** Share the expensive initial-letter index between the two engine instances
 * used by the IME view and the service. PinyinLexicon returns the same map
 * instance for a process, so identity is a safe and allocation-free key. */
private object PinyinInitialIndexCache {
    private var source: Map<String, List<String>>? = null
    private var index: Map<String, List<String>> = emptyMap()

    @Synchronized
    fun get(
        sourceMap: Map<String, List<String>>,
        builder: () -> Map<String, List<String>>,
    ): Map<String, List<String>> {
        if (source === sourceMap) return index
        val built = builder()
        source = sourceMap
        index = built
        return built
    }
}