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
 * CandidateEngine or candidate ordering rules. Rime is authoritative for
 * Chinese ranking; the local 9-key decoder provides only the immediate frame.
 */
class CandidatePipeline(
    private val engine: CandidateEngine,
) : CandidateResolver {
    /**
     * [pinyinPaths] keeps its historical name for Listener compatibility. For
     * PINYIN_9 it contains exactly one native Rime T9 code (digits plus
     * apostrophe boundaries). [displayPinyinPaths] contains the human-readable
     * ambiguity choices used only by the side filter UI.
     */
    data class NineKeyResolution(
        val preview: String,
        val pinyinPaths: List<String>,
        val candidates: List<String>,
        val displayPinyinPaths: List<String> = emptyList(),
    )

    private val nineKeyDecoder = NineKeyLocalDecoder(engine)

    override fun candidatesFor(
        mode: KeyboardMode,
        composition: String,
        fuzzy: Boolean,
    ): List<String> = when (mode) {
        KeyboardMode.PINYIN_26 -> engine.getCandidates(composition, fuzzy)
        KeyboardMode.ENGLISH_26 -> englishCandidates(composition)
        KeyboardMode.PINYIN_9 -> if (composition.length <= 32) {
            engine.getCandidates(composition, fuzzy)
        } else {
            emptyList()
        }
        KeyboardMode.DIGITS -> emptyList()
    }

    /**
     * English completion is advisory, but unlike Pinyin the space key is also
     * the normal acceptance gesture. Keep the engine's best completion first
     * so space accepts it, while retaining the exact typed text as a selectable
     * fallback candidate. Suggestions inherit ordinary lower/title/all-caps
     * casing so Shift/Caps Lock are not silently discarded.
     */
    private fun englishCandidates(composition: String): List<String> {
        if (composition.isEmpty()) return emptyList()
        val suggestions = engine.getEnglishCompletions(composition)
            .map { applyEnglishCase(composition, it) }
        return (suggestions + composition)
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CANDIDATES)
    }

    private fun applyEnglishCase(typed: String, suggestion: String): String {
        val letters = typed.filter(Char::isLetter)
        return when {
            letters.isNotEmpty() && letters.all(Char::isUpperCase) -> suggestion.uppercase()
            typed.firstOrNull()?.isUpperCase() == true &&
                typed.drop(1).filter(Char::isLetter).all(Char::isLowerCase) ->
                suggestion.replaceFirstChar { it.uppercase() }
            else -> suggestion
        }
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
            .take(NineKeyLocalDecoder.MAX_DIGITS)
        if (boundedDigits.isEmpty()) {
            NineKeyUiState.clear()
            return NineKeyResolution(
                preview = segmentPrefix,
                pinyinPaths = emptyList(),
                candidates = emptyList(),
                displayPinyinPaths = emptyList(),
            )
        }

        val nativeInput = NineKeyLocalDecoder.nativeCode(segmentPrefix, boundedDigits)
        val effectivePreferred = preferredSuffix
            ?: NineKeyUiState.preferredSuffixFor(nativeInput, segmentPrefix)
        val local = nineKeyDecoder.resolve(
            digits = boundedDigits,
            preferredSuffix = effectivePreferred,
            fuzzy = fuzzy,
        )
        val preview = segmentPrefix + local.previewSuffix
        val displayPaths = buildList {
            // An incomplete-but-valid continuation (for example nia after
            // selecting ni and typing one more digit) must stay visible in the
            // side filter even before it becomes a complete dictionary syllable.
            if (NineKeyLocalDecoder.digitsForPinyin(local.previewSuffix) == boundedDigits) {
                add(preview)
            }
            addAll(
                local.pinyinSuffixes
                    // An unsegmented 9-key display path must remain a direct
                    // filterable spelling. Explicit boundaries are rendered
                    // only when the already-committed prefix owns them.
                    .filter { segmentPrefix.isNotEmpty() || it.none { ch -> ch.isWhitespace() || ch == '\'' || ch == '|' } }
                    .map { segmentPrefix + it },
            )
        }.distinct()

        val candidates = if (segmentPrefix.isEmpty()) {
            local.candidates
        } else {
            // A suffix-only candidate must never replace the entire composition.
            // Re-resolve complete explicitly segmented paths, but interleave
            // equal ranks so one guessed path cannot occupy all 96 slots.
            roundRobin(
                displayPaths.map { path ->
                    engine.getCandidates(path, fuzzy)
                        .filter(::isChineseDisplayCandidate)
                        .take(PER_PATH_CANDIDATES)
                },
                MAX_CANDIDATES,
            )
        }
            .filter(::isChineseDisplayCandidate)
            .distinct()
            .take(MAX_CANDIDATES)

        NineKeyUiState.remember(nativeInput, displayPaths, segmentPrefix)
        NineKeyFallbackRegistry.remember(nativeInput, candidates)
        return NineKeyResolution(
            preview = preview,
            pinyinPaths = listOfNotNull(nativeInput),
            candidates = candidates,
            displayPinyinPaths = displayPaths,
        )
    }

    private fun roundRobin(batches: List<List<String>>, limit: Int): List<String> {
        if (batches.isEmpty() || limit <= 0) return emptyList()
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>()
        val max = batches.maxOfOrNull { it.size } ?: 0
        for (rank in 0 until max) {
            batches.forEach { batch ->
                val value = batch.getOrNull(rank) ?: return@forEach
                if (seen.add(value)) out += value
                if (out.size >= limit) return out
            }
        }
        return out
    }

    private fun isChineseDisplayCandidate(value: String): Boolean =
        value.isNotBlank() && value.none(Char::isDigit) && value.any { it.code > 0x7f }

    companion object {
        private const val MAX_CANDIDATES = 96
        private const val PER_PATH_CANDIDATES = 24

        fun nineKeyDigitsFor(pinyin: String): String? =
            NineKeyLocalDecoder.digitsForPinyin(pinyin)
    }
}
