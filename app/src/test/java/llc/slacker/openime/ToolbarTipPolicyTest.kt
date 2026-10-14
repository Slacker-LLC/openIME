package llc.slacker.openime

import llc.slacker.openime.keyboard.ToolbarTipPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarTipPolicyTest {
    /**
     * The banner's own fractions of the row width: the container padding
     * (`cls_toolbar_tip_adaptive`, `style.xml:442`), the lead space
     * (`style.xml:1277`) and the label's own inset (`style.xml:1265`).
     */
    @Test
    fun theBannerKeepsTheReferencesOwnRuler() {
        assertEquals(0.03333f, ToolbarTipPolicy.CONTAINER_PAD_FRACTION, 0.00001f)
        assertEquals(0.03571f, ToolbarTipPolicy.LEAD_WEIGHT, 0.00001f)
        assertEquals(0.012f, ToolbarTipPolicy.LABEL_PAD_FRACTION, 0.00001f)
        assertEquals(999, ToolbarTipPolicy.RADIUS_UNITS)
    }

    /**
     * The pill is the row minus the container's two vertical fractions
     * (0.17857 + 0.10714 of the 48-unit row), which leaves the 34dp our painted
     * toolbar controls use.
     */
    @Test
    fun thePillIsTheRowsOwnRemainder() {
        val rowUnits = 48f
        val left = rowUnits * (1f - 0.17857f - 0.10714f)
        assertEquals(ToolbarTipPolicy.PILL_UNITS.toFloat(), left, 0.5f)
        assertTrue(
            "A 48dp touch target still fits around the pill",
            ToolbarTipPolicy.PILL_UNITS < 48,
        )
    }
}
