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
 *
 * Two files feed it: the hand-written `emoji/keywords.tsv` first, then the
 * generated `emoji/follow-keywords.tsv` (the reference's own association
 * table, scripts/import_doubao_data.py). A keyword both carry keeps the
 * curated emoji, the generated file adds the keywords the curated one lacks.
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
        private const val FOLLOW_ASSET = "emoji/follow-keywords.tsv"

        val EMPTY = EmojiAssociationIndex(emptyMap())

        /**
         * Lines are `keyword<TAB>emoji ...`; `#` starts a comment. The emoji
         * are separated by spaces, or by tabs when the line has them, so a
         * kaomoji that itself contains a space stays one entry.
         */
        fun parse(lines: Sequence<String>): EmojiAssociationIndex {
            val table = LinkedHashMap<String, List<String>>()
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEach
                val keyword = line.substring(0, tab).trim()
                val emoji = line.substring(tab + 1).trim()
                    .let { if (it.contains('\t')) it.split('\t') else it.split(Regex("\\s+")) }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
                if (keyword.isNotEmpty() && emoji.isNotEmpty()) table.putIfAbsent(keyword, emoji)
            }
            return EmojiAssociationIndex(table)
        }

        /** Curated table first: it wins for any keyword the generated one also has. */
        fun load(context: Context): EmojiAssociationIndex =
            runCatching {
                parse(assetLines(context, ASSET) + assetLines(context, FOLLOW_ASSET))
            }.getOrDefault(EMPTY)

        @Volatile
        private var cached: EmojiAssociationIndex? = null

        /** The parsed tables, built once; [preload] fills this off the first commit. */
        fun obtain(context: Context): EmojiAssociationIndex =
            cached ?: synchronized(this) { cached ?: load(context).also { cached = it } }

        /** Read the tables on a background thread so the first commit does not. */
        fun preload(context: Context) {
            obtain(context)
        }

        private fun assetLines(context: Context, name: String): Sequence<String> =
            runCatching {
                context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readLines() }
            }.getOrDefault(emptyList()).asSequence()
    }
}
