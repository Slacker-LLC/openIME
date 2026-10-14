package llc.slacker.openime

import llc.slacker.openime.candidate.EnglishLexicon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishLexiconTest {

    private val lexicon = EnglishLexicon.fromEntries(
        listOf(
            "hello" to 779,
            "help" to 200,
            "head" to 300,
            "he" to 40,
            "world" to 500,
        ),
    )

    @Test
    fun prefixMatchesRankByFrequency() {
        assertEquals(listOf("he", "help", "head", "hello"), lexicon.complete("he", 10))
    }

    @Test
    fun limitKeepsTheMostFrequent() {
        assertEquals(listOf("he", "help"), lexicon.complete("he", 2))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(listOf("he", "help", "head", "hello"), lexicon.complete("HE", 10))
    }

    @Test
    fun unknownPrefixIsEmpty() {
        assertTrue(lexicon.complete("zzz", 5).isEmpty())
        assertTrue(lexicon.complete("", 5).isEmpty())
    }
}
