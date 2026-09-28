package llc.slacker.openime

/** Pure geometry helpers for production key presentation. */
internal object ProductionKeyPolicy {
    data class EdgeWeights(
        val leftOuter: Float,
        val rightOuter: Float,
    )

    data class BottomRowWeights(
        val leftOuter: Float,
        val leftInner: Float,
        val space: Float,
        val rightInner: Float,
        val rightOuter: Float,
    )

    /** Visual reference geometry for the shared Chinese/English 26-key bottom row. */
    fun twentySixKeyBottomRowWeights(): BottomRowWeights {
        val leftOuter = 1.30f
        val leftInner = 0.95f
        val rightInner = 1.05f
        val rightOuter = 1.80f
        val balanced = balancedOuterWeights(
            leftTotal = leftOuter + leftInner,
            rightTotal = rightInner + rightOuter,
            leftOuter = leftOuter,
            rightOuter = rightOuter,
        )
        return BottomRowWeights(
            leftOuter = balanced.leftOuter,
            leftInner = leftInner,
            space = 3.40f,
            rightInner = rightInner,
            rightOuter = balanced.rightOuter,
        )
    }

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

}
