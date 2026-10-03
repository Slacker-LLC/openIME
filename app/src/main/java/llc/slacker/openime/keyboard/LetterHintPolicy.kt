package llc.slacker.openime.keyboard

/**
 * The digit or symbol printed in the top-right corner of each 26-key letter key
 * (the scheme Sogou, iFlytek and WeChat Keyboard use): the top row carries
 * 1–0, the other rows carry punctuation. Swipe up on the key, or long press it,
 * commits the hint instead of the letter.
 *
 * Chinese mode commits the full-width marks a Chinese IME types; English mode
 * the ASCII ones. The two layouts differ only where a full-width mark exists.
 */
internal object LetterHintPolicy {
    private val DIGITS = mapOf(
        'q' to "1", 'w' to "2", 'e' to "3", 'r' to "4", 't' to "5",
        'y' to "6", 'u' to "7", 'i' to "8", 'o' to "9", 'p' to "0",
    )

    private val ENGLISH_SYMBOLS = mapOf(
        'a' to "~", 's' to "!", 'd' to "@", 'f' to "#", 'g' to "$",
        'h' to "%", 'j' to "&", 'k' to "*", 'l' to "?",
        'z' to "(", 'x' to ")", 'c' to "-", 'v' to "_",
        'b' to ":", 'n' to ";", 'm' to "/",
    )

    private val CHINESE_SYMBOLS = ENGLISH_SYMBOLS + mapOf(
        's' to "！", 'g' to "￥", 'l' to "？",
        'z' to "（", 'x' to "）", 'b' to "：", 'n' to "；",
    )

    /** The hint of [letter] (lower case), or null for none. */
    fun hint(letter: Char, english: Boolean): String? =
        DIGITS[letter] ?: (if (english) ENGLISH_SYMBOLS else CHINESE_SYMBOLS)[letter]
}
