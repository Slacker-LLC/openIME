package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishCandidatePolicyTest {
    private val pipeline = CandidatePipeline(CandidateEngine())

    @Test
    fun exactTypedTextAlwaysStaysFirst() {
        assertEquals(
            "cod",
            pipeline.candidatesFor(KeyboardMode.ENGLISH_26, "cod", fuzzy = false).first(),
        )
    }

    @Test
    fun titleCaseSuggestionsPreserveShiftIntent() {
        val candidates = pipeline.candidatesFor(KeyboardMode.ENGLISH_26, "Cod", fuzzy = false)
        assertEquals("Cod", candidates.first())
        assertTrue(candidates.contains("Code"))
    }

    @Test
    fun allCapsSuggestionsPreserveCapsLockIntent() {
        val candidates = pipeline.candidatesFor(KeyboardMode.ENGLISH_26, "COD", fuzzy = false)
        assertEquals("COD", candidates.first())
        assertTrue(candidates.contains("CODE"))
    }
}
