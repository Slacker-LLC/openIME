package llc.slacker.openime

import llc.slacker.openime.keyboard.DoubleSpacePeriodPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 双击空格键输入句号: the second space inside the reference's 200 ms window. */
class DoubleSpacePeriodPolicyTest {

    @Test
    fun aSecondSpaceInsideTheWindowConverts() {
        assertTrue(DoubleSpacePeriodPolicy.converts(0L, 'a'))
        assertTrue(DoubleSpacePeriodPolicy.converts(150L, '好'))
        // The window itself is inclusive: the reference compares with 0xc8.
        assertTrue(DoubleSpacePeriodPolicy.converts(200L, 'a'))
    }

    @Test
    fun aSlowerSecondSpaceStaysASpace() {
        assertFalse(DoubleSpacePeriodPolicy.converts(201L, 'a'))
        assertFalse(DoubleSpacePeriodPolicy.converts(2_000L, 'a'))
    }

    @Test
    fun aFirstSpaceIsNeverConverted() {
        // The timestamp the service keeps starts unset, so the elapsed time is
        // the whole uptime and no reading of the text can turn a space into 。
        assertFalse(DoubleSpacePeriodPolicy.converts(System.currentTimeMillis(), 'a'))
        // A char the editor could not answer leaves the space alone.
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, null))
    }

    @Test
    fun aSpaceAfterPunctuationStaysASpace() {
        // The reference's gate list, one from each group of it.
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '，'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '。'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '？'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '：'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '“'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '》'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, ','))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, '!'))
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, ']'))
        // A space itself is in the list too.
        assertFalse(DoubleSpacePeriodPolicy.converts(10L, ' '))
        // … is not: the reference only lists the marks above.
        assertTrue(DoubleSpacePeriodPolicy.converts(10L, '…'))
    }

    @Test
    fun thePeriodIsTheFullWidthOne() {
        assertTrue(DoubleSpacePeriodPolicy.PERIOD == "。")
    }
}
