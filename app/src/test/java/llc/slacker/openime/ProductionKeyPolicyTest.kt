package llc.slacker.openime

import llc.slacker.openime.keyboard.ProductionKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for current-main production key geometry. */
class ProductionKeyPolicyTest {
    @Test
    fun twentySixKeyBottomRowCarriesTheReferenceWeights() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        // 123 | ，。 | ， | 空格 | 。 | 中/英 | 确定, in the reference's own units
        // (style.xml:957-978).
        assertEquals(70.6f, weights.leftOuter, 0.0001f)
        assertEquals(37.6f, weights.merged, 0.0001f)
        assertEquals(35.5f, weights.symbol, 0.0001f)
        assertEquals(133.6f, weights.space, 0.0001f)
        assertEquals(38.6f, weights.rightInner, 0.0001f)
        assertEquals(74.6f, weights.rightOuter, 0.0001f)
    }

    @Test
    fun twentySixKeyBottomRowKeepsTheSpaceBarNearTheCentreLine() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        // The reference's own row is a touch heavier on the right: 确定 is wider
        // than 123, and the two symbol keys bookend the space bar.
        val left = weights.leftOuter + weights.merged + weights.symbol
        val right = weights.symbol + weights.rightInner + weights.rightOuter
        assertEquals(143.7f, left, 0.0001f)
        assertEquals(148.7f, right, 0.0001f)
        assertEquals(5f, right - left, 1f)
    }

    @Test
    fun twentySixKeyBottomRowGivesLandscapeASpaceBarLikeTheReference() {
        val portrait = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        val landscape = ProductionKeyPolicy.twentySixKeyBottomRowWeights(landscape = true)
        // 参考键盘横屏皮肤只放大空格键（133.6 -> 182.2，×1.3638），其余键不动。
        assertEquals(portrait.leftOuter, landscape.leftOuter, 0.0001f)
        assertEquals(portrait.merged, landscape.merged, 0.0001f)
        assertEquals(portrait.symbol, landscape.symbol, 0.0001f)
        assertEquals(portrait.rightInner, landscape.rightInner, 0.0001f)
        assertEquals(portrait.rightOuter, landscape.rightOuter, 0.0001f)
        assertEquals(182.2f, landscape.space, 0.0001f)
        assertEquals(182.2f / 133.6f, landscape.space / portrait.space, 0.0001f)
    }

    @Test
    fun nineKeyBottomRowGivesTheSpaceBarMoreRoomThanTheSideKeys() {
        val weights = ProductionKeyPolicy.nineKeyBottomRowWeights()
        assertEquals(0.7f, weights.side, 0.0001f)
        assertEquals(1.6f, weights.space, 0.0001f)
        // Same total as the former 1:1:1 row, so the grid above is unaffected.
        assertEquals(3f, weights.side * 2 + weights.space, 0.0001f)
    }

    @Test
    fun balancesNineKeyBottomRowWithoutChangingSpaceWeight() {
        val balanced = ProductionKeyPolicy.balancedOuterWeights(
            leftTotal = 0.90f,
            rightTotal = 0.95f,
            leftOuter = 0.90f,
            rightOuter = 0.95f,
        )
        assertEquals(balanced.leftOuter, balanced.rightOuter, 0.0001f)
        assertEquals(0.925f, balanced.leftOuter, 0.0001f)
    }

}
