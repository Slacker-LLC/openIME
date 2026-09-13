package llc.slacker.openime

import android.content.Context

/** Loads the generated Pinyin tables once per process and preserves phrase weights. */
object PinyinLexicon {
    private const val CHAR_ASSET = "pinyin_chars.tsv"
    private const val PHRASE_ASSET = "pinyin_phrases.tsv"

    private data class WeightedValue(
        val text: String,
        var weight: Int,
        val order: Int,
    )

    @Volatile
    private var cached: Map<String, List<String>>? = null

    @Volatile
    private var cachedWeights: Map<String, Int> = emptyMap()

    fun load(context: Context): Map<String, List<String>> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val result = LinkedHashMap<String, LinkedHashMap<String, WeightedValue>>()
                var order = 0

                fun add(pinyin: String, text: String, weight: Int) {
                    if (pinyin.isBlank() || text.isBlank()) return
                    val values = result.getOrPut(pinyin) { LinkedHashMap() }
                    val existing = values[text]
                    if (existing == null) {
                        values[text] = WeightedValue(text, weight.coerceAtLeast(0), order++)
                    } else if (weight > existing.weight) {
                        existing.weight = weight
                    }
                }

                // The character table is coverage data, not a frequency list.
                // Keep its source order only as a tie-breaker behind weighted phrases.
                context.assets.open(CHAR_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('\t', limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            parts[1].codePoints().forEach { codePoint ->
                                add(parts[0], String(Character.toChars(codePoint)), weight = 0)
                            }
                        }
                    }
                }

                // pinyin_phrases.tsv is generated with a real corpus weight in
                // column 3. The old loader parsed three columns but discarded the
                // weight, making the local nine-key decoder effectively frequency-blind.
                context.assets.open(PHRASE_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEach
                        val parts = line.split('\t', limit = 3)
                        if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            val weight = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            add(parts[0], parts[1], weight)
                        }
                    }
                }

                val weights = HashMap<String, Int>()
                val ordered = result.mapValues { (pinyin, values) ->
                    values.values
                        .sortedWith(
                            compareByDescending<WeightedValue> { it.weight }
                                .thenBy { it.order },
                        )
                        .take(96)
                        .onEach { value -> weights[weightKey(pinyin, value.text)] = value.weight }
                        .map { it.text }
                }
                cachedWeights = weights
                ordered
            }.getOrElse {
                cachedWeights = emptyMap()
                emptyMap()
            }.also { cached = it }
        }
    }

    /** Corpus weight retained for the local fallback/nine-key scorer. */
    fun weightFor(pinyin: String, text: String): Int =
        cachedWeights[weightKey(pinyin, text)] ?: 0

    private fun weightKey(pinyin: String, text: String): String = "$pinyin\u0000$text"
}
