package llc.slacker.openime

import llc.slacker.openime.candidate.CandidateEngine
import llc.slacker.openime.candidate.pinyinFuzzyVariants
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinFuzzyFallbackTest {
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
    fun fuzzyInitialAppliesInsideLaterSyllable() {
        val engine = CandidateEngine(mapOf("hunan" to listOf("湖南")))

        assertTrue(
            "hulan should resolve hunan when n/l fuzzy correction is enabled",
            engine.getCandidates("hulan", fuzzy = true).contains("湖南"),
        )
    }

    @Test
    fun fuzzyRetroflexAppliesInsideLaterSyllable() {
        val variants = pinyinFuzzyVariants("dashan")

        assertTrue(
            "second-syllable sh/s correction should not be limited to the start of the whole input",
            variants.contains("dasan"),
        )
    }

    @Test
    fun fuzzyFinalAppliesInsideEarlierSyllable() {
        val variants = pinyinFuzzyVariants("shenghuo")

        assertTrue(
            "eng/en correction should apply before a following syllable",
            variants.contains("shenhuo"),
        )
    }

    @Test
    fun fuzzyVariantExpansionStaysBounded() {
        val variants = pinyinFuzzyVariants("zhengzhenglinlin")

        assertTrue("fuzzy expansion must remain bounded on the IME path", variants.size <= 16)
    }
}
