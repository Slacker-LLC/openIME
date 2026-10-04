package llc.slacker.openime

import llc.slacker.openime.keyboard.ProductionKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for current-main production key geometry. */
class ProductionKeyPolicyTest {
    @Test
    fun twentySixKeyBottomRowFollowsTheReferenceCanvas() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        // 123 | ，。 | 空格 | 中/英 | 确定, in the design's 390 units.
        assertEquals(70f, weights.leftOuter, 0.0001f)
        assertEquals(48f, weights.leftInner, 0.0001f)
        assertEquals(154f, weights.space, 0.0001f)
        assertEquals(48f, weights.rightInner, 0.0001f)
        assertEquals(70f, weights.rightOuter, 0.0001f)
        assertEquals(
            390f,
            weights.leftOuter + weights.leftInner + weights.space + weights.rightInner + weights.rightOuter,
            0.0001f,
        )
    }

    @Test
    fun twentySixKeyBottomRowIsSymmetricAroundTheSpaceKey() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        assertEquals(weights.leftOuter, weights.rightOuter, 0.0001f)
        assertEquals(weights.leftInner, weights.rightInner, 0.0001f)
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
