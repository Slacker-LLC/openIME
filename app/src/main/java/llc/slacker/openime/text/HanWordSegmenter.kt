package llc.slacker.openime.text

/**
 * One piece of a segmented text: a word the tables know, a run of letters or
 * digits, or a separator between them.
 *
 * [start] and [end] are code-unit offsets into the text it came from, so the
 * text a run of tokens covers is the substring between the first and the last
 * of them — separators inside the run come along, as they are written.
 */
internal data class HanToken(
    val text: String,
    val start: Int,
    val end: Int,
    val selectable: Boolean,
)

/**
 * Splits a text into the words the tables carry, the way the reference's 拆词
 * does for a copied text: it walks the text and takes the longest word it finds
 * at each position, so 今天天气 reads 今天 + 天气 rather than four characters,
 * and falls back to single characters where no word of the tables starts there.
 *
 * Letters and digits of any script form one token, so a copied address or
 * message id stays whole. Whitespace and punctuation are tokens too, but they
 * are not selectable: they separate words rather than being one, and the
 * reference's label colours have a clickable and an un-clickable face.
 */
internal object HanWordSegmenter {
    /** The longest word of the phrase table is 8 characters (依法追究刑事责任). */
    const val MAX_WORD_CHARS = 8

    fun segment(text: String, isWord: (String) -> Boolean): List<HanToken> {
        val tokens = ArrayList<HanToken>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            when {
                isHan(codePoint) -> {
                    val (token, consumed) = readWord(text, index, isWord)
                    tokens += token
                    index += consumed
                    continue
                }
                Character.isLetterOrDigit(codePoint) -> {
                    var end = index + width
                    while (end < text.length) {
                        val next = text.codePointAt(end)
                        if (!Character.isLetterOrDigit(next) || isHan(next)) break
                        end += Character.charCount(next)
                    }
                    tokens += HanToken(text.substring(index, end), index, end, selectable = true)
                    index = end
                    continue
                }
                else -> {
                    var end = index + width
                    while (end < text.length) {
                        val next = text.codePointAt(end)
                        if (isHan(next) || Character.isLetterOrDigit(next)) break
                        end += Character.charCount(next)
                    }
                    tokens += HanToken(text.substring(index, end), index, end, selectable = false)
                    index = end
                    continue
                }
            }
        }
        return tokens
    }

    /** The longest word starting at [start], or its first character when none does. */
    private fun readWord(
        text: String,
        start: Int,
        isWord: (String) -> Boolean,
    ): Pair<HanToken, Int> {
        var end = start
        var characters = 0
        while (end < text.length && characters < MAX_WORD_CHARS) {
            val codePoint = text.codePointAt(end)
            if (!isHan(codePoint)) break
            end += Character.charCount(codePoint)
            characters++
        }
        while (characters > 1) {
            val candidate = text.substring(start, end)
            if (isWord(candidate)) return HanToken(candidate, start, end, selectable = true) to (end - start)
            end -= Character.charCount(text.codePointBefore(end))
            characters--
        }
        val single = text.substring(start, end)
        return HanToken(single, start, end, selectable = isWord(single)) to (end - start)
    }

    private fun isHan(codePoint: Int): Boolean =
        Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}
