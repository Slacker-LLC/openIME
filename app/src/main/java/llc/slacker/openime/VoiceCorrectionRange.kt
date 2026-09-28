package llc.slacker.openime

/** Absolute editor range used to track one committed ASR result across window shifts. */
internal data class VoiceCorrectionRange(
    val original: String,
    val startAbsolute: Int,
    val endAbsolute: Int,
)

internal fun voiceCorrectionRange(
    original: String,
    snapshot: InputConnectionGateway.AbsoluteCursorSnapshot,
): VoiceCorrectionRange? {
    if (original.isBlank()) return null
    val end = snapshot.cursorAbsolute
    val start = end - original.length
    if (start < 0) return null
    if (snapshot.textInAbsoluteRange(start, end) != original) return null
    return VoiceCorrectionRange(
        original = original,
        startAbsolute = start,
        endAbsolute = end,
    )
}

internal fun correctedVoiceText(
    range: VoiceCorrectionRange,
    snapshot: InputConnectionGateway.AbsoluteCursorSnapshot,
    maxLength: Int = 128,
): String? {
    val end = snapshot.cursorAbsolute
    if (end < range.startAbsolute) return null
    if (end - range.startAbsolute > maxLength) return null
    return snapshot.textInAbsoluteRange(range.startAbsolute, end)
}


/**
 * Owns the lifecycle of one learnable voice-correction pair.
 *
 * The tracker stores only editor-relative correction state. Cursor snapshots,
 * privacy eligibility and persistence remain injected concrete callbacks.
 */
internal class VoiceCorrectionTracker(
    private val snapshot: () -> InputConnectionGateway.AbsoluteCursorSnapshot?,
    private val learningAllowed: () -> Boolean,
    private val record: (String, String) -> Unit,
) {
    private data class Pending(
        val range: VoiceCorrectionRange,
        var edited: Boolean = false,
    )

    private var pending: Pending? = null

    fun clear() {
        pending = null
    }

    fun begin(original: String) {
        if (!learningAllowed() || original.isBlank()) {
            clear()
            return
        }
        val current = snapshot() ?: run {
            clear()
            return
        }
        pending = voiceCorrectionRange(original, current)?.let(::Pending)
    }

    fun noteBackspace() {
        val currentPending = pending ?: return
        val cursorAbsolute = snapshot()?.cursorAbsolute ?: run {
            clear()
            return
        }
        if (
            cursorAbsolute in
            (currentPending.range.startAbsolute + 1)..currentPending.range.endAbsolute
        ) {
            currentPending.edited = true
        } else if (!currentPending.edited) {
            clear()
        }
    }

    fun noteReplacementInput() {
        val currentPending = pending ?: return
        // Typing at the end without first deleting part of the ASR result is
        // ordinary continuation, not a correction pair.
        if (!currentPending.edited) clear()
    }

    fun finalizeIfNeeded() {
        val currentPending = pending ?: return
        clear()
        if (!currentPending.edited || !learningAllowed()) return
        val current = snapshot() ?: return
        val corrected = correctedVoiceText(currentPending.range, current) ?: return
        record(currentPending.range.original, corrected)
    }
}
