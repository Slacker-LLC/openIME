package llc.slacker.openime

import llc.slacker.openime.keyboard.LetterHintPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterHintPolicyTest {
    private val letters = ('a'..'z').toList()

    @Test
    fun theTopRowCarriesTheDigitsInKeyboardOrder() {
        listOf(false, true).forEach { english ->
            assertEquals(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                "qwertyuiop".map { LetterHintPolicy.hint(it, english) },
            )
        }
    }

    @Test
    fun everyLetterHasAHintInBothLanguages() {
        listOf(false, true).forEach { english ->
            letters.forEach { assertNotNull("$it english=$english", LetterHintPolicy.hint(it, english)) }
        }
    }

    @Test
    fun noHintIsRepeatedWithinALayout() {
        listOf(false, true).forEach { english ->
            val hints = letters.map { LetterHintPolicy.hint(it, english) }
            assertEquals("english=$english", hints.size, hints.toSet().size)
        }
    }

    /** The reference product's English marks, key by key. */
    @Test
    fun englishFollowsTheReferenceMarks() {
        assertEquals(
            listOf("-", "/", ":", ";", "(", ")", "~", "'", "\"")
                + listOf("@", "_", "#", "&", "?", "!", "…"),
            "asdfghjkl".map { LetterHintPolicy.hint(it, english = true) } +
                "zxcvbnm".map { LetterHintPolicy.hint(it, english = true) },
        )
    }

    /** The reference product's Chinese marks: the full-width form of the same row. */
    @Test
    fun chineseFollowsTheReferenceMarks() {
        assertEquals(
            listOf("-", "/", "：", "；", "（", "）", "～", "“", "”")
                + listOf("@", ".", "#", "、", "？", "！", "…"),
            "asdfghjkl".map { LetterHintPolicy.hint(it, english = false) } +
                "zxcvbnm".map { LetterHintPolicy.hint(it, english = false) },
        )
    }

    @Test
    fun marksWithoutAFullWidthFormAreTheSameInBothLanguages() {
        "aszcm".forEach {
            assertEquals("$it", LetterHintPolicy.hint(it, true), LetterHintPolicy.hint(it, false))
        }
    }

    @Test
    fun capitalLettersAndNonLettersHaveNoHint() {
        assertTrue(LetterHintPolicy.hint('Q', false) == null)
        assertTrue(LetterHintPolicy.hint('1', false) == null)
    }

    @Test
    fun longPressOffersTheLetterInBothCasesUpperFirst() {
        assertEquals(listOf("A", "a"), LetterHintPolicy.caseChoices('a'))
        assertEquals(listOf("Q", "q"), LetterHintPolicy.caseChoices('q'))
        assertEquals(listOf("S", "s"), LetterHintPolicy.caseChoices('s'))
    }
}
