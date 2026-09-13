package llc.slacker.openime

/** One candidate exactly as it was rendered, optionally carrying its Rime identity. */
internal data class CandidateSnapshotEntry(
    val text: String,
    val nativeReference: NativeCandidateReference? = null,
)

/**
 * Immutable identity for one rendered candidate generation.
 *
 * Commit paths must consume this snapshot instead of re-querying Rime. That
 * guarantees the text the user sees is the text that is committed, while a
 * stale UI event cannot target a newer composition generation.
 */
internal data class CandidateSnapshot(
    val generation: Long,
    val composition: String,
    val mode: KeyboardMode,
    val entries: List<CandidateSnapshotEntry>,
) {
    val visibleCandidates: List<String>
        get() = entries.map { it.text }

    /**
     * Space/enter commit the rendered first candidate. PINYIN_9 deliberately
     * does not fall back to committing the guessed/local preview as raw Latin
     * text when no real candidate exists yet.
     */
    fun firstForCommit(
        currentGeneration: Long,
        currentComposition: String,
        currentMode: KeyboardMode,
    ): CandidateSnapshotEntry? {
        if (!matches(currentGeneration, currentComposition, currentMode)) return null
        entries.firstOrNull()?.let { return it }
        if (mode == KeyboardMode.PINYIN_9) return null
        return composition.takeIf { it.isNotEmpty() }?.let(::CandidateSnapshotEntry)
    }

    fun candidateForCommit(
        candidate: String,
        currentGeneration: Long,
        currentComposition: String,
        currentMode: KeyboardMode,
    ): CandidateSnapshotEntry? =
        if (matches(currentGeneration, currentComposition, currentMode)) {
            entries.firstOrNull { it.text == candidate }
        } else {
            null
        }

    fun matches(
        currentGeneration: Long,
        currentComposition: String,
        currentMode: KeyboardMode,
    ): Boolean =
        generation == currentGeneration &&
            composition == currentComposition &&
            mode == currentMode

    companion object {
        fun rendered(
            generation: Long,
            composition: String,
            mode: KeyboardMode,
            candidates: List<String>,
            nativeReferences: Map<String, NativeCandidateReference> = emptyMap(),
        ): CandidateSnapshot {
            val deferredInput = deferredInputFor(composition, mode)
            return CandidateSnapshot(
                generation = generation,
                composition = composition,
                mode = mode,
                entries = candidates.map { text ->
                    CandidateSnapshotEntry(
                        text = text,
                        nativeReference = nativeReferences[text]
                            ?: deferredInput?.let { input ->
                                NativeCandidateReference.deferred(input, text)
                            },
                    )
                },
            )
        }

        private fun deferredInputFor(composition: String, mode: KeyboardMode): String? = when (mode) {
            KeyboardMode.PINYIN_26 -> RimeInputNormalizer.normalize(composition).ifBlank { null }
            KeyboardMode.PINYIN_9 -> nineKeyCodeFromPreview(composition)
            else -> null
        }

        /** Convert the visible local Pinyin preview back to the exact T9 code. */
        private fun nineKeyCodeFromPreview(composition: String): String? {
            if (composition.isBlank()) return null
            val out = StringBuilder(composition.length)
            composition.lowercase().forEach { ch ->
                when {
                    ch in 'a'..'z' || ch == 'ü' -> {
                        val digit = NineKeyLocalDecoder.digitsForPinyin(ch.toString()) ?: return null
                        out.append(digit)
                    }
                    ch in '2'..'9' -> out.append(ch)
                    ch == '|' || ch == '\'' || ch.isWhitespace() -> {
                        if (out.isNotEmpty() && out.last() != '\'') out.append('\'')
                    }
                    else -> return null
                }
            }
            return out.toString().trim('\'').ifBlank { null }
        }
    }
}
