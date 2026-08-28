package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(
            engine.getEnglishCompletions("hel"),
            pipeline.candidatesFor(KeyboardMode.ENGLISH_26, "hel", false),
        )
        assertEquals(
            engine.getT9EnglishCandidates("435"),
            pipeline.candidatesFor(KeyboardMode.ENGLISH_T9, "435", false),
        )
        assertTrue(pipeline.candidatesFor(KeyboardMode.DIGITS, "123", false).isEmpty())
    }

    @Test
    fun nineKeyResolutionOwnsPreviewPathsAndCandidateOrdering() {
        val resolution = pipeline.resolveNineKey(
            digits = "64426",
            segmentPrefix = "",
            preferredSuffix = "nihao",
            fuzzy = false,
        )

        assertEquals("nihao", resolution.preview)
        assertEquals("nihao", resolution.pinyinPaths.first())
        assertTrue(resolution.candidates.isNotEmpty())
        assertFalse(resolution.candidates.any { candidate -> candidate.any(Char::isDigit) })
        assertEquals(resolution.candidates.distinct(), resolution.candidates)
    }

    @Test
    fun segmentedNineKeyKeepsPrefixAheadOfRawDigitCandidates() {
        val resolution = pipeline.resolveNineKey(
            digits = "426",
            segmentPrefix = "ni ",
            preferredSuffix = "hao",
            fuzzy = false,
        )

        assertEquals("ni hao", resolution.preview)
        assertTrue(resolution.pinyinPaths.all { it.startsWith("ni ") })
        assertTrue(resolution.candidates.none { it.any(Char::isDigit) })
    }

    @Test
    fun nineKeyInputIsBoundedBeforeEngineResolution() {
        val resolution = pipeline.resolveNineKey(
            digits = "6".repeat(CandidateEngine.MAX_NINE_KEY_DIGITS + 20),
            segmentPrefix = "",
            preferredSuffix = null,
            fuzzy = false,
        )

        assertTrue(resolution.pinyinPaths.size <= 8)
        assertTrue(resolution.candidates.size <= 96)
    }
}
