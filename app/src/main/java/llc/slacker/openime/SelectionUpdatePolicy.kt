package llc.slacker.openime

/**
 * Decide whether a host/editor selection callback invalidates the IME's
 * current pre-edit composition.
 *
 * Android reports the active composing range through candidatesStart/end.
 * Selection changes that stay inside that range are normal composing/cursor
 * updates. Moving outside it means the editor/user has taken ownership of the
 * cursor and the old IME composition must not survive at the new position.
 */
internal fun shouldClearCompositionForSelectionUpdate(
    hasComposition: Boolean,
    oldSelStart: Int,
    oldSelEnd: Int,
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Boolean {
    if (!hasComposition) return false
    if (newSelStart < 0 || newSelEnd < 0) return false

    val selectionChanged = oldSelStart != newSelStart || oldSelEnd != newSelEnd
    if (!selectionChanged) return false

    if (candidatesStart < 0 || candidatesEnd < 0) return true
    val composingStart = minOf(candidatesStart, candidatesEnd)
    val composingEnd = maxOf(candidatesStart, candidatesEnd)
    return newSelStart !in composingStart..composingEnd ||
        newSelEnd !in composingStart..composingEnd
}
