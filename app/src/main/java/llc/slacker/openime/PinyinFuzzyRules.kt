package llc.slacker.openime

/**
 * Kotlin fallback counterpart of the fuzzy algebra enabled by
 * luna_pinyin_simp_fuzzy.schema.yaml. Keep this limited to the product's three
 * supported fuzzy groups so fallback and librime expose the same semantics.
 */
internal fun pinyinFuzzyVariants(rawPinyin: String): List<String> {
    val pinyin = rawPinyin.lowercase()
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
