package llc.slacker.openime.keyboard

/**
 * The digit or symbol printed in the top-right corner of each 26-key letter key,
 * on the reference product's map: the top row carries 1–0, every other key
 * carries one mark. Chinese mode prints the full-width mark a Chinese IME types,
 * English mode the ASCII one; the two layouts differ only where a full-width
 * mark exists.
 *
 * Swipe up on the key commits the hint. A long press opens the letter in both
 * cases, in the reference keyboard's own order — it does not offer the hint.
 */
internal object LetterHintPolicy {
    private val DIGITS = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
    )

    /** Marks that are the same in both languages: ASCII, or no full-width form. */
    private val SHARED_SYMBOLS = mapOf(
        'a' to "-", 's' to "/", 'z' to "@", 'c' to "#", 'm' to "…",
    )

    private val ENGLISH_SYMBOLS = SHARED_SYMBOLS + mapOf(
        'd' to ":", 'f' to ";", 'g' to "(", 'h' to ")",
        'j' to "~", 'k' to "'", 'l' to "\"",
        'x' to "_", 'v' to "&", 'b' to "?", 'n' to "!",
    )

    private val CHINESE_SYMBOLS = SHARED_SYMBOLS + mapOf(
        'd' to "：", 'f' to "；", 'g' to "（", 'h' to "）",
        'j' to "～", 'k' to "“", 'l' to "”",
        'x' to ".", 'v' to "、", 'b' to "？", 'n' to "！",
    )

    /** The hint of [letter] (lower case), or null for none. */
    fun hint(letter: Char, english: Boolean): String? =
        DIGITS[letter] ?: (if (english) ENGLISH_SYMBOLS else CHINESE_SYMBOLS)[letter]

    /**
     * What a long press on a letter key offers, in popup order: the letter in
     * both cases, upper first — except on P and L, where the reference's own
     * long-press list reads lower first (`p P`, `l L`, in both its Chinese and
     * its English layout). No other key or layout leads with the lower case,
     * so this is the reference's order on those two keys and not a rule.
     */
    private val LOWER_FIRST = setOf('p', 'l')

    fun caseChoices(letter: Char): List<String> {
        val lower = letter.lowercaseChar().toString()
        val upper = letter.uppercaseChar().toString()
        return if (letter.lowercaseChar() in LOWER_FIRST) listOf(lower, upper) else listOf(upper, lower)
    }
}
