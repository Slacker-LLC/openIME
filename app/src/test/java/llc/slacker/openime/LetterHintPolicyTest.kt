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

    @Test
    fun englishUsesAsciiMarks() {
        assertEquals("!", LetterHintPolicy.hint('s', english = true))
        assertEquals("$", LetterHintPolicy.hint('g', english = true))
        assertEquals("?", LetterHintPolicy.hint('l', english = true))
        assertEquals("(", LetterHintPolicy.hint('z', english = true))
        assertEquals(")", LetterHintPolicy.hint('x', english = true))
        assertEquals(":", LetterHintPolicy.hint('b', english = true))
        assertEquals(";", LetterHintPolicy.hint('n', english = true))
    }

    @Test
    fun chineseUsesFullWidthMarksWhereOneExists() {
        assertEquals("！", LetterHintPolicy.hint('s', english = false))
        assertEquals("￥", LetterHintPolicy.hint('g', english = false))
        assertEquals("？", LetterHintPolicy.hint('l', english = false))
        assertEquals("（", LetterHintPolicy.hint('z', english = false))
        assertEquals("）", LetterHintPolicy.hint('x', english = false))
        assertEquals("：", LetterHintPolicy.hint('b', english = false))
        assertEquals("；", LetterHintPolicy.hint('n', english = false))
    }

    @Test
    fun marksWithoutAFullWidthFormAreTheSameInBothLanguages() {
        "adfhjkcvm".forEach {
            assertEquals("$it", LetterHintPolicy.hint(it, true), LetterHintPolicy.hint(it, false))
        }
    }

    @Test
    fun capitalLettersAndNonLettersHaveNoHint() {
        assertTrue(LetterHintPolicy.hint('Q', false) == null)
        assertTrue(LetterHintPolicy.hint('1', false) == null)
    }

    @Test
    fun longPressOffersTheOtherCaseThenTheHint() {
        assertEquals(listOf("A", "~"), LetterHintPolicy.longPressChoices('a', english = true, upperShown = false, hintsOn = true))
        assertEquals(listOf("q", "1"), LetterHintPolicy.longPressChoices('q', english = false, upperShown = true, hintsOn = true))
        assertEquals(listOf("S"), LetterHintPolicy.longPressChoices('s', english = true, upperShown = false, hintsOn = false))
    }
}
