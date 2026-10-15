package llc.slacker.openime

import llc.slacker.openime.keyboard.QuickInputRowPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInputRowPolicyTest {
    /**
     * The row's own numbers: the text box's vertical margins and the badge that
     * rides above its right shoulder (`input_toolbar_quickinput.xml:42,44-45`).
     */
    @Test
    fun theRowKeepsTheReferencesOwnRuler() {
        assertEquals(0.055f, QuickInputRowPolicy.TEXT_TOP_FRACTION, 0.00001f)
        assertEquals(0.111f, QuickInputRowPolicy.TEXT_BOTTOM_FRACTION, 0.00001f)
        assertEquals(0.08f, QuickInputRowPolicy.BADGE_TOP_FRACTION, 0.00001f)
    }

    /**
     * The badge is the candidate font scaled by `fi_toolbar_tip_superscript`'s
     * 30 units against `cand_font`'s 56.
     */
    @Test
    fun theBadgeIsASuperscriptOfTheCandidateFont() {
        assertEquals(30f / 56f, QuickInputRowPolicy.BADGE_FONT_RATIO, 0.00001f)
        assertEquals(9.64f, 18f * QuickInputRowPolicy.BADGE_FONT_RATIO, 0.01f)
        assertTrue(
            "The badge stays well under the text it marks",
            QuickInputRowPolicy.BADGE_FONT_RATIO < 0.6f,
        )
    }
}
