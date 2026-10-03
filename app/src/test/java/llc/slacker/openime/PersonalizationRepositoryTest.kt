package llc.slacker.openime

import llc.slacker.openime.data.PersonalizationRepository
import llc.slacker.openime.data.UserPhraseRepository
import llc.slacker.openime.voice.VoiceCorrectionRepository
import llc.slacker.openime.voice.VoiceHotwordProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationRepositoryTest {
    @After
    fun cleanUp() {
        PersonalizationRepository.clear()
        UserPhraseRepository.clear()
        VoiceCorrectionRepository.clear()
    }

    @Test
    fun oneSelectionDoesNotImmediatelyBecomeAHotword() {
        PersonalizationRepository.clear()
        PersonalizationRepository.record("澎湃项目")
        assertTrue(PersonalizationRepository.hotwords().isEmpty())
    }

    @Test
    fun repeatedSelectionFeedsVoiceWithoutCreatingCandidateRanking() {
        PersonalizationRepository.clear()
        UserPhraseRepository.clear()
        VoiceCorrectionRepository.clear()

        repeat(2) { PersonalizationRepository.record("澎湃项目") }

        assertEquals(listOf("澎湃项目"), PersonalizationRepository.hotwords())
        assertTrue(UserPhraseRepository.candidatesFor("pengpai xiangmu").isEmpty())
        assertTrue(VoiceHotwordProvider.current().contains("澎湃项目 :1.8"))
    }
}
