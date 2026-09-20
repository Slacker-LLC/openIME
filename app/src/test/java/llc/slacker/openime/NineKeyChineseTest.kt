package llc.slacker.openime

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

    private fun resolve(digits: String): CandidatePipeline.NineKeyResolution =
        pipeline.resolveNineKey(
            digits = digits,
            segmentPrefix = "",
            preferredSuffix = null,
            fuzzy = false,
        )
}
