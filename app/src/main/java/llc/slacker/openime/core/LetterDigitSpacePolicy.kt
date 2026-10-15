package llc.slacker.openime.core

/**
 * 数字、英文前后加空格: a space is inserted where Chinese meets digits or
 * English, on either side of the boundary.
 *
 * The reference ships the switch as `enable_letter_digit_space` on its
 * 快捷格式输入 page (`res/xq.xml`); its own label and summary are the whole
 * rule — `string/letter_digit_space_list_item_title` 「数字、英文前后加空格」
 * and `string/letter_digit_space_list_item_subtitle` 「在语音与打字输入时，在
 * 中文和数字、英文间将增加空格」, i.e. voice input and typing alike. That row
 * is the only trace of the switch in the reference: `res/cU.xml` holds no
 * `enable_letter_digit_space` preference and the whole APK has no such string,
 * so no default can be read from it — ours ships off.
 */
internal object LetterDigitSpacePolicy {

    /**
     * Whether a space belongs between [left] (the text already at that side)
     * and [right] (the text being joined to it). True exactly when one side is
     * Chinese and the other is a Latin letter or an ASCII digit; anything else
     * — including a space already there — is left as it is.
     */
    fun boundaryNeedsSpace(left: Char?, right: Char?): Boolean {
        if (left == null || right == null) return false
        val leftChinese = isChinese(left)
        val rightChinese = isChinese(right)
        return when {
            leftChinese && isDigitOrLatin(right) -> true
            rightChinese && isDigitOrLatin(left) -> true
            else -> false
        }
    }

    /** Han ideographs, the scripts the reference calls 中文. */
    fun isChinese(char: Char): Boolean {
        val code = char.code
        return code in 0x3400..0x4DBF || // CJK Unified Ideographs Extension A
            code in 0x4E00..0x9FFF || // CJK Unified Ideographs
            code in 0xF900..0xFAFF || // CJK Compatibility Ideographs
            code in 0xD840..0xD87F // high surrogates of the Extension B..F planes
    }

    /** ASCII digits and letters, the scripts the reference calls 数字、英文. */
    fun isDigitOrLatin(char: Char): Boolean =
        char in '0'..'9' || char in 'a'..'z' || char in 'A'..'Z'
}
