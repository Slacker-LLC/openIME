package llc.slacker.openime

import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyUiStateTest {

    @Test
    fun manualPathChoiceSurvivesIncompleteNextSyllablePrefix() {
        NineKeyUiState.clear()
        val pipeline = CandidatePipeline(CandidateEngine())

        val ambiguous = pipeline.resolveNineKey(
            digits = "64",
            segmentPrefix = "",
            preferredSuffix = "ni",
            fuzzy = false,
        )
        assertTrue("64 must expose ni as a selectable path", "ni" in ambiguous.displayPinyinPaths)
        NineKeyUiState.select("64", "ni")

        val extended = pipeline.resolveNineKey(
            digits = "642",
            segmentPrefix = "",
            preferredSuffix = null,
            fuzzy = false,
        )

        assertTrue(
            "manual ni path should continue as nia... instead of switching to another 64 path: ${extended.preview}",
            extended.preview.startsWith("nia"),
        )
    }
}
