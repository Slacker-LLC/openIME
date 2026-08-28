package llc.slacker.openime

/** Builds a bounded sherpa context string without logging personal phrases. */
object VoiceHotwordProvider {
    private const val MAX_HOTWORDS = 64
    private const val MAX_CODE_POINTS = 16

    fun current(): String = encode(
        PersonalizationRepository.hotwords(limit = MAX_HOTWORDS) +
            UserPhraseRepository.voiceHotwords(limit = MAX_HOTWORDS) +
            VoiceCorrectionRepository.hotwords(limit = 32),
    )

    internal fun encode(phrases: List<String>): String = phrases
        .asSequence()
        .map(::sanitize)
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MAX_HOTWORDS)
        // createStream() accepts slash-separated hotword lines. A moderate
        // per-token score avoids one personal term overwhelming normal ASR.
        .joinToString("/") { "$it :1.8" }

    private fun sanitize(value: String): String {
        val cleaned = value
            .trim()
            .replace(Regex("[\\r\\n/:]+"), " ")
            .replace(Regex("\\s+"), " ")
        if (cleaned.isBlank()) return ""
        val codePoints = cleaned.codePoints().toArray()
        if (codePoints.size !in 2..MAX_CODE_POINTS) return ""
        // The bundled bilingual model does not include bpe.vocab. Its dynamic
        // context path can therefore encode CJK terms reliably; ordinary
        // bilingual decoding continues to handle English without hotword bias.
        if (codePoints.none(::isCjk)) return ""
        return cleaned
    }

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x3400..0x4DBF ||
            codePoint in 0x4E00..0x9FFF ||
            codePoint in 0xF900..0xFAFF
}
