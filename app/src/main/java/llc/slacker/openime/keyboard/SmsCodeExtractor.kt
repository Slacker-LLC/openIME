package llc.slacker.openime.keyboard

/**
 * Pulls a verification code out of a text message: a 4-8 digit run, preferably
 * the one next to a word such as 验证码. Messages without such a word give none,
 * so a delivery notice with an order number is not offered.
 */
internal object SmsCodeExtractor {
    private val KEYWORDS = Regex("验证码|校验码|动态码|确认码|安全码|验证代码|驗證碼|verification|verify|security code|one-time|otp|passcode|\\bcode\\b", RegexOption.IGNORE_CASE)
    private val DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    fun extract(body: String): String? {
        val keyword = KEYWORDS.find(body) ?: return null
        val runs = DIGITS.findAll(body).toList()
        if (runs.isEmpty()) return null
        // Nearest run to the keyword wins; ties go to the later one ("验证码 1234" vs "1234是验证码").
        return runs.minByOrNull { kotlin.math.abs(it.range.first - keyword.range.last) }?.value
    }
}
