package llc.slacker.openime

import llc.slacker.openime.keyboard.NineKeyLongPressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyLongPressPolicyTest {
    @Test
    fun offersTheDigitAndBothCasesOfTheLetters() {
        assertEquals(
            listOf(listOf("2", "a", "b", "c"), listOf("A", "B", "C")),
            NineKeyLongPressPolicy.choiceRows("2"),
        )
        assertEquals(
            listOf(listOf("9", "w", "x", "y", "z"), listOf("W", "X", "Y", "Z")),
            NineKeyLongPressPolicy.choiceRows("9"),
        )
    }

    @Test
    fun everyRowFitsTheLongestPopup() {
        ('2'..'9').forEach { digit ->
            NineKeyLongPressPolicy.choiceRows(digit.toString()).forEach { row ->
                assertTrue("row for $digit is too wide: $row", row.size <= 5)
            }
        }
    }

    @Test
    fun keysWithoutLettersKeepTheirOwnBehavior() {
        listOf("1", "0", "*", "#").forEach {
            assertTrue(NineKeyLongPressPolicy.choiceRows(it).isEmpty())
        }
    }
}
