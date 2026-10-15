package llc.slacker.openime.keyboard

/** Local, conservative extraction: a code must be directly attached to a verification label. */
internal object SmsCodeExtractor {
    private val KEYWORDS = Regex(
        "验证码|校验码|动态码|确认码|安全码|验证代码|驗證碼|驗證代碼|動態密碼|动态密码|随机码|登录码|登入碼|授权码|" +
            "\\b(?:(?:verification|authentication|security|login|sign[ -]?in|confirmation)\\s+code|" +
            "one[ -]time\\s+(?:code|password|passcode)|otp|passcode|code)\\b",
        RegexOption.IGNORE_CASE,
    )
    // Consume whole tokens (including grouped numbers) before validating length. In particular,
    // never turn a phone number or AB123456CD into the numeric substring 123456.
    private val TOKENS = Regex(
        "(?<![A-Za-z0-9_@/+\\-])(?<![A-Za-z0-9]\\.)" +
            "(?:[0-9]+(?:[ \\u00a0\\u202f\\-][0-9]+)+|[A-Za-z0-9]+)" +
            "(?![A-Za-z0-9_@/]|\\.[A-Za-z0-9]|-[A-Za-z0-9])",
    )
    private const val PUNCTUATION = "[\\s:=,#\\[\\]【】()（）\"'“”‘’，、]*"
    private val AFTER_LABEL = Regex(
        "$PUNCTUATION(?:(?:is|are)\\b|(?:为|為|是)(?:您(?:的)?|你(?:的)?)?)?$PUNCTUATION",
        RegexOption.IGNORE_CASE,
    )
    private val BEFORE_LABEL = Regex(
        "[\\s\\[\\]【】()（）\"'“”‘’]*(?:(?:is|as)\\s+(?:(?:your|the|a)\\s+)?|(?:是|为|為)(?:您的|你的|本次|此次)?)?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val NON_VERIFICATION_LABEL = Regex(
        "(?:order|tracking|parcel|shipment|booking|product|promo|coupon|discount|postal|zip|area|" +
            "currency|error|reference|transaction|invoice|phone|telephone)\\s*(?:number\\s*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val DATE = Regex("(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])")
    private val NUMBER_UNIT = Regex(
        "^\\s*(?:[年月日元块塊角%‰]|(?:USD|CNY|RMB|EUR|dollars?|yuan|minutes?|seconds?|hours?)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val STATUS_WORDS = setOf(
        "CODE", "YOUR", "THIS", "THAT", "VALID", "INVALID", "EXPIRED", "EXPIRES", "EXPIRY", "SENT",
        "SEND", "PLEASE", "ENTER", "READY", "HERE", "WITH", "FROM", "LOGIN", "SIGNIN", "ACCESS",
        "SECURITY", "PASSWORD", "PASSCODE", "CONFIRM", "VERIFY", "VERIFIED", "REQUIRED", "MINUTES",
    )

    fun extract(body: String): String? {
        // SMS senders also use full-width digits/letters and punctuation. Keep letter case.
        val text = body.map { char ->
            when (char) {
                in '！'..'～' -> (char.code - 0xFEE0).toChar()
                '　' -> ' '
                else -> char
            }
        }.joinToString("")
        val labels = KEYWORDS.findAll(text).filterNot { label ->
            label.value.equals("code", ignoreCase = true) &&
                NON_VERIFICATION_LABEL.containsMatchIn(text.substring(0, label.range.first))
        }.toList()
        if (labels.isEmpty()) return null
        val codes = TOKENS.findAll(text).mapNotNull { token ->
            val code = normalizeCode(token.value) ?: return@mapNotNull null
            if (code.all(Char::isDigit) && (DATE.matches(code) ||
                    NUMBER_UNIT.containsMatchIn(text.substring(token.range.last + 1)))) return@mapNotNull null
            val attached = labels.any { label ->
                when {
                    label.range.last < token.range.first -> {
                        val gap = text.substring(label.range.last + 1, token.range.first)
                        gap.length <= 32 && AFTER_LABEL.matches(gap)
                    }
                    token.range.last < label.range.first -> {
                        val gap = text.substring(token.range.last + 1, label.range.first)
                        gap.length <= 32 && BEFORE_LABEL.matches(gap)
                    }
                    else -> false
                }
            }
            code.takeIf { attached }
        }.distinct().toList()
        // Two distinct explicitly labelled codes are ambiguous; do not guess which one to type.
        return codes.singleOrNull()
    }

    private fun normalizeCode(token: String): String? {
        val groups = token.split(' ', ' ', ' ', '-')
        if (groups.size > 1 && (groups.any { part -> part.any { !it.isDigit() } } ||
                groups.any { it.length != groups.first().length })) return null
        val code = groups.joinToString("")
        if (code.length !in 4..8) return null
        if (code.any(Char::isDigit)) return code
        // Pure-letter codes need uppercase to avoid treating ordinary English after a label as a code.
        return code.takeIf { it.all { char -> char in 'A'..'Z' } && it !in STATUS_WORDS }
    }
}
