package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidatePipelineTest {
    private val engine = CandidateEngine(
        linkedMapOf(
            "ni" to listOf("你", "呢"),
            "hao" to listOf("好", "号"),
            "nihao" to listOf("你好", "拟好"),
        ),
    )
    private val pipeline = CandidatePipeline(engine)

    @Test
    fun ordinaryModesPreserveCandidateEngineSemantics() {
        assertEquals(
            engine.getCandidates("nihao", false),
            pipeline.candidatesFor(KeyboardMode.PINYIN_26, "nihao", false),
        )

        val english = pipeline.candidatesFor(KeyboardMode.ENGLISH_26, "hel", false)
        assertEquals("hel", english.first())
        assertEquals(
            (listOf("hel") + engine.getEnglishCompletions("hel")).distinct(),
            english,
        )

        assertTrue(pipeline.candidatesFor(KeyboardMode.DIGITS, "123", false).isEmpty())
    }

    @Test
    fun nineKeyResolutionUsesOneNativeDigitCode() {
        val resolution = pipeline.resolveNineKey(
            digits = "64426",
            segmentPrefix = "",
            preferredSuffix = "nihao",
            fuzzy = false,
        )

        assertEquals("nihao", resolution.preview)
        assertEquals(listOf("64426"), resolution.pinyinPaths)
        assertTrue("selected display path must remain filterable", "nihao" in resolution.displayPinyinPaths)
        resolution.displayPinyinPaths.forEach { path ->
            assertEquals("display path must match the current digits: $path", "64426", CandidatePipeline.nineKeyDigitsFor(path))
        }
        assertTrue(resolution.candidates.isNotEmpty())
        assertTrue(resolution.candidates.contains("你好"))
        assertFalse(resolution.candidates.any { candidate -> candidate.any(Char::isDigit) })
        assertEquals(resolution.candidates.distinct(), resolution.candidates)
        assertEquals(resolution.candidates, pipeline.nineKeyFallbackCandidatesFor("64426"))
    }

    @Test
    fun segmentedNineKeyPreservesBoundaryInNativeCode() {
        val resolution = pipeline.resolveNineKey(
            digits = "426",
            segmentPrefix = "ni ",
            preferredSuffix = "hao",
            fuzzy = false,
        )

        assertEquals("ni hao", resolution.preview)
        assertEquals(listOf("ni'426"), resolution.pinyinPaths)
        assertTrue("ni hao" in resolution.displayPinyinPaths)
        assertFalse("Suffix-only choices would drop ni when selected", resolution.candidates.contains("好"))
        assertTrue(resolution.candidates.contains("你好"))
        assertEquals(resolution.candidates, pipeline.nineKeyFallbackCandidatesFor("ni'426"))
    }

    @Test
    fun nineKeyInputIsBoundedBeforeNativeResolution() {
        val resolution = pipeline.resolveNineKey(
            digits = "6".repeat(NineKeyLocalDecoder.MAX_DIGITS + 20),
            segmentPrefix = "",
            preferredSuffix = null,
            fuzzy = false,
        )

        assertTrue(resolution.pinyinPaths.size <= 1)
        assertTrue(resolution.pinyinPaths.firstOrNull()?.length ?: 0 <= NineKeyLocalDecoder.MAX_DIGITS)
        assertTrue(resolution.candidates.size <= 96)
    }

    @Test
    fun nineKeyDigitsForMapsPinyinToKeypadDigits() {
        assertEquals("64426", CandidatePipeline.nineKeyDigitsFor("nihao"))
        assertEquals("64426", CandidatePipeline.nineKeyDigitsFor("NiHao"))
        assertEquals("426", CandidatePipeline.nineKeyDigitsFor("hao"))
        assertEquals("6446", CandidatePipeline.nineKeyDigitsFor("niho"))
        assertNull(CandidatePipeline.nineKeyDigitsFor("ni hao"))
        assertNull(CandidatePipeline.nineKeyDigitsFor("ni2hao"))
        assertNull(CandidatePipeline.nineKeyDigitsFor("你好"))
    }

    @Test
    fun everyProductionPresetMatchesItsDigitCode() {
        NineKeyPresets.combinations.forEach { (digits, pinyins) ->
            assertTrue(pinyins.isNotEmpty())
            pinyins.forEach { pinyin ->
                assertEquals("preset $digits -> $pinyin is invalid", digits, NineKeyLocalDecoder.digitsForPinyin(pinyin))
            }
        }
        assertEquals(listOf("gong"), NineKeyPresets.combinations["4664"])
        assertEquals(listOf("zhe"), NineKeyPresets.combinations["943"])
        assertEquals(listOf("xiang"), NineKeyPresets.combinations["94264"])
        assertEquals(listOf("weixin"), NineKeyPresets.combinations["934946"])
    }

    @Test
    fun nativeCodeKeepsExplicitSegmentationAndRejectsGarbage() {
        assertEquals("64'426", NineKeyLocalDecoder.nativeCode("ni ", "426"))
        assertEquals("ni'426", NineKeyLocalDecoder.nativeCode("ni ", "426", lockLetters = true))
        assertEquals("94'26'426", NineKeyLocalDecoder.nativeCode("xi an ", "426"))
        assertNotNull(NineKeyLocalDecoder.nativeCode("", "64426"))
        assertNull(NineKeyLocalDecoder.nativeCode("你 ", "426"))
    }

    @Test
    fun nineKeyInternalEditRecoversDigitsAndDecodesCorrectly() {
        val suffixDigits = CandidatePipeline.nineKeyDigitsFor("niho")
        assertEquals("6446", suffixDigits)

        val inserted = suffixDigits!!.substring(0, 3) + "2" + suffixDigits.substring(3)
        assertEquals("64426", inserted)
        val resolution = pipeline.resolveNineKey(inserted, "", null, false)
        assertEquals("nihao", resolution.preview)
        assertTrue(resolution.candidates.contains("你好"))

        val deleted = "64426".removeRange(3, 4)
        assertEquals("6446", deleted)
    }

    @Test
    fun lockedSyllableReachesNativeAsLettersSoZhongAndXiongStayDistinct() {
        val locked = pipeline.resolveNineKey(
            digits = "94664",
            segmentPrefix = "",
            preferredSuffix = "xiong",
            fuzzy = false,
            lockPreferred = true,
        )
        assertEquals(listOf("xiong"), locked.pinyinPaths)

        val open = pipeline.resolveNineKey(
            digits = "94664",
            segmentPrefix = "",
            preferredSuffix = "xiong",
            fuzzy = false,
        )
        assertEquals(listOf("94664"), open.pinyinPaths)
    }

    @Test
    fun shortInputOffersWholeReadingsAndTheWordReadingLeads() {
        val readings = pipeline.nineKeyReadingsFor("64426", null)
        assertEquals("ni'hao", readings.first().display)
        assertTrue(readings.all { it.coversAll })
        assertTrue("mi'hao" in readings.map { it.display })
        assertTrue("ni'gao" in readings.map { it.display })
        // A lone vowel between syllables is a digit-grid artefact, not a reading.
        assertTrue(readings.none { reading -> reading.syllables.any { it.length == 1 } })
        assertEquals(readings.map { it.display }.distinct(), readings.map { it.display })
    }

    @Test
    fun readingPreviewLeadsWhenItIsOneOfTheReadings() {
        val readings = pipeline.nineKeyReadingsFor("64426", "migao")
        assertEquals("mi'gao", readings.first().display)
    }

    @Test
    fun longInputOffersFirstSyllablesOnlyAndNeverADeadEnd() {
        val readings = pipeline.nineKeyReadingsFor("9694264244326", null)
        assertTrue(readings.isNotEmpty())
        assertTrue(readings.all { it.syllables.size == 1 })
        assertEquals("wo", readings.first().syllables.single())
    }

    @Test
    fun aLoneDigitOffersLettersThatAreOrientationOnly() {
        val readings = pipeline.nineKeyReadingsFor("9", null)
        assertEquals(listOf("w", "x", "y", "z"), readings.map { it.display })
        assertTrue(readings.none { it.complete })
        assertTrue(pipeline.nineKeyReadingsFor("", null).isEmpty())
    }

    @Test
    fun previewReadingFollowsTheWordNotTheDecoderGuess() {
        // The unit environment only has the compact lexicon; the full sentence
        // (我想吃饭 -> wo xiang chi fan) is covered end to end on a device.
        assertEquals(listOf("zhong", "guo"), pipeline.nineKeyReadingFor("94664486", "中国"))
        assertEquals(listOf("ni", "hao"), pipeline.nineKeyReadingFor("64426", "你好"))
        // Digits must be consumed exactly: a shorter word or another spelling is no reading.
        assertNull(pipeline.nineKeyReadingFor("64426", "你"))
        assertNull(pipeline.nineKeyReadingFor("64426", "我想"))
        assertNull(pipeline.nineKeyReadingFor("64426", "nihao"))
    }

    @Test
    fun wordsTheDigitsSpellExactlyComeBeforePredictions() {
        // 64 spells ni/mi: 你 fits; 你好 needs three more digits (a prediction).
        assertEquals(
            listOf("你", "你好"),
            pipeline.preferExactNineKeyMatches("64", listOf("你好", "你")),
        )
        // The same holds for a code whose first syllable the user fixed.
        assertEquals(
            listOf("你好", "你"),
            pipeline.preferExactNineKeyMatches("ni'426", listOf("你", "你好")),
        )
        // Nothing exact (or nothing else): Rime's order is left alone.
        assertEquals(listOf("你好"), pipeline.preferExactNineKeyMatches("64", listOf("你好")))
        assertEquals(listOf("你好", "你敢好"), pipeline.preferExactNineKeyMatches("9", listOf("你好", "你敢好")))
        assertEquals(listOf("a", "b"), pipeline.preferExactNineKeyMatches(null, listOf("a", "b")))
    }
}
