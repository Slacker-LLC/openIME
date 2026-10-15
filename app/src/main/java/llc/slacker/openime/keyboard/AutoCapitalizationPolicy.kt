package llc.slacker.openime.keyboard

/**
 * 首字母自动大写: whether the next English letter starts a sentence.
 *
 * The reference ships the switch as `enable_first_letter_capitalization` on
 * its 快捷格式输入 page (`res/xq.xml`, the `…_list_item` of
 * `string/first_letter_capitalization_title` 「首字母自动大写」); the stored
 * default is false (`res/cU.xml`: `BooleanPreference defaultValue="false"`).
 * With the switch on, the English Shift key must be up after a sentence mark
 * or at the start of the field, and down inside a word.
 */
internal object AutoCapitalizationPolicy {

    /** How far back the policy reads; a mark plus following spaces fit easily. */
    const val LOOKBACK = 8

    /** Sentence enders, half- and full-width, plus an explicit line break. */
    private val sentenceEnders = setOf('.', '!', '?', '…', '。', '！', '？', '\n')

    /**
     * True when [textBefore] ends a sentence (or is empty, the field's start),
     * so the next letter is a capital. Null means the editor could not be
     * read — the policy then leaves Shift alone rather than guessing.
     */
    fun atSentenceStart(textBefore: CharSequence?): Boolean {
        if (textBefore == null) return false
        var end = textBefore.length
        // Trailing spaces sit between the mark and the next word; a newline is
        // itself an ender, so it is not skipped.
        while (end > 0 && textBefore[end - 1].isWhitespace() && textBefore[end - 1] != '\n') end--
        if (end == 0) return true
        return textBefore[end - 1] in sentenceEnders
    }
}
