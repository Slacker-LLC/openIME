package llc.slacker.openime.keyboard

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

    /** Nine-key bottom row: 123 | 空格 | 中/英. The space bar is the wide key. */
    data class NineKeyBottomRowWeights(val side: Float, val space: Float)

    fun nineKeyBottomRowWeights(): NineKeyBottomRowWeights = NineKeyBottomRowWeights(side = 0.7f, space = 1.6f)

    /**
     * Visual reference geometry for the shared Chinese/English 26-key bottom row:
     * 123 | ，。 | 空格 | 中/英 | 确定. The row mirrors around the space key so the
     * space bar sits on the keyboard's centre line.
     */
    fun twentySixKeyBottomRowWeights(): BottomRowWeights {
        return BottomRowWeights(70f, 48f, 154f, 48f, 70f)
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
