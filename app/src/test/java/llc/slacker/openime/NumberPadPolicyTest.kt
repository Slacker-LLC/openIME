package llc.slacker.openime

import llc.slacker.openime.keyboard.NumberPadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberPadPolicyTest {
    /** The reference's row is 66 / 75 / 75 / 75 / 66 (`input_kbd_number_from26.xml:8,14-38`). */
    @Test
    fun theRowKeepsTheReferencesColumnWidths() {
        assertEquals(66f, NumberPadPolicy.RAIL_COLUMN_WEIGHT, 0f)
        assertEquals(75f, NumberPadPolicy.DIGIT_COLUMN_WEIGHT, 0f)
        assertEquals(66f, NumberPadPolicy.SEND_COLUMN_WEIGHT, 0f)
    }

    /** 156 against 52 is the rail's three rows against the return key's one. */
    @Test
    fun theRailIsThreeKeyRowsAndTheReturnKeyTheFourth() {
        assertEquals(3, NumberPadPolicy.RAIL_ROWS)
        assertEquals(1, NumberPadPolicy.BACK_ROWS)
        assertEquals(
            "height_weight 156 against 52",
            3 * NumberPadPolicy.BACK_ROWS,
            NumberPadPolicy.RAIL_ROWS,
        )
    }

    /** Send is twice as tall as the delete and space keys above it. */
    @Test
    fun sendIsTwiceAsTallAsTheKeysAboveIt() {
        assertEquals(2, NumberPadPolicy.SEND_ROWS)
        assertEquals(
            "the four rows of the digit columns",
            NumberPadPolicy.BACK_ROWS * 4,
            NumberPadPolicy.SEND_ROWS + NumberPadPolicy.BACK_ROWS * 2,
        )
    }

    /** Every digit column closes with its own fourth cell, read off the reference. */
    @Test
    fun eachDigitColumnClosesWithItsOwnCell() {
        assertEquals(listOf("符号", "0", "."), NumberPadPolicy.COLUMN_TAILS)
        assertEquals(
            NumberPadPolicy.COLUMN_TAILS.size,
            NumberPadPolicy.DIGIT_COLUMN_TOPS.size,
        )
    }

    /** The digits read down the columns, and all nine appear once. */
    @Test
    fun theDigitsReadDownTheColumnsAndCoverOneToNine() {
        assertEquals(
            listOf(listOf("1", "4", "7"), listOf("2", "5", "8"), listOf("3", "6", "9")),
            NumberPadPolicy.DIGIT_COLUMN_TOPS,
        )
        val digits = NumberPadPolicy.DIGIT_COLUMN_TOPS.flatten()
        assertEquals(9, digits.size)
        assertEquals(('1'..'9').map(Char::toString).toSet(), digits.toSet())
        assertTrue("0 closes the second column", "0" !in digits)
    }
}
