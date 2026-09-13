package llc.slacker.openime

/**
 * Kotlin fallback counterpart of the fuzzy algebra enabled by
 * luna_pinyin_simp_fuzzy.schema.yaml. Fuzzy rules are applied per Pinyin
 * syllable so fallback and librime keep the same semantics for continuous
 * multi-syllable input as well as single syllables.
 */
internal fun pinyinFuzzyVariants(rawPinyin: String): List<String> {
    val pinyin = rawPinyin.lowercase()
    if (pinyin.isEmpty()) return emptyList()

    val variants = linkedSetOf<String>()
    singleSyllableFuzzyVariants(pinyin).forEach { variant ->
        if (variant != pinyin && variants.size < MAX_FUZZY_VARIANTS) variants += variant
    }

    if (variants.size < MAX_FUZZY_VARIANTS) {
        segmentedCanonicalVariants(pinyin).forEach { variant ->
            if (variant != pinyin && variants.size < MAX_FUZZY_VARIANTS) variants += variant
        }
    }

    return variants.toList()
}

private data class FuzzySyllableMatch(
    val spelling: String,
    val canonical: String,
    val exact: Boolean,
)

private val fuzzySyllableMatchesByFirst: Map<Char, List<FuzzySyllableMatch>> by lazy {
    val matches = LinkedHashMap<Char, MutableList<FuzzySyllableMatch>>()
    ImeData.pinyinDict.keys
        .asSequence()
        .filter { it.isNotBlank() && it.all { ch -> ch in 'a'..'z' } }
        .distinct()
        .forEach { canonical ->
            val spellings = linkedSetOf(canonical)
            spellings.addAll(singleSyllableFuzzyVariants(canonical))
            spellings.forEach { spelling ->
                val first = spelling.firstOrNull() ?: return@forEach
                matches.getOrPut(first) { mutableListOf() }
                    .add(
                        FuzzySyllableMatch(
                            spelling = spelling,
                            canonical = canonical,
                            exact = spelling == canonical,
                        ),
                    )
            }
        }
    matches.mapValues { (_, values) ->
        values
            .distinctBy { it.spelling to it.canonical }
            .sortedWith(
                compareByDescending<FuzzySyllableMatch> { it.exact }
                    .thenByDescending { it.spelling.length }
                    .thenBy { it.canonical },
            )
    }
}

/**
 * Interpret one continuous input string as a sequence of real Pinyin syllables,
 * accepting the same fuzzy spelling alternatives that Rime applies to every
 * syllable. The output is canonical Pinyin used for dictionary lookup.
 */
private fun segmentedCanonicalVariants(input: String): List<String> {
    if (input.length > MAX_FUZZY_INPUT_LENGTH) return emptyList()
    val states = Array(input.length + 1) { linkedSetOf<String>() }
    states[0] += ""

    for (start in input.indices) {
        val prefixes = states[start]
        if (prefixes.isEmpty()) continue
        val first = input[start]
        for (match in fuzzySyllableMatchesByFirst[first].orEmpty()) {
            if (!input.startsWith(match.spelling, start)) continue
            val end = start + match.spelling.length
            for (prefix in prefixes) {
                states[end] += prefix + match.canonical
                if (states[end].size >= MAX_FUZZY_STATES_PER_OFFSET) break
            }
        }
    }

    return states[input.length]
        .asSequence()
        .filter { it != input }
        .distinct()
        .take(MAX_FUZZY_VARIANTS)
        .toList()
}

/** Apply the configured fuzzy groups to one syllable. */
private fun singleSyllableFuzzyVariants(pinyin: String): List<String> {
    if (pinyin.isEmpty()) return emptyList()

    val variants = linkedSetOf<String>()
    val pending = mutableListOf(pinyin)
    var cursor = 0

    fun enqueue(candidate: String) {
        if (candidate != pinyin && variants.size < MAX_FUZZY_VARIANTS && variants.add(candidate)) {
            pending.add(candidate)
        }
    }

    while (cursor < pending.size && variants.size < MAX_FUZZY_VARIANTS) {
        val value = pending[cursor++]

        listOf("zh" to "z", "ch" to "c", "sh" to "s").forEach { (long, short) ->
            when {
                value.startsWith(long) -> enqueue(short + value.substring(long.length))
                value.startsWith(short) -> enqueue(long + value.substring(short.length))
            }
        }

        when {
            value.startsWith("n") -> enqueue("l" + value.substring(1))
            value.startsWith("l") -> enqueue("n" + value.substring(1))
        }

        when {
            value.endsWith("eng") -> enqueue(value.dropLast(3) + "en")
            value.endsWith("en") -> enqueue(value.dropLast(2) + "eng")
            value.endsWith("ing") -> enqueue(value.dropLast(3) + "in")
            value.endsWith("in") -> enqueue(value.dropLast(2) + "ing")
        }
    }

    return variants.toList()
}

private const val MAX_FUZZY_VARIANTS = 16
private const val MAX_FUZZY_STATES_PER_OFFSET = 24
private const val MAX_FUZZY_INPUT_LENGTH = 32
