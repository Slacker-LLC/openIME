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
        lockPreferred: Boolean = false,
    ): CandidatePipeline.NineKeyResolution

    /** Syllables of [candidate] if it spells exactly all of [digits]; null otherwise. */
    fun nineKeyReadingFor(digits: String, candidate: String): List<String>? = null

    /** Pinyin syllables that can start [digits], best first, for the left rail. */
    fun nineKeySyllablesFor(digits: String, preferred: String?): List<String> = emptyList()

    fun nineKeyPathsFor(code: String?): List<String>
    fun selectedNineKeyPathFor(code: String?): String?
    fun selectNineKeyPath(code: String, path: String)
}

/**
 * Service-owned local candidate pipeline.
 *
 * The View may keep transient key/composition buffers, but it must not own a
 * CandidateEngine or candidate ordering rules. Rime is authoritative for
 * Chinese ranking; the local 9-key decoder provides only the immediate frame.
 */
class CandidatePipeline internal constructor(
    private val engine: CandidateEngine,
    private val nineKeyUiState: NineKeyUiState = NineKeyUiState(),
    private val nineKeyFallbackRegistry: NineKeyFallbackRegistry = NineKeyFallbackRegistry(),
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

    private val nineKeyDecoder: NineKeyLocalDecoder = run {
        val startedAt = System.nanoTime()
        NineKeyLocalDecoder(engine).also {
            NineKeyPerformanceTrace.recordDecoderConstruction(
                elapsedNs = System.nanoTime() - startedAt,
                threadName = Thread.currentThread().name,
            )
        }
    }

    override fun candidatesFor(
        mode: KeyboardMode,
        composition: String,
        fuzzy: Boolean,
    ): List<String> = when (mode) {
        KeyboardMode.PINYIN_26 -> pinyin26Candidates(composition, fuzzy)
        KeyboardMode.ENGLISH_26 -> englishCandidates(composition)
        KeyboardMode.PINYIN_9 -> if (composition.length <= 32) {
            engine.getCandidates(composition, fuzzy)
        } else {
            emptyList()
        }
        KeyboardMode.DIGITS -> emptyList()
    }

    private fun pinyin26Candidates(
        composition: String,
        fuzzy: Boolean,
    ): List<String> {
        val normal = engine.getCandidates(composition, fuzzy)
        val meaningfulNormal = normal.count { candidate ->
            candidate != composition && candidate.any { it.code > 0x7f }
        }
        if (meaningfulNormal >= TYPO_CORRECTION_MIN_NORMAL_CANDIDATES) {
            return normal.take(MAX_CANDIDATES)
        }

        return (normal + engine.getAdjacentKeyCorrections(composition, fuzzy))
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CANDIDATES)
    }

    /**
     * English completion is advisory. The exact text the user typed must stay
     * candidate #1 because punctuation, space and mode changes commit that
     * entry automatically. Suggestions inherit ordinary lower/title/all-caps
     * casing so Shift/Caps Lock are not silently discarded.
     */
    private fun englishCandidates(composition: String): List<String> {
        if (composition.isEmpty()) return emptyList()
        val suggestions = engine.getEnglishCompletions(composition)
            .map { applyEnglishCase(composition, it) }
        return (listOf(composition) + suggestions)
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
        lockPreferred: Boolean,
    ): NineKeyResolution {
        val boundedDigits = digits
            .filter { it in '2'..'9' }
            .take(NineKeyLocalDecoder.MAX_DIGITS)
        if (boundedDigits.isEmpty()) {
            nineKeyUiState.clear()
            return NineKeyResolution(
                preview = segmentPrefix,
                pinyinPaths = emptyList(),
                candidates = emptyList(),
                displayPinyinPaths = emptyList(),
            )
        }

        // A syllable the user tapped on the rail is a decision, not a guess:
        // hand Rime its letters so zhong/xiong (same digits) stay distinct and
        // the candidates agree with the pinyin shown.
        val locked = preferredSuffix
            ?.lowercase()
            ?.trim()
            ?.takeIf { lockPreferred && NineKeyLocalDecoder.digitsForPinyin(it) == boundedDigits }
        val nativeInput = if (locked != null) {
            NineKeyLocalDecoder.nativeCode(segmentPrefix + locked, "", lockLetters = true)
        } else {
            NineKeyLocalDecoder.nativeCode(segmentPrefix, boundedDigits, lockLetters = true)
        }
        val effectivePreferred = preferredSuffix
            ?: nineKeyUiState.preferredSuffixFor(nativeInput, segmentPrefix)
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

        nineKeyUiState.remember(nativeInput, displayPaths, segmentPrefix)
        nineKeyFallbackRegistry.remember(nativeInput, candidates)
        return NineKeyResolution(
            preview = preview,
            pinyinPaths = listOfNotNull(nativeInput),
            candidates = candidates,
            displayPinyinPaths = displayPaths,
        )
    }

    internal fun nineKeyFallbackCandidatesFor(code: String): List<String> =
        nineKeyFallbackRegistry.candidatesFor(code)

    override fun nineKeySyllablesFor(digits: String, preferred: String?): List<String> =
        nineKeyDecoder.syllableOptions(digits, preferred)

    override fun nineKeyReadingFor(digits: String, candidate: String): List<String>? =
        nineKeyDecoder.readingFor(digits, candidate)

    override fun nineKeyPathsFor(code: String?): List<String> =
        nineKeyUiState.pathsFor(code)

    override fun selectedNineKeyPathFor(code: String?): String? =
        nineKeyUiState.selectedPathFor(code)

    override fun selectNineKeyPath(code: String, path: String) {
        nineKeyUiState.select(code, path)
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
        private const val TYPO_CORRECTION_MIN_NORMAL_CANDIDATES = 8

        fun nineKeyDigitsFor(pinyin: String): String? =
            NineKeyLocalDecoder.digitsForPinyin(pinyin)
    }
}
