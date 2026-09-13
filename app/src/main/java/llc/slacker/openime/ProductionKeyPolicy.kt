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

    /**
     * The legacy renderer already waits the full product threshold (150 ms)
     * before forwarding a voice press. V2 must not add Android's system
     * long-press timeout on top of that delay, otherwise a 150 ms gesture turns
     * into roughly a 500 ms gesture on a typical device.
     */
    fun remainingVoiceDelayMs(@Suppress("UNUSED_PARAMETER") systemLongPressTimeoutMs: Long): Long = 0L
}
