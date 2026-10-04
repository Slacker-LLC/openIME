package llc.slacker.openime.voice

internal data class VoiceTextProcessingPolicy(
    val autoTerminalPunctuation: Boolean,
    val stripFillers: Boolean = false,
    val punctuationAsSpace: Boolean = false,
)

/**
 * Fast, deterministic post-processing kept between ASR and InputConnection.
 *
 * The streaming runtime restores punctuation before this final formatting step.
 * Spoken punctuation is interpreted conservatively and
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
        val policy = VoiceTextProcessingPolicy(
            autoTerminalPunctuation = VoiceEditorContext.allowNaturalPunctuation(),
            stripFillers = VoiceEditorContext.stripFillers(),
            punctuationAsSpace = VoiceEditorContext.punctuationAsSpace(),
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

        val original = text
        if (policy.stripFillers) {
            text = stripFillers(text)
            // An utterance that is nothing but a hesitation ("嗯") is a real
            // answer, not noise; keep it as spoken.
            if (text.isBlank()) text = original
        }

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
                text.last() !in "。！？….!?"
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
        if (policy.punctuationAsSpace) text = punctuationToSpaces(text)
        return text
    }

    /**
     * Hesitation sounds carry no content. 嗯 and 呃 are removed wherever they
     * occur (the one real word containing 呃, 呃逆, is kept); 额 is also a
     * component of 额度 / 金额, so it only goes when the recogniser set it off
     * as a word of its own. English um / uh / er / hmm go only as whole words.
     */
    private fun stripFillers(value: String): String {
        var text = value
        text = text.replace(Regex("[嗯呃]+(?!逆)"), "")
        text = text.replace(Regex("(?<![\\p{L}\\p{N}])额+(?![\\p{L}\\p{N}])"), "")
        text = text.replace(
            Regex("(?i)(?<![\\p{L}\\p{N}'])(?:u[hm]+|erm+|er|hm+)(?![\\p{L}\\p{N}'])[,.]?"),
            "",
        )
        // Removing a word between two punctuation marks leaves "，，" or a
        // stray leading mark; tidy those and any doubled spaces.
        text = text.replace(Regex("\\s+"), " ").trim()
        text = text.replace(Regex("^[，,、]+"), "")
        return collapsePunctuation(text).trim()
    }

    /** Clause punctuation becomes one space; a trailing mark just disappears. */
    private fun punctuationToSpaces(value: String): String {
        var text = value
            .replace(Regex("[，。！？；：、]+|…+|—{2,}"), " ")
            // ASCII clause marks next to Chinese are punctuation too.
            .replace(Regex("(?<=[\\u4e00-\\u9fff])[,.!?;:]+|[,.!?;:]+(?=[\\u4e00-\\u9fff])"), " ")
            // Latin marks only when they end a word, so 3.5 and a.b stay intact.
            .replace(Regex("(?<=\\S)[,.!?;:]+(?=\\s|$)"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
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
        .replace(Regex("[！!]{2,}")) { it.value.take(1) }
        .replace(Regex("[？?]{2,}")) { it.value.take(1) }
        .replace(Regex("[，,]{2,}")) { it.value.take(1) }
}
