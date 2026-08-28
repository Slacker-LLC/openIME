package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression coverage for current-main production key geometry and long-press timing. */
class ProductionKeyPolicyTest {
    @Test
    fun balancesTwentySixKeyBottomRowAroundSpace() {
        val balanced = ProductionKeyPolicy.balancedOuterWeights(
            leftTotal = 1.30f + 0.95f,
            rightTotal = 1.05f + 1.80f,
            leftOuter = 1.30f,
            rightOuter = 1.80f,
        )
        val left = balanced.leftOuter + 0.95f
        val right = 1.05f + balanced.rightOuter
        assertEquals(left, right, 0.0001f)
        assertEquals(1.60f, balanced.leftOuter, 0.0001f)
        assertEquals(1.50f, balanced.rightOuter, 0.0001f)
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

    @Test
    fun legacyEarlyVoiceTriggerIsDelayedToSystemLongPressThreshold() {
        assertEquals(350L, ProductionKeyPolicy.remainingVoiceDelayMs(500L))
        assertEquals(0L, ProductionKeyPolicy.remainingVoiceDelayMs(100L))
    }
}
