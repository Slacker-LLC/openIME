package llc.slacker.openime

import llc.slacker.openime.candidate.pinyinFuzzyVariants
import llc.slacker.openime.core.FuzzyRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FuzzyRuleTest {
    private fun asset(relative: String): File = sequenceOf(
        File("src/main/assets/$relative"),
        File("app/src/main/assets/$relative"),
    ).first { it.isFile }

    /** Every syllable openIME knows, from the generated Pinyin table. */
    private val syllables: List<String> by lazy {
        asset("pinyin_chars.tsv").readLines()
            .map { it.substringBefore('\t') }
            .filter { it.isNotEmpty() && it.all { ch -> ch in 'a'..'z' } }
            .distinct()
    }

    /** Apply one librime `derive/pattern/replacement/` the way librime does: as an extra spelling. */
    private fun rimeDerive(rule: String, syllable: String): String? {
        val (pattern, replacement) = rule.removePrefix("derive/").removeSuffix("/").split('/')
        val regex = Regex(pattern)
        if (!regex.containsMatchIn(syllable)) return null
        return regex.replaceFirst(syllable, replacement).takeIf { it != syllable }
    }

    @Test
    fun theShippedRulesFileIsTheDefaultSwitches() {
        assertEquals(FuzzyRule.rimeYaml(FuzzyRule.DEFAULTS), asset("rime-data/openime_fuzzy.yaml").readText())
    }

    @Test
    fun fifteenPairsWithTheReferencesSixOnByDefault() {
        assertEquals(15, FuzzyRule.entries.size)
        assertEquals(6, FuzzyRule.entries.count { it.initial })
        // The reference's preference XML (res/cU.xml) defaults these six to
        // true: kZEqualToZh, kCEqualToCh, kSEqualToSh, kAnEqualToAng,
        // kEnEqualToEng, kInEqualToIng.
        assertEquals(
            setOf(
                FuzzyRule.Z_ZH, FuzzyRule.C_CH, FuzzyRule.S_SH,
                FuzzyRule.AN_ANG, FuzzyRule.EN_ENG, FuzzyRule.IN_ING,
            ),
            FuzzyRule.DEFAULTS,
        )
    }

    @Test
    fun kotlinAndLibrimeSpellEverySyllableTheSameWayForEveryPair() {
        FuzzyRule.entries.forEach { rule ->
            syllables.forEach { syllable ->
                val rime = rule.rime.mapNotNull { rimeDerive(it, syllable) }.toSet()
                val kotlin = setOfNotNull(rule.variant(syllable))
                assertEquals("${rule.label} on $syllable", rime, kotlin)
            }
        }
    }

    @Test
    fun eachPairWorksBothWays() {
        assertEquals("zi", FuzzyRule.Z_ZH.variant("zhi"))
        assertEquals("zhi", FuzzyRule.Z_ZH.variant("zi"))
        assertEquals("li", FuzzyRule.N_L.variant("ni"))
        assertEquals("ni", FuzzyRule.N_L.variant("li"))
        assertEquals("shang", FuzzyRule.AN_ANG.variant("shan"))
        assertEquals("shan", FuzzyRule.AN_ANG.variant("shang"))
        // an = ang leaves ian/uan to their own switches.
        assertEquals(null, FuzzyRule.AN_ANG.variant("xian"))
        assertEquals(null, FuzzyRule.AN_ANG.variant("guan"))
        assertEquals("xiang", FuzzyRule.IAN_IANG.variant("xian"))
        assertEquals("guang", FuzzyRule.UAN_UANG.variant("guan"))
        assertEquals("hei", FuzzyRule.F_H.variant("fei"))
        assertEquals("le", FuzzyRule.R_L.variant("re"))
        // zh is not z + h: n = l does not touch it, z = zh does.
        assertEquals(null, FuzzyRule.N_L.variant("zhi"))
        assertEquals(null, FuzzyRule.F_H.variant("zhi"))
    }

    @Test
    fun theFourFinalPairsTheReferenceCarriesBeyondOurs() {
        // un = ong and eng = ong are final swaps the reference spells over the
        // whole syllable table (f_syllable-variants.tsv bits D3 and D6).
        assertEquals("chong", FuzzyRule.UN_ONG.variant("chun"))
        assertEquals("chun", FuzzyRule.UN_ONG.variant("chong"))
        assertEquals("yun", FuzzyRule.UN_ONG.variant("yong"))
        assertEquals("jiun", FuzzyRule.UN_ONG.variant("jiong"))
        assertEquals("dong", FuzzyRule.ENG_ONG.variant("deng"))
        assertEquals("deng", FuzzyRule.ENG_ONG.variant("dong"))
        assertEquals("yeng", FuzzyRule.ENG_ONG.variant("yong"))
        // The other two are whole-syllable pairs in the reference too.
        assertEquals("hui", FuzzyRule.EI_UI.variant("fei"))
        assertEquals("fei", FuzzyRule.EI_UI.variant("hui"))
        // hei is not one of the pair: it reaches hui only through f = h, the
        // way the reference gates its own hui <-> hei row on both bits.
        assertEquals(null, FuzzyRule.EI_UI.variant("hei"))
        assertEquals("wang", FuzzyRule.HUANG_WANG.variant("huang"))
        assertEquals("huang", FuzzyRule.HUANG_WANG.variant("wang"))
        assertEquals(null, FuzzyRule.HUANG_WANG.variant("zhuang"))
    }

    @Test
    fun onlyTheSwitchedOnPairsWidenTheLookup() {
        val onlyZ = pinyinFuzzyVariants("zi", setOf(FuzzyRule.Z_ZH))
        assertTrue("zhi" in onlyZ)
        assertFalse("ci" in onlyZ)

        val onlyAn = pinyinFuzzyVariants("shan", setOf(FuzzyRule.AN_ANG))
        assertTrue("shang" in onlyAn)
        assertFalse("san" in onlyAn)

        // Both on: they combine, as in librime.
        assertTrue("sang" in pinyinFuzzyVariants("shan", setOf(FuzzyRule.S_SH, FuzzyRule.AN_ANG)))

        assertTrue(pinyinFuzzyVariants("zi", emptySet()).isEmpty())
    }

    @Test
    fun aRulesFileWithNothingOnIsStillValid() {
        assertEquals(
            "# Generated by openIME from the 模糊音 switches; do not edit.\nrules:\n  __append: []\n",
            FuzzyRule.rimeYaml(emptySet()),
        )
    }
}
