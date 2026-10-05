package llc.slacker.openime.candidate

import llc.slacker.openime.core.FuzzyRule
import llc.slacker.openime.core.ImeData

/**
 * The 模糊音 pairs switched on. The service sets it from the settings (empty
 * when 模糊音 is off); the callers' `fuzzy` flag still decides whether fuzzy
 * spellings are looked up at all.
 */
object FuzzyPinyin {
    @Volatile
    var rules: Set<FuzzyRule> = FuzzyRule.DEFAULTS
}

/**
 * Kotlin fallback counterpart of the fuzzy algebra enabled by
 * luna_pinyin_simp_fuzzy.schema.yaml (openime_fuzzy.yaml). Fuzzy rules are
 * applied per Pinyin syllable so fallback and librime keep the same semantics
 * for continuous multi-syllable input as well as single syllables.
 */
internal fun pinyinFuzzyVariants(rawPinyin: String, rules: Set<FuzzyRule> = FuzzyPinyin.rules): List<String> {
    val pinyin = rawPinyin.lowercase()
    if (pinyin.isEmpty() || rules.isEmpty()) return emptyList()

    val cached = fuzzyVariantCache
    if (cached.input == pinyin && cached.rules == rules) return cached.variants

    val variants = linkedSetOf<String>()
    singleSyllableFuzzyVariants(pinyin, rules).forEach { variant ->
        if (variant != pinyin && variants.size < MAX_FUZZY_VARIANTS) variants += variant
    }

    if (variants.size < MAX_FUZZY_VARIANTS) {
        segmentedCanonicalVariants(pinyin, rules).forEach { variant ->
            if (variant != pinyin && variants.size < MAX_FUZZY_VARIANTS) variants += variant
        }
    }

    return variants.toList().also { result ->
        fuzzyVariantCache = FuzzyVariantCache(pinyin, rules, result)
    }
}

private data class FuzzyVariantCache(
    val input: String,
    val rules: Set<FuzzyRule>,
    val variants: List<String>,
)

@Volatile
private var fuzzyVariantCache = FuzzyVariantCache("", emptySet(), emptyList())

private data class FuzzySyllableMatch(
    val spelling: String,
    val canonical: String,
    val exact: Boolean,
)

private data class FuzzySyllableIndex(
    val rules: Set<FuzzyRule>,
    val byFirst: Map<Char, List<FuzzySyllableMatch>>,
)

@Volatile
private var fuzzySyllableIndex: FuzzySyllableIndex? = null

/** Syllable spellings under [rules], by first letter; rebuilt when the rules change. */
private fun fuzzySyllableMatchesByFirst(rules: Set<FuzzyRule>): Map<Char, List<FuzzySyllableMatch>> {
    fuzzySyllableIndex?.takeIf { it.rules == rules }?.let { return it.byFirst }
    return buildFuzzySyllableMatches(rules).also { fuzzySyllableIndex = FuzzySyllableIndex(rules, it) }
}

private fun buildFuzzySyllableMatches(rules: Set<FuzzyRule>): Map<Char, List<FuzzySyllableMatch>> {
    val matches = LinkedHashMap<Char, MutableList<FuzzySyllableMatch>>()
    ImeData.pinyinDict.keys
        .asSequence()
        .filter { it.isNotBlank() && it.all { ch -> ch in 'a'..'z' } }
        .distinct()
        .forEach { canonical ->
            val spellings = linkedSetOf(canonical)
            spellings.addAll(singleSyllableFuzzyVariants(canonical, rules))
            spellings.forEach spellingLoop@{ spelling ->
                val first = spelling.firstOrNull() ?: return@spellingLoop
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
    return matches.mapValues { (_, values) ->
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
private fun segmentedCanonicalVariants(input: String, rules: Set<FuzzyRule>): List<String> {
    if (input.length > MAX_FUZZY_INPUT_LENGTH) return emptyList()
    val states = Array(input.length + 1) { linkedSetOf<String>() }
    states[0] += ""

    for (start in input.indices) {
        val prefixes = states[start]
        if (prefixes.isEmpty()) continue
        val first = input[start]
        for (match in fuzzySyllableMatchesByFirst(rules)[first].orEmpty()) {
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

/** Apply [rules] to one syllable, repeatedly (z=zh and an=ang give zang for zhan). */
private fun singleSyllableFuzzyVariants(pinyin: String, rules: Set<FuzzyRule>): List<String> {
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

        for (rule in FuzzyRule.entries) {
            if (rule in rules) rule.variant(value)?.let(::enqueue)
        }
    }

    return variants.toList()
}

private const val MAX_FUZZY_VARIANTS = 16
private const val MAX_FUZZY_STATES_PER_OFFSET = 24
private const val MAX_FUZZY_INPUT_LENGTH = 32
