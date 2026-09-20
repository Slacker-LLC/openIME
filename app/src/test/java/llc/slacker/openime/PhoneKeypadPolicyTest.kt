package llc.slacker.openime

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
}
