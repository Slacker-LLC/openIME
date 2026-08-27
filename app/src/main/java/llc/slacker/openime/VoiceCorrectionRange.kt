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
