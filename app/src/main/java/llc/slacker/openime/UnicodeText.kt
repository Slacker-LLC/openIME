package llc.slacker.openime

/**
 * Unicode helpers shared by composition editing and pre-Android-9 editor deletion.
 * Android's legacy deleteSurroundingText() counts UTF-16 code units, while users
 * expect one backspace to remove one Unicode code point.
 */
internal fun dropLastCodePointSafe(text: String): String {
    if (text.isEmpty()) return text
    val units = previousCodePointUtf16Length(text).coerceAtLeast(1)
    return text.substring(0, text.length - units)
}

/** Return the UTF-16 width of the final complete code point in [text]. */
internal fun previousCodePointUtf16Length(text: CharSequence?): Int {
    if (text.isNullOrEmpty()) return 0
    val lastIndex = text.length - 1
    val last = text[lastIndex]
    return if (
        Character.isLowSurrogate(last) &&
        lastIndex > 0 &&
        Character.isHighSurrogate(text[lastIndex - 1])
    ) {
        2
    } else {
        1
    }
}
