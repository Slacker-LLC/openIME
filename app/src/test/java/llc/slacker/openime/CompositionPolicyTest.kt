package llc.slacker.openime

import llc.slacker.openime.keyboard.CompositionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionPolicyTest {
    /**
     * The pre-edit block is at most half the page: `maxwidth="halfMaxWidth"` on
     * the composition's wrapper, whose sibling control takes the other half
     * (`input_toolbar_typing.xml:6,18`).
     */
    @Test
    fun thePreEditKeepsHalfThePage() {
        assertEquals(0.5f, CompositionPolicy.MAX_WIDTH_FRACTION, 0.00001f)
        assertEquals(5, CompositionPolicy.LEAD_UNITS)
        assertEquals(195, CompositionPolicy.maxWidthPx(390))
        assertEquals(0, CompositionPolicy.maxWidthPx(0))
    }
}
