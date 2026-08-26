package llc.slacker.openime

/**
 * Kotlin port of ui-suite/js/ime-engine.js IMEEngine.
 * Candidate ordering and fallback behavior mirror the web prototype.
 */
class CandidateEngine(externalPinyin: Map<String, List<String>> = emptyMap()) {

    private val pinyinDict: Map<String, List<String>> = LinkedHashMap<String, List<String>>().apply {
        putAll(ImeData.pinyinDict)
        externalPinyin.forEach { (key, values) ->
            this[key] = (get(key).orEmpty() + values).distinct().take(96)
        }
    }

    private val syllableKeys = pinyinDict.keys.sortedByDescending { it.length }

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
        // User choices outrank the built-in frequency order but do not hide
        // the normal candidates. This is how the user can reliably get a
        // less-common character or a personal phrase after selecting it once.
        result.addAll(UserPhraseRepository.candidatesFor(py))
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
        pinyinDict.forEach { (key, list) ->
            if (key.startsWith(py)) result.addAll(list.take(3))
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
        result.addAll(UserPhraseRepository.candidatesFor(key))
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
            syllableKeys.forEach { syllable ->
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
            segmentationTokens.forEach { token ->
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
        if (numberStr.isEmpty()) return NineKeyResult(emptyList(), emptyList())
        val preset = ImeData.keypad9Combinations[numberStr]
        if (!preset.isNullOrEmpty()) {
            val candidates = mutableSetOf<String>()
            preset.forEach { candidates.addAll(getCandidates(it)) }
            return NineKeyResult(preset, candidates.toList())
        }

        val possibleLetters = numberStr.map { ImeData.keypad9Map[it.toString()].orEmpty() }
        val combinations = mutableListOf<String>()
        generateCombos(possibleLetters, 0, StringBuilder(), combinations)
        val valid = combinations.ifEmpty { listOf(numberStr) }
        val candList = mutableSetOf<String>()
        valid.forEach { candList.addAll(getCandidates(it)) }
        return NineKeyResult(valid, candList.toList())
    }

    private fun generateCombos(
        letters: List<List<String>>,
        idx: Int,
        current: StringBuilder,
        out: MutableList<String>,
    ) {
        if (out.size >= 6) return
        if (idx == letters.size) {
            val candidate = current.toString()
            if (pinyinDict.containsKey(candidate) ||
                ImeData.phraseDict.containsKey(candidate)
            ) {
                out.add(candidate)
            }
            return
        }
        for (ch in letters[idx]) {
            if (ch.length != 1) continue
            generateCombos(letters, idx + 1, StringBuilder(current).append(ch), out)
        }
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

    fun getT9EnglishCandidates(digits: String): List<String> {
        if (digits.isEmpty()) return emptyList()
        val wanted = digits.filter { it.isDigit() }.ifEmpty { return emptyList() }
        val matches = linkedSetOf<String>()
        val words = ImeData.englishDict.values.flatten() + ImeData.englishDict.keys
        words.distinct().forEach { word ->
            if (wordToT9(word) == wanted) {
                matches.add(word)
                if (matches.size >= 12) return@forEach
            }
        }
        return matches.toList().ifEmpty { listOf(wanted) }
    }

    private fun wordToT9(word: String): String {
        val map = mapOf(
            "abc" to "2",
            "def" to "3",
            "ghi" to "4",
            "jkl" to "5",
            "mno" to "6",
            "pqrs" to "7",
            "tuv" to "8",
            "wxyz" to "9",
        )
        return word.lowercase().map { ch ->
            map.entries.firstOrNull { it.key.contains(ch) }?.value ?: ""
        }.joinToString("")
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
