package llc.slacker.openime

internal data class VoiceTextProcessingPolicy(
    val autoTerminalPunctuation: Boolean,
)

/**
 * Fast, deterministic post-processing kept between ASR and InputConnection.
 *
 * The APK currently ships a streaming recognition model without a separate
 * punctuation model. Spoken punctuation is interpreted conservatively and
 * terminal punctuation is enabled only for prose-like EditorInfo contexts.
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
        val service = LocalVoiceImeService.activeInstance
        val policy = VoiceTextProcessingPolicy(
            autoTerminalPunctuation = service?.let {
                EditorInfoAdapter.allowNaturalLanguageVoicePunctuation(it.currentInputEditorInfo)
            } ?: true,
        )
        return process(raw, languageTag, policy)
    }

    internal fun process(
        raw: String,
        languageTag: String,
        policy: VoiceTextProcessingPolicy,
    ): String {
        var text = raw
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return ""

        if (languageTag.startsWith("zh", ignoreCase = true)) {
            text = replaceChineseSpokenPunctuation(text)
            // Chinese characters do not need an ASR-inserted space between
            // them, but keep spaces in embedded Latin/number phrases.
            text = text.replace(Regex("(?<=[\\u4e00-\\u9fff])\\s+(?=[\\u4e00-\\u9fff])"), "")
            text = text.replace(Regex("\\s+([，。！？；：、）》】』])"), "$1")
            text = text.replace(Regex("([，。！？；：、（《【『])\\s+"), "$1")
            text = collapsePunctuation(text)
            if (
                policy.autoTerminalPunctuation &&
                text.length >= 4 &&
                text.last() !in "。！？…"
            ) {
                text += if (text.endsWith("吗") || text.endsWith("呢") || text.endsWith("么")) {
                    "？"
                } else {
                    "。"
                }
            }
        } else {
            englishPunctuation.forEach { (spoken, mark) ->
                text = text.replace(
                    Regex("(?i)(?<![A-Za-z])${Regex.escape(spoken)}(?![A-Za-z])"),
                    mark,
                )
            }
            text = text.replace(Regex("\\s+([,.!?;:)])"), "$1")
            text = collapsePunctuation(text)
        }
        return text
    }

    /**
     * Chinese has no reliable regex word boundary. Only standalone/pause-delimited
     * commands are replaced in the middle of an utterance. A trailing command is
     * also accepted because it is a common explicit dictation form. This avoids
     * turning ordinary text such as “我喜欢句号这个名字” into punctuation.
     */
    private fun replaceChineseSpokenPunctuation(value: String): String {
        var text = value
        chinesePunctuation.forEach { (spoken, mark) ->
            val bounded = Regex(
                "(?<![\\p{L}\\p{N}])${Regex.escape(spoken)}(?![\\p{L}\\p{N}])",
            )
            text = text.replace(bounded, mark)
            if (text.length > spoken.length && text.endsWith(spoken)) {
                text = text.dropLast(spoken.length) + mark
            }
        }
        return text
    }

    private fun collapsePunctuation(value: String): String = value
        .replace(Regex("[。]{2,}"), "。")
        .replace(Regex("[！!]{2,}"), "！")
        .replace(Regex("[？?]{2,}"), "？")
        .replace(Regex("[，,]{2,}"), "，")
}
