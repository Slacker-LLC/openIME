package llc.slacker.openime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCorrectionRepositoryTest {
    @After
    fun cleanup() {
        VoiceCorrectionRepository.clear()
    }

    @Test
    fun exactCorrectionIsAppliedLocally() {
        VoiceCorrectionRepository.record("开放爱慕", "openIME")
        assertEquals("openIME", VoiceCorrectionRepository.apply("开放爱慕"))
        assertEquals("其他文本", VoiceCorrectionRepository.apply("其他文本"))
    }

    @Test
    fun correctedPhraseFeedsFutureHotwords() {
        VoiceCorrectionRepository.record("彭拜系统", "澎湃系统")
        assertTrue(VoiceCorrectionRepository.hotwords().contains("澎湃系统"))
    }
}
