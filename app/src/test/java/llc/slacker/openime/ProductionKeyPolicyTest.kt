package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for current-main production key geometry. */
class ProductionKeyPolicyTest {
    @Test
    fun twentySixKeyBottomRowFollowsTheReferenceCanvas() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        // 123 | 中/英 | 空格 | (no right inner key) | 确定, in the design's 390 units.
        assertEquals(68f, weights.leftOuter, 0.0001f)
        assertEquals(56f, weights.leftInner, 0.0001f)
        assertEquals(178f, weights.space, 0.0001f)
        assertEquals(0f, weights.rightInner, 0.0001f)
        assertEquals(88f, weights.rightOuter, 0.0001f)
        assertEquals(
            390f,
            weights.leftOuter + weights.leftInner + weights.space + weights.rightInner + weights.rightOuter,
            0.0001f,
        )
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
