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
    fun choiceRows(digit: String, keypad: Map<String, List<String>> = ImeData.keypad9Map): List<List<String>> {
        val letters = keypad[digit].orEmpty().filter { it.length == 1 && it[0] in 'a'..'z' }
        if (letters.isEmpty()) return emptyList()
        return listOf(letters.map { it.uppercase() }, listOf(digit) + letters)
    }
}
