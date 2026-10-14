package llc.slacker.openime.keyboard

/** Pure geometry helpers for production key presentation. */
internal object ProductionKeyPolicy {
    data class EdgeWeights(
        val leftOuter: Float,
        val rightOuter: Float,
    )

    data class BottomRowWeights(
        val leftOuter: Float,
        val merged: Float,
        val symbol: Float,
        val space: Float,
        val rightInner: Float,
        val rightOuter: Float,
    )

    /**
     * Nine-key bottom row: 123 | 空格 | 中/英, in the reference's own weights.
     * Its nine-key board and its stroke board give that row the same three
     * figures — 45.5, 134, 45.5 — inside the 225-unit centre column
     * (`input_kbd_pinyin9.xml:87-100`, `input_kbd_stroke.xml:69-84`).
     */
    data class NineKeyBottomRowWeights(val side: Float, val space: Float)

    fun nineKeyBottomRowWeights(): NineKeyBottomRowWeights = NineKeyBottomRowWeights(side = 45.5f, space = 134f)

    /**
     * Visual reference geometry for the shared Chinese/English 26-key bottom row:
     * 123 | ，。 | ， | 空格 | 。 | 中/英 | 确定, in the reference's own weights
     * (`style.xml:957-978`: 123 70.6, the merged key 37.6, both symbol keys 35.5,
     * space 133.6, 中/英 38.6, 确定 74.6). The row mirrors around the space key so
     * the space bar sits on the keyboard's centre line.
     */
    fun twentySixKeyBottomRowWeights(landscape: Boolean = false): BottomRowWeights {
        // 横屏按参考键盘的横屏皮肤放大空格键：其空格权重 133.6 -> 182.2（×1.3638），
        // 其余各键均不变（skin-reference/land/style.xml:115）。
        val space = if (landscape) 182.2f else 133.6f
        return BottomRowWeights(70.6f, 37.6f, 35.5f, space, 38.6f, 74.6f)
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
