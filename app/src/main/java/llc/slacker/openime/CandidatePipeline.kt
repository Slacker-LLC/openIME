package llc.slacker.openime

/** Capability exposed by the service to a thin keyboard renderer. */
interface CandidateResolver {
    fun candidatesFor(
        mode: KeyboardMode,
        composition: String,
        fuzzy: Boolean,
    ): List<String>

    fun resolveNineKey(
        digits: String,
        segmentPrefix: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): CandidatePipeline.NineKeyResolution
}

/**
 * Service-owned local candidate pipeline.
 *
 * The View may keep transient key/composition buffers, but it must not own a
 * CandidateEngine or candidate ordering rules. Keeping the engine behind this
 * boundary makes the service the single authoritative owner for 26-key,
 * English, T9, 9-key, and association fallback semantics.
 */
class CandidatePipeline(
    private val engine: CandidateEngine,
) : CandidateResolver {
    data class NineKeyResolution(
        val preview: String,
        val pinyinPaths: List<String>,
        val candidates: List<String>,
    )

    override fun candidatesFor(
        mode: KeyboardMode,
        composition: String,
        fuzzy: Boolean,
    ): List<String> = when (mode) {
        KeyboardMode.PINYIN_26 -> engine.getCandidates(composition, fuzzy)
        KeyboardMode.ENGLISH_26 -> engine.getEnglishCompletions(composition)
        KeyboardMode.PINYIN_9 -> if (composition.length <= 32) {
            engine.getCandidates(composition, fuzzy)
        } else {
            emptyList()
        }
        KeyboardMode.ENGLISH_T9 -> engine.getT9EnglishCandidates(composition)
        KeyboardMode.DIGITS -> emptyList()
    }

    fun associationsFor(context: String): List<String> = engine.getAssociations(context)

    override fun resolveNineKey(
        digits: String,
        segmentPrefix: String,
        preferredSuffix: String?,
        fuzzy: Boolean,
    ): NineKeyResolution {
        val boundedDigits = digits
            .filter { it in '2'..'9' }
            .take(CandidateEngine.MAX_NINE_KEY_DIGITS)
        if (boundedDigits.isEmpty()) {
            return NineKeyResolution(
                preview = segmentPrefix,
                pinyinPaths = listOf(segmentPrefix).filter { it.isNotBlank() },
                candidates = emptyList(),
            )
        }

        val result = engine.get9KeyCandidates(boundedDigits)
        val fallbackPath = boundedDigits.mapNotNull { digit ->
            ImeData.keypad9Map[digit.toString()]
                ?.firstOrNull { it.length == 1 && it[0] in 'a'..'z' }
        }.joinToString("")
        val stableSuffix = preferredSuffix
            ?.lowercase()
            ?.takeIf { suffix -> nineKeyDigitsFor(suffix) == boundedDigits }
        val pinyinPaths = (listOfNotNull(stableSuffix) + result.pinyins)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { segmentPrefix + it }
            .distinct()
            .take(8)
            .toList()
            .ifEmpty {
                listOf(segmentPrefix + fallbackPath).filter { it.isNotBlank() }
            }
        val preview = pinyinPaths.firstOrNull().orEmpty()
        val localCandidates = candidatesFor(
            mode = KeyboardMode.PINYIN_9,
            composition = preview,
            fuzzy = fuzzy,
        )
        val candidates = (
            if (segmentPrefix.isEmpty()) {
                result.candidates + localCandidates
            } else {
                localCandidates + result.candidates
            }
            )
            .filter { it.isNotEmpty() && it.none(Char::isDigit) }
            .distinct()
            .take(96)

        return NineKeyResolution(
            preview = preview,
            pinyinPaths = pinyinPaths,
            candidates = candidates,
        )
    }

    private fun nineKeyDigitsFor(pinyin: String): String? {
        val digits = StringBuilder(pinyin.length)
        pinyin.forEach { ch ->
            digits.append(
                when (ch) {
                    in 'a'..'c' -> '2'
                    in 'd'..'f' -> '3'
                    in 'g'..'i' -> '4'
                    in 'j'..'l' -> '5'
                    in 'm'..'o' -> '6'
                    in 'p'..'s' -> '7'
                    in 't'..'v' -> '8'
                    in 'w'..'z' -> '9'
                    else -> return null
                },
            )
        }
        return digits.toString()
    }
}
