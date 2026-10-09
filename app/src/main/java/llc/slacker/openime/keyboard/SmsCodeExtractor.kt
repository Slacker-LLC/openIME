package llc.slacker.openime.keyboard

/**
 * Pulls a verification code out of a text message: a 4-8 digit run, preferably
 * the one next to a word such as 验证码. Messages without such a word give none,
 * so a delivery notice with an order number is not offered.
 */
internal object SmsCodeExtractor {
    private val KEYWORDS = Regex("验证码|校验码|动态码|确认码|安全码|验证代码|驗證碼|verification|verify|security code|one-time|\\botp\\b|passcode|动态密码|随机码|登录码|授权码|\\bcode\\b", RegexOption.IGNORE_CASE)
    /** 8 digits that read as yyyyMMdd are a date, not a code. */
    private val DATE = Regex("(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])")
    private val DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d|[年月日.]\\d)(?![年月日])")

    fun extract(body: String): String? {
        val keywords = KEYWORDS.findAll(body).map { it.range }.toList()
        if (keywords.isEmpty()) return null
        val runs = DIGITS.findAll(body).filterNot { DATE.matches(it.value) }.toList()
        if (runs.isEmpty()) return null
        // The run with the smallest gap to the keyword wins; the gap is between the two spans,
        // so "1234是验证码" and "验证码1234" count alike.
        return runs.minByOrNull { run -> keywords.minOf { gap(run.range, it) } }?.value
    }

    private fun gap(a: IntRange, b: IntRange): Int = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }
}
