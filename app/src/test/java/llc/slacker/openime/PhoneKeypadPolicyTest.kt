package llc.slacker.openime

import llc.slacker.openime.keyboard.PhoneKeypadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKeypadPolicyTest {
    @Test
    fun phoneSurfaceUsesAsciiStarHashAndPlus() {
        val literals = PhoneKeypadPolicy.literalByTag.values.toSet()
        assertEquals(setOf("*", "+", "#"), literals)
        assertTrue("*" in literals)
        assertTrue("#" in literals)
        assertTrue("+" in literals)
        assertFalse("＊" in literals)
    }

    /** Each dialer symbol sits on a cell of the number page's own row. */
    @Test
    fun theDialerSymbolsSitOnTheNumberPagesOwnCells() {
        assertEquals(
            setOf("key-space", "key:.", "key:symbol"),
            PhoneKeypadPolicy.literalByTag.keys,
        )
    }
}
