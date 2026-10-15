package llc.slacker.openime

import llc.slacker.openime.candidate.CandidateEngine
import llc.slacker.openime.candidate.CandidatePipeline
import llc.slacker.openime.candidate.FuzzyPinyin
import llc.slacker.openime.candidate.NineKeyLocalDecoder
import llc.slacker.openime.core.FuzzyRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyChineseTest {
    private val engine = CandidateEngine()
    private val pipeline = CandidatePipeline(engine)

    @Test
    fun dirtyLegacyMappingsCannotCrossDigitCodes() {
        val hao = engine.get9KeyCandidates("426")
        assertEquals("hao", hao.pinyins.first())
        assertFalse("gong must not leak into 426", "gong" in hao.pinyins)

        val zhi = engine.get9KeyCandidates("944")
        assertFalse("zhe must not leak into 944", "zhe" in zhi.pinyins)

        val xian = engine.get9KeyCandidates("9426")
        assertFalse("xiang must not leak into 9426", "xiang" in xian.pinyins)
    }

    @Test
    fun returnedPreviewPathsAlwaysEncodeToTheTypedDigits() {
        listOf("2", "4", "42", "64", "426", "943", "944", "9426", "94264", "64426").forEach { digits ->
            val result = engine.get9KeyCandidates(digits)
            assertTrue(
                "all exposed pinyin paths must exactly encode $digits: ${result.pinyins}",
                result.pinyins.all { pinyin ->
                    CandidateEngine.nineKeyDigitsForPinyin(pinyin) == digits
                },
            )
        }
    }

    @Test
    fun aLoneKeyShowsALetterNotItsDigit() {
        // The pre-edit is letters from the first key on; Rime's answer then
        // aligns it to the top candidate (4 -> 和 -> h).
        assertEquals("a", resolve("2").preview)
        assertEquals("o", resolve("6").preview)
        listOf("3", "4", "5", "7", "8", "9").forEach { digit ->
            val preview = resolve(digit).preview
            assertEquals("one letter for $digit", 1, preview.length)
            assertEquals(digit, NineKeyLocalDecoder.digitsForPinyin(preview))
        }
    }

    @Test
    fun commonChineseSequencesResolveToStableExpectedPreview() {
        assertEquals("ni", resolve("64").preview)
        assertEquals("hao", resolve("426").preview)
        assertEquals("nihao", resolve("64426").preview)
        assertEquals("zhe", resolve("943").preview)
        assertEquals("xiang", resolve("94264").preview)
        assertEquals("weixin", resolve("934946").preview)
    }

    @Test
    fun shortPrefixDoesNotExposeLongPredictionAsPreedit() {
        listOf("2", "3", "4", "7", "9", "94").forEach { digits ->
            val resolution = resolve(digits)
            val previewDigits = CandidateEngine.nineKeyDigitsForPinyin(resolution.preview)
            if (previewDigits != null) {
                assertEquals(
                    "preview must represent only the keys already typed",
                    digits,
                    previewDigits,
                )
            }
        }
    }

    @Test
    fun commonSequencesStillProduceChineseCandidates() {
        listOf("64", "426", "64426", "943", "94264").forEach { digits ->
            val resolution = resolve(digits)
            assertTrue("$digits should have candidates", resolution.candidates.isNotEmpty())
            assertTrue(
                "$digits candidates should not contain raw digits",
                resolution.candidates.none { candidate -> candidate.any(Char::isDigit) },
            )
        }
    }

    @Test
    fun highConfidencePresetWinsAfterAnAmbiguousPrefix() {
        val decoder = NineKeyLocalDecoder(engine)

        decoder.resolve("6", preferredSuffix = null, fuzzy = false)
        assertEquals("ni", decoder.resolve("64", preferredSuffix = null, fuzzy = false).previewSuffix)

        decoder.resolve("644", preferredSuffix = null, fuzzy = false)
        assertEquals("nihao", decoder.resolve("64426", preferredSuffix = null, fuzzy = false).previewSuffix)
    }

    @Test
    fun aLoneKeyAlignsToTheTopCandidatesInitial() {
        // 4 / 5 / 7 / 8 / 9 hold only initials; the pre-edit shows the top
        // candidate's initial instead of staying on the digit.
        assertEquals(listOf("h"), pipeline.nineKeyReadingFor("4", "和"))
        assertEquals(listOf("l"), pipeline.nineKeyReadingFor("5", "了"))
        assertEquals(listOf("s"), pipeline.nineKeyReadingFor("7", "是"))
        assertEquals(listOf("t"), pipeline.nineKeyReadingFor("8", "他"))
        assertEquals(listOf("w"), pipeline.nineKeyReadingFor("9", "我"))
        // A full syllable still wins, and a character the key cannot start stays unaligned.
        assertEquals(listOf("a"), pipeline.nineKeyReadingFor("2", "啊"))
        assertEquals(null, pipeline.nineKeyReadingFor("4", "啊"))
        // More than one key keeps requiring complete syllables.
        assertEquals(null, pipeline.nineKeyReadingFor("44", "和"))
    }

    /**
     * The reference keeps a masked reading beside every plain one and lets it in
     * when its rule is on: node "64" of syllable.dat carries `li` with the n↔l
     * mask 0x0008 (data3[275]; pass2 0x5b962c gates on `mask ⊆ 用户fuzzy`), so a
     * press of 64 offers 里 once n = l is switched on and never before.
     */
    @Test
    fun aFuzzyRuleAddsTheReadingsItCoversAndNothingWhileOff() {
        val previous = FuzzyPinyin.rules
        try {
            FuzzyPinyin.rules = setOf(FuzzyRule.N_L)
            val on = pipeline.resolveNineKey("64", "", null, fuzzy = true)
            assertTrue("li must read 64 under n = l: ${on.displayPinyinPaths}", "li" in on.displayPinyinPaths)
            assertTrue("里 must be offered for 64: ${on.candidates}", "里" in on.candidates)
            val off = pipeline.resolveNineKey("64", "", null, fuzzy = false)
            assertFalse("no li while fuzzy is off: ${off.displayPinyinPaths}", "li" in off.displayPinyinPaths)
            // The rail carries plain spellings only, exactly like the reference's
            // left builder, which takes records whose mask is zero (0x5ba378).
            assertTrue(
                "a masked reading stays out of the rail: " +
                    pipeline.nineKeyReadingsFor("64", null).map { it.display },
                "li" !in pipeline.nineKeyReadingsFor("64", null).map { it.display },
            )
        } finally {
            FuzzyPinyin.rules = previous
        }
    }

    private fun resolve(digits: String): CandidatePipeline.NineKeyResolution =
        pipeline.resolveNineKey(
            digits = digits,
            segmentPrefix = "",
            preferredSuffix = null,
            fuzzy = false,
        )
}
