package llc.slacker.openime

import llc.slacker.openime.handwriting.HandwritingFeaturePolicy
import llc.slacker.openime.handwriting.HandwritingProvider
import llc.slacker.openime.handwriting.HandwritingResult
import llc.slacker.openime.handwriting.Stroke
import llc.slacker.openime.handwriting.UnavailableHandwritingProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandwritingFeaturePolicyTest {
    @Test
    fun unavailableProviderDisablesProductionEntry() {
        assertFalse(HandwritingFeaturePolicy.entryEnabled(UnavailableHandwritingProvider))
        assertTrue(UnavailableHandwritingProvider.recognize(emptyList()) is HandwritingResult.NotConfigured)
    }

    @Test
    fun configuredProviderEnablesProductionEntry() {
        val provider = object : HandwritingProvider {
            override fun recognize(strokes: List<Stroke>): HandwritingResult =
                HandwritingResult.Success(listOf("你"))
        }

        assertTrue(HandwritingFeaturePolicy.entryEnabled(provider))
    }

    /**
     * The launcher entries are hidden for now even where the strokes table
     * ships; the switch is the only thing that keeps them out.
     */
    @Test
    fun theLauncherEntryFollowsTheOfferSwitch() {
        assertFalse(HandwritingFeaturePolicy.entryShown(assetPresent = true))
        assertFalse(HandwritingFeaturePolicy.entryShown(assetPresent = false))
    }
}
