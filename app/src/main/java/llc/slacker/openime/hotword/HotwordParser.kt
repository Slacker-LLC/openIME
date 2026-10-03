package llc.slacker.openime.hotword

/**
 * Parses the hotword list text format. Pure and Android-free.
 *
 * ```
 * # title: 手游与电竞
 * # description: 常见游戏和电竞用语
 * # default: off
 * 打野
 * 王者荣耀
 * ```
 *
 * One word per line. `#` lines are comments; `title`, `description` and
 * `default` are recognised as headers. Only Chinese words of 2..[MAX_WORD_LENGTH]
 * characters are kept: the corrector matches by pronunciation, which says
 * nothing about Latin text or single characters.
 */
internal object HotwordParser {
    const val MAX_WORD_LENGTH = 8
    const val MAX_WORDS = 5_000
    const val MAX_BYTES = 512 * 1024

    data class Parsed(
        val title: String,
        val description: String,
        val defaultEnabled: Boolean,
        val words: List<String>,
        val rejectedLines: Int,
        /** True when the list had more than [MAX_WORDS] usable words. */
        val truncated: Boolean,
    )

    fun parse(text: String, fallbackTitle: String): Parsed {
        var title = ""
        var description = ""
        var defaultEnabled = true
        var rejected = 0
        val words = LinkedHashSet<String>()
        var truncated = false

        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) {
                val header = line.removePrefix("#").trim()
                val key = header.substringBefore(':', "").trim().lowercase()
                val value = header.substringAfter(':', "").trim()
                when (key) {
                    "title" -> if (value.isNotEmpty()) title = value.take(32)
                    "description" -> description = value.take(80)
                    "default" -> defaultEnabled = value.lowercase() !in OFF_VALUES
                }
                continue
            }
            if (!isUsable(line)) {
                rejected++
            } else if (words.size >= MAX_WORDS) {
                truncated = true
            } else {
                words += line
            }
        }
        return Parsed(
            title = title.ifEmpty { fallbackTitle.take(32) },
            description = description,
            defaultEnabled = defaultEnabled,
            words = words.toList(),
            rejectedLines = rejected,
            truncated = truncated,
        )
    }

    /** Renders [parsed] back in the canonical format used for stored imports. */
    fun render(parsed: Parsed): String = buildString {
        append("# title: ").append(parsed.title).append('\n')
        if (parsed.description.isNotEmpty()) {
            append("# description: ").append(parsed.description).append('\n')
        }
        parsed.words.forEach { append(it).append('\n') }
    }

    internal fun isUsable(word: String): Boolean {
        val codePoints = word.codePoints().toArray()
        return codePoints.size in 2..MAX_WORD_LENGTH && codePoints.all(::isHan)
    }

    internal fun isHan(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF || codePoint in 0x4E00..0x9FFF

    private val OFF_VALUES = setOf("off", "false", "no", "0")
}
