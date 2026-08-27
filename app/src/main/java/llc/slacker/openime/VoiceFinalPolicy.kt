package llc.slacker.openime

internal data class VoiceFinalPlan(
    val setFinalText: Boolean,
    val finishComposing: Boolean,
    val composingAfter: Boolean,
)

/** Pure decision layer so final-only ASR callbacks remain regression-testable. */
internal object VoiceFinalPolicy {
    fun resolve(
        passwordField: Boolean,
        hadPartialComposition: Boolean,
        autoCommit: Boolean,
        finalText: String,
    ): VoiceFinalPlan {
        if (passwordField) return VoiceFinalPlan(false, false, false)
        val setFinal = finalText.isNotBlank()
        val hasComposition = hadPartialComposition || setFinal
        return VoiceFinalPlan(
            setFinalText = setFinal,
            finishComposing = autoCommit && hasComposition,
            composingAfter = !autoCommit && hasComposition,
        )
    }
}
