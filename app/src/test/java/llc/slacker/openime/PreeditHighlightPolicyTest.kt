package llc.slacker.openime

import llc.slacker.openime.keyboard.PreeditHighlightPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PreeditHighlightPolicyTest {
    @Test
    fun theFixedSyllablesOfTheShownTextCarryTheAccent() {
        assertEquals(3, PreeditHighlightPolicy.fixedPrefixLength("ni'hao", "ni'"))
        assertEquals(3, PreeditHighlightPolicy.fixedPrefixLength("ni hao", "ni "))
        assertEquals(2, PreeditHighlightPolicy.fixedPrefixLength("nihao", "ni"))
    }

    @Test
    fun aGuessedBoundaryStaysInThePlainColour() {
        // A decoder-inserted divider is not a syllable the user chose.
        assertEquals(0, PreeditHighlightPolicy.fixedPrefixLength("wo'yi a m", "ni "))
        assertEquals(0, PreeditHighlightPolicy.fixedPrefixLength("hao", ""))
    }
}
