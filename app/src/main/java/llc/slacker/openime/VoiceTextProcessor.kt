package llc.slacker.openime

/**
 * Fast, deterministic post-processing kept between ASR and InputConnection.
 *
 * The APK currently ships a streaming recognition model without a separate
 * punctuation model. This processor still handles the common spoken
 * punctuation words, whitespace and terminal punctuation locally, while its
 * small API leaves room for a stronger bundled punctuation/correction model.
 */
object VoiceTextProcessor {
    private val chinesePunctuation = linkedMapOf(
        "感叹号" to "！",
        "问号" to "？",
        "分号" to "；",
        "冒号" to "：",
        "省略号" to "……",
        "句号" to "。",
        "逗号" to "，",
        "顿号" to "、",
        "破折号" to "——",
        "左括号" to "（",
        "右括号" to "）",
    )

    private val englishPunctuation = linkedMapOf(
        "question mark" to "?",
        "exclamation mark" to "!",
        "exclamation point" to "!",
        "semicolon" to ";",
        "colon" to ":",
        "full stop" to ".",
        "period" to ".",
        "comma" to ",",
        "open parenthesis" to "(",
        "close parenthesis" to ")",
    )

    fun process(raw: String, languageTag: String): String {
        var text = raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return ""

        if (languageTag.startsWith("zh", ignoreCase = true)) {
            chinesePunctuation.forEach { (spoken, mark) ->
                text = text.replace(spoken, mark)
            }
            // Chinese characters do not need an ASR-inserted space between
            // them, but keep spaces in embedded Latin/number phrases.
            text = text.replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
            text = text.replace(Regex("\\s+([，。！？；：、）》】』])"), "$1")
            text = text.replace(Regex("([，。！？；：、（《【『])\\s+"), "$1")
            text = collapsePunctuation(text)
            if (text.length >= 4 && text.last() !in "。！？！？…") {
                text += if (text.endsWith("吗") || text.endsWith("呢") || text.endsWith("么")) "？" else "。"
            }
        } else {
            englishPunctuation.forEach { (spoken, mark) ->
                text = text.replace(Regex("(?i)(?<![A-Za-z])${Regex.escape(spoken)}(?![A-Za-z])"), mark)
            }
            text = text.replace(Regex("\\s+([,.!?;:)])"), "$1")
            text = collapsePunctuation(text)
        }
        return text
    }

    private fun collapsePunctuation(value: String): String = value
        .replace(Regex("[。]{2,}"), "。")
        .replace(Regex("[！!]{2,}"), "！")
        .replace(Regex("[？?]{2,}"), "？")
        .replace(Regex("[，,]{2,}"), "，")
}
