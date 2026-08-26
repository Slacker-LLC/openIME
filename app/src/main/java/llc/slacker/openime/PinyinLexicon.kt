package llc.slacker.openime

import android.content.Context

/** Loads the generated Unicode Unihan Mandarin reading table once per process. */
object PinyinLexicon {
    private const val ASSET = "pinyin_chars.tsv"

    @Volatile
    private var cached: Map<String, List<String>>? = null

    fun load(context: Context): Map<String, List<String>> {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: runCatching {
                val result = LinkedHashMap<String, List<String>>()
                context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split('\t', limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            result[parts[0]] = parts[1].take(96).map { it.toString() }
                        }
                    }
                }
                result.toMap()
            }.getOrDefault(emptyMap()).also { cached = it }
        }
    }
}
