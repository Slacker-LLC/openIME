package llc.slacker.openime

import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinFuzzyFallbackTest {

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
}
