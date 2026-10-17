package llc.slacker.openime

import llc.slacker.openime.keyboard.ProductionKeyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the original production key geometry. */
class ProductionKeyPolicyTest {

    @Test
    fun twentySixKeyBottomRowKeepsTheSpaceBarCentred() {
        listOf(false, true).forEach { landscape ->
            val weights = ProductionKeyPolicy.twentySixKeyBottomRowWeights(landscape)
            assertEquals(weights.leftOuter + weights.leftInner, weights.rightInner + weights.rightOuter, 0.0001f)
        }
    }


    @Test
    fun nineKeyBottomRowKeepsTheOriginalWidths() {
        val weights = ProductionKeyPolicy.nineKeyBottomRowWeights()
        assertEquals(0.7f, weights.side, 0.0001f)
        assertEquals(1.6f, weights.space, 0.0001f)
        assertTrue(weights.space > weights.side * 2)
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
