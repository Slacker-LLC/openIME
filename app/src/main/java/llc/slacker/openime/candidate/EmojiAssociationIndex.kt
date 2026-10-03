package llc.slacker.openime.candidate

import android.content.Context

/**
 * Local keyword to emoji lookup behind the association row ("联想").
 *
 * After a word is committed, the emoji that go with it are offered first:
 * 开心 -> 😊 😄 😁. A keyword matches when the committed text equals it, or,
 * for keywords of two characters or more, when the committed phrase ends with
 * it (今天好开心 -> 开心). One-character keywords such as 笑 or 猫 only match
 * exactly so that ordinary words never sprout unrelated emoji.
 *
 * The table is a bundled text asset; nothing here touches the network.
 */
class EmojiAssociationIndex internal constructor(
    private val byKeyword: Map<String, List<String>>,
) {
    private val longestKeyword = byKeyword.keys.maxOfOrNull { it.length } ?: 0

    /** Up to [limit] emoji for [committed]; empty when nothing matches. */
    fun emojiFor(committed: String, limit: Int = MAX_EMOJI): List<String> {
        val text = committed.trim()
        if (text.isEmpty() || byKeyword.isEmpty()) return emptyList()
        byKeyword[text]?.let { return it.take(limit) }
        val longest = minOf(longestKeyword, text.length - 1)
        for (length in longest downTo MIN_SUFFIX_KEYWORD_LENGTH) {
            byKeyword[text.takeLast(length)]?.let { return it.take(limit) }
        }
        return emptyList()
    }

    companion object {
        const val MAX_EMOJI = 3
        private const val MIN_SUFFIX_KEYWORD_LENGTH = 2
        private const val ASSET = "emoji/keywords.tsv"

        val EMPTY = EmojiAssociationIndex(emptyMap())

        /** Lines are `keyword<TAB>emoji emoji ...`; `#` starts a comment. */
        fun parse(lines: Sequence<String>): EmojiAssociationIndex {
            val table = LinkedHashMap<String, List<String>>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val keyword = line.substring(0, tab).trim()
                val emoji = line.substring(tab + 1).trim()
                    .split(Regex("\\s+"))
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (keyword.isNotEmpty() && emoji.isNotEmpty()) table.putIfAbsent(keyword, emoji)
            }
            return EmojiAssociationIndex(table)
        }

        fun load(context: Context): EmojiAssociationIndex =
            runCatching {
                context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { reader ->
                    parse(reader.lineSequence().toList().asSequence())
                }
            }.getOrDefault(EMPTY)
    }
}
