package llc.slacker.openime.voice

/**
 * What the voice layer needs to know about the active editor, supplied by the
 * input method service. It keeps the recognition code from depending on the
 * service or on editor classes.
 */
internal object VoiceEditorContext {
    /** Whether voice text may get sentence punctuation added in the current editor. */
    @Volatile
    var allowNaturalPunctuation: () -> Boolean = { true }
}
