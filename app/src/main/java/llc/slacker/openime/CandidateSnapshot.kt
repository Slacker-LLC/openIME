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
     * Space/enter commit the rendered first candidate. When there is no
     * candidate chip, the visible pre-edit string itself remains the commit
     * target, matching the existing raw-composition fallback.
     */
    fun firstForCommit(
        currentGeneration: Long,
        currentComposition: String,
        currentMode: KeyboardMode,
    ): CandidateSnapshotEntry? {
        if (!matches(currentGeneration, currentComposition, currentMode)) return null
        return entries.firstOrNull()
            ?: composition.takeIf { it.isNotEmpty() }?.let(::CandidateSnapshotEntry)
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
        ): CandidateSnapshot = CandidateSnapshot(
            generation = generation,
            composition = composition,
            mode = mode,
            entries = candidates.map { text ->
                CandidateSnapshotEntry(
                    text = text,
                    nativeReference = nativeReferences[text],
                )
            },
        )
    }
}
