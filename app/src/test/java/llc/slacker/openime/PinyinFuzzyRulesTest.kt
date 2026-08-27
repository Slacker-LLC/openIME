package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinFuzzyRulesTest {

    @Test
    fun coversZhZInBothDirections() {
        assertTrue("za" in pinyinFuzzyVariants("zha"))
        assertTrue("zha" in pinyinFuzzyVariants("za"))
    }

    @Test
    fun coversNLInBothDirections() {
        assertTrue("lan" in pinyinFuzzyVariants("nan"))
        assertTrue("nan" in pinyinFuzzyVariants("lan"))
    }

    @Test
    fun coversEnEngAndInIngInBothDirections() {
        assertTrue("ben" in pinyinFuzzyVariants("beng"))
        assertTrue("beng" in pinyinFuzzyVariants("ben"))
        assertTrue("pin" in pinyinFuzzyVariants("ping"))
        assertTrue("ping" in pinyinFuzzyVariants("pin"))
    }

    @Test
    fun combinesIndependentFuzzyGroupsWithoutReturningOriginal() {
        val variants = pinyinFuzzyVariants("zheng")
        assertTrue("zeng" in variants)
        assertTrue("zhen" in variants)
        assertTrue("zen" in variants)
        assertFalse("zheng" in variants)
        assertEquals(variants.distinct(), variants)
    }
}
