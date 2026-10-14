package llc.slacker.openime.voice

/**
 * Decides when the microphone level reads as the start of a syllable.
 *
 * The voice panel's character hops once per syllable it hears, so the level
 * stream that the recognizer already reports has to be reduced to onsets. A
 * chunk counts as an onset when it is above the silence floor and clearly above
 * the chunk before it, which is what the first frame of a syllable looks like;
 * a held vowel stays flat and never hops twice.
 *
 * The policy is pure - no clock, no view - so the thresholds can be tested as
 * rules rather than by watching the character.
 */
internal class VoiceFaceLevelPolicy(
    private val floor: Float = FLOOR,
    private val rise: Float = RISE,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private var previous = 0f
    private var lastOnset = Long.MIN_VALUE

    /**
     * Whether the level of a chunk read at [nowMs] opens a syllable.
     *
     * [nowMs] is a monotonic stamp; the first onset is always allowed.
     */
    fun onLevel(level: Float, nowMs: Long): Boolean {
        val value = level.coerceIn(0f, 1f)
        val previousValue = previous
        previous = value
        if (value < floor) return false
        if (value < previousValue * rise) return false
        if (lastOnset != Long.MIN_VALUE && nowMs - lastOnset < intervalMs) return false
        lastOnset = nowMs
        return true
    }

    /** Forgets the level so far, e.g. when a session starts or ends. */
    fun reset() {
        previous = 0f
        lastOnset = Long.MIN_VALUE
    }

    companion object {
        /** Silence floor: a chunk quieter than this never opens a syllable. */
        const val FLOOR = 0.06f

        /** How far above the previous chunk a level must rise to count as an onset. */
        const val RISE = 1.6f

        /** Shortest gap between two hops, so speech cannot strobe the character. */
        const val INTERVAL_MS = 300L
    }
}
