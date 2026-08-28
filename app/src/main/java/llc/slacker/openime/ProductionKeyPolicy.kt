package llc.slacker.openime

/** Pure geometry/timing helpers for production key presentation. */
internal object ProductionKeyPolicy {
    const val LEGACY_SPACE_VOICE_TRIGGER_MS = 150L

    data class EdgeWeights(
        val leftOuter: Float,
        val rightOuter: Float,
    )

    /**
     * Balance the total weights on both sides of a centered space key while
     * preserving the inner key weights. Half of the imbalance is moved from
     * the heavier outside key to the lighter outside key.
     */
    fun balancedOuterWeights(
        leftTotal: Float,
        rightTotal: Float,
        leftOuter: Float,
        rightOuter: Float,
    ): EdgeWeights {
        val halfDelta = (rightTotal - leftTotal) / 2f
        return EdgeWeights(
            leftOuter = (leftOuter + halfDelta).coerceAtLeast(0.1f),
            rightOuter = (rightOuter - halfDelta).coerceAtLeast(0.1f),
        )
    }

    /** Delay still required after the legacy renderer fires at 150 ms. */
    fun remainingVoiceDelayMs(systemLongPressTimeoutMs: Long): Long =
        (systemLongPressTimeoutMs - LEGACY_SPACE_VOICE_TRIGGER_MS).coerceAtLeast(0L)
}
