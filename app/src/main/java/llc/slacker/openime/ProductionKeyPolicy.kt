package llc.slacker.openime

/** Pure geometry/timing helpers for production key presentation. */
internal object ProductionKeyPolicy {

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

    /**
     * The legacy renderer already waits the platform long-press threshold
     * before forwarding a voice press. V2 adds no extra delay on top of it, so a
     * long-press gesture is not stretched to roughly twice the threshold.
     */
    fun remainingVoiceDelayMs(@Suppress("UNUSED_PARAMETER") systemLongPressTimeoutMs: Long): Long = 0L
}
