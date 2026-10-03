package llc.slacker.openime.keyboard

import llc.slacker.openime.core.ImeData

/**
 * What a long press on a nine-key digit key offers.
 *
 * Row one is the digit and the lowercase letters on the key, row two the same
 * letters in uppercase, so a letter of either case or the digit is one tap away
 * without leaving the nine-key surface. Keys with no letters (1, 0, *, #) keep
 * their own behavior and return no rows.
 */
internal object NineKeyLongPressPolicy {
    fun choiceRows(digit: String, keypad: Map<String, List<String>> = ImeData.keypad9Map): List<List<String>> {
        val letters = keypad[digit].orEmpty().filter { it.length == 1 && it[0] in 'a'..'z' }
        if (letters.isEmpty()) return emptyList()
        return listOf(listOf(digit) + letters, letters.map { it.uppercase() })
    }
}
