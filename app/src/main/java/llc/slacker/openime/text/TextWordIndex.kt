package llc.slacker.openime.text

import android.content.Context
import llc.slacker.openime.candidate.PinyinLexicon

/**
 * The text side of the Pinyin tables: every word they carry, for 拆词.
 *
 * The tables are keyed by reading, and the same word sits under each of its
 * readings, so the set is the union of the values; single characters come in
 * with them, from the character table and the Rime table alike. Built once on
 * the panel's own thread, next to the clipboard read that asked for it.
 */
internal object TextWordIndex {
    @Volatile
    private var cached: Set<String>? = null

    fun words(context: Context): Set<String> = cached ?: synchronized(this) {
        cached ?: build(PinyinLexicon.load(context)).also { cached = it }
    }

    internal fun build(lexicon: Map<String, List<String>>): Set<String> {
        val words = HashSet<String>(lexicon.size * 4)
        lexicon.values.forEach { values -> words.addAll(values) }
        return words
    }
}
