package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
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
}
