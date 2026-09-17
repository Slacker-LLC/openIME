package llc.slacker.openime

/**
 * Number of UTF-16 code units to remove for a single "delete previous word" action.
 * Latin/digit runs are deleted as one token. CJK text deliberately deletes one
 * code point at a time because whitespace-free Han text has no reliable word
 * boundary without a language segmenter.
 */
internal fun previousWordDeleteUtf16Length(beforeCursor: String): Int {
    if (beforeCursor.isEmpty()) return 0

    var end = beforeCursor.length
    while (end > 0) {
        val cp = beforeCursor.codePointBefore(end)
        if (!Character.isWhitespace(cp)) break
        end -= Character.charCount(cp)
    }
    if (end == 0) return beforeCursor.length

    val last = beforeCursor.codePointBefore(end)
    if (isCjkCodePoint(last)) {
        end -= Character.charCount(last)
        return beforeCursor.length - end
    }

    if (isWordCodePoint(last)) {
        while (end > 0) {
            val cp = beforeCursor.codePointBefore(end)
            if (!isWordCodePoint(cp) || isCjkCodePoint(cp)) break
            end -= Character.charCount(cp)
        }
    } else {
        end -= Character.charCount(last)
    }

    return beforeCursor.length - end
}

private fun isWordCodePoint(codePoint: Int): Boolean =
    Character.isLetterOrDigit(codePoint) ||
        codePoint == '_'.code ||
        codePoint == '\''.code ||
        codePoint == '’'.code

private fun isCjkCodePoint(codePoint: Int): Boolean = when (Character.UnicodeScript.of(codePoint)) {
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    -> true
    else -> false
}

/** Delete one previous token through absolute editor coordinates when available. */
internal fun InputConnectionGateway.deletePreviousWord() {
    val snapshot = absoluteCursorSnapshot()
    if (snapshot == null) {
        deleteBackwards()
        return
    }
    val localCursor = (snapshot.cursorAbsolute - snapshot.windowStart)
        .coerceIn(0, snapshot.text.length)
    if (localCursor == 0) return

    val before = snapshot.text.substring(0, localCursor)
    val utf16Units = previousWordDeleteUtf16Length(before)
    if (utf16Units <= 0) return

    val startAbsolute = (snapshot.cursorAbsolute - utf16Units)
        .coerceAtLeast(snapshot.windowStart)
    selectStartEnd(startAbsolute, snapshot.cursorAbsolute)
    if (!deleteSelection()) deleteBackwards()
}
