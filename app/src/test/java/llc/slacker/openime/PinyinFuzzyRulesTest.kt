package llc.slacker.openime

import llc.slacker.openime.candidate.pinyinFuzzyVariants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinFuzzyRulesTest {
    // These cases were written for the pairs that used to be always on.
    private val previousFuzzyRules = llc.slacker.openime.candidate.FuzzyPinyin.rules

    @org.junit.Before
    fun enableFormerFuzzyPairs() {
        llc.slacker.openime.candidate.FuzzyPinyin.rules = setOf(
            llc.slacker.openime.core.FuzzyRule.Z_ZH,
            llc.slacker.openime.core.FuzzyRule.C_CH,
            llc.slacker.openime.core.FuzzyRule.S_SH,
            llc.slacker.openime.core.FuzzyRule.N_L,
            llc.slacker.openime.core.FuzzyRule.EN_ENG,
            llc.slacker.openime.core.FuzzyRule.IN_ING,
        )
    }

    @org.junit.After
    fun restoreFuzzyPairs() {
        llc.slacker.openime.candidate.FuzzyPinyin.rules = previousFuzzyRules
    }


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
