package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for current-main production key geometry. */
class ProductionKeyPolicyTest {
    @Test
    fun balancesTwentySixKeyBottomRowAroundSpace() {
        val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights()
        val left = weights.leftOuter + weights.leftInner
        val right = weights.rightInner + weights.rightOuter
        assertEquals(left, right, 0.0001f)
        assertEquals(1.60f, weights.leftOuter, 0.0001f)
        assertEquals(0.95f, weights.leftInner, 0.0001f)
        assertEquals(3.40f, weights.space, 0.0001f)
        assertEquals(1.05f, weights.rightInner, 0.0001f)
        assertEquals(1.50f, weights.rightOuter, 0.0001f)
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
