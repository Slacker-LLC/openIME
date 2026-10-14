package llc.slacker.openime

import llc.slacker.openime.keyboard.NineKeyLongPressPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyLongPressPolicyTest {
    @Test
    fun offersTheDigitAndBothCasesWithLowercaseNearestTheThumb() {
        assertEquals(
            listOf(listOf("A", "B", "C"), listOf("2", "a", "b", "c")),
            NineKeyLongPressPolicy.choiceRows("2"),
        )
        assertEquals(
            listOf(listOf("W", "X", "Y", "Z"), listOf("9", "w", "x", "y", "z")),
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

    @Test
    fun theAtSymbolKeyOffersTheReferenceBubble() {
        // input_kbd_pinyin9.xml:26-27: the @# key's seven symbols, in order.
        assertEquals(
            listOf("/", "-", "_", "1", "@", "#", "."),
            NineKeyLongPressPolicy.AT_SYMBOL_CHOICES,
        )
    }
}
