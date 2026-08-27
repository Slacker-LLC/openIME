package llc.slacker.openime

import android.content.Context

/** Loads the generated Unicode Unihan Mandarin reading table once per process. */
object PinyinLexicon {
    private const val CHAR_ASSET = "pinyin_chars.tsv"
    private const val PHRASE_ASSET = "pinyin_phrases.tsv"

    @Volatile
    private var cached: Map<String, List<String>>? = null

    fun load(context: Context): Map<String, List<String>> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val result = LinkedHashMap<String, MutableList<String>>()
                context.assets.open(CHAR_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('\t', limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            val values = result.getOrPut(parts[0]) { mutableListOf() }
                            parts[1].take(96).mapTo(values) { it.toString() }
                        }
                    }
                }
                context.assets.open(PHRASE_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEach
                        val parts = line.split('\t', limit = 3)
                        if (parts.size >= 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            val values = result.getOrPut(parts[0]) { mutableListOf() }
                            if (parts[1] !in values && values.size < 96) values.add(parts[1])
                        }
                    }
                }
                result.mapValues { (_, values) -> values.toList() }
            }.getOrDefault(emptyMap()).also { cached = it }
        }
    }
}
