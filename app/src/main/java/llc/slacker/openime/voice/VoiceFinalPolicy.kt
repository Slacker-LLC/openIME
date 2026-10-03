package llc.slacker.openime.voice

internal data class VoiceFinalPlan(
    val setFinalText: Boolean,
    val finishComposing: Boolean,
    val composingAfter: Boolean,
    /** Password fields get the final text as one direct commit: no composing text, no correction tracking. */
    val commitDirect: Boolean = false,
)

/** Pure decision layer so final-only ASR callbacks remain regression-testable. */
internal object VoiceFinalPolicy {
    fun resolve(
        passwordField: Boolean,
        hadPartialComposition: Boolean,
        autoCommit: Boolean,
        finalText: String,
    ): VoiceFinalPlan {
        if (passwordField) {
            return VoiceFinalPlan(
                setFinalText = false,
                finishComposing = false,
                composingAfter = false,
                commitDirect = finalText.isNotBlank(),
            )
        }
        val setFinal = finalText.isNotBlank()
        val hasComposition = hadPartialComposition || setFinal
        return VoiceFinalPlan(
            setFinalText = setFinal,
            finishComposing = autoCommit && hasComposition,
            composingAfter = !autoCommit && hasComposition,
        )
    }
}
