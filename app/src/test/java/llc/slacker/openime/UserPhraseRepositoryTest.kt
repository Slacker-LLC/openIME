package llc.slacker.openime

import llc.slacker.openime.data.UserPhraseRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserPhraseRepositoryTest {

    @After
    fun cleanUp() {
        UserPhraseRepository.clear()
    }

    @Test
    fun oneAccidentalChoiceIsNotPromoted() {
        UserPhraseRepository.clear()
        UserPhraseRepository.record("nihao", "误选词")
        assertTrue(UserPhraseRepository.candidatesFor("nihao").isEmpty())
    }

    @Test
    fun repeatedFallbackChoiceCanBeRecoveredWhenRimeIsUnavailable() {
        UserPhraseRepository.clear()
        repeat(3) { UserPhraseRepository.record("nihao", "常用词") }
        assertEquals(listOf("常用词"), UserPhraseRepository.candidatesFor("nihao"))
    }

    @Test
    fun manualHotwordsLeadTheVoiceListAndBypassTheFrequencyGate() {
        UserPhraseRepository.clear()
        UserPhraseRepository.setManualVoiceHotwords(listOf(" 澎湃OS ", "澎湃OS", "", "语音识别"))
        assertEquals(listOf("澎湃OS", "语音识别"), UserPhraseRepository.manualVoiceHotwords())
        // 只选过一次的词不满足频率门槛；手动热词不受门槛限制。
        UserPhraseRepository.record("nihao", "语音识别")
        assertEquals(listOf("澎湃OS", "语音识别"), UserPhraseRepository.voiceHotwords())
    }

    @Test
    fun manualHotwordsAreCappedAtSixtyFour() {
        UserPhraseRepository.clear()
        UserPhraseRepository.setManualVoiceHotwords((1..80).map { "词${it}号" })
        assertEquals(64, UserPhraseRepository.manualVoiceHotwords().size)
    }
}
