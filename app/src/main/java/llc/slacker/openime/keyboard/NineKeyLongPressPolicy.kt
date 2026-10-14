package llc.slacker.openime.keyboard

import llc.slacker.openime.core.ImeData

/**
 * What a long press on a nine-key digit key offers.
 *
 * The popup sits above the key, so the last row is the one nearest the thumb: the
 * digit and the lowercase letters, which people type most. The row above it holds
 * the same letters in uppercase. Press, slide to a cell and lift to type it, or lift
 * first and tap it. Keys with no letters (1, 0, *, #) keep
 * their own behavior and return no rows.
 */
internal object NineKeyLongPressPolicy {
    /**
     * The nine-key `@#` key's symbols, as the reference lists them in that key's
     * own up-swipe bubble (`input_kbd_pinyin9.xml:26-27`). The corner already
     * costs one of them — the digit 1, which a plain up swipe types — so the list
     * stays reachable here.
     */
    val AT_SYMBOL_CHOICES = listOf("/", "-", "_", "1", "@", "#", ".")

    fun choiceRows(digit: String, keypad: Map<String, List<String>> = ImeData.keypad9Map): List<List<String>> {
        val letters = keypad[digit].orEmpty().filter { it.length == 1 && it[0] in 'a'..'z' }
        if (letters.isEmpty()) return emptyList()
        return listOf(letters.map { it.uppercase() }, listOf(digit) + letters)
    }
}
