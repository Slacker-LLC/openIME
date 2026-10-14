package llc.slacker.openime

import llc.slacker.openime.keyboard.RecentSymbolStripPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentSymbolStripPolicyTest {
    /**
     * The toolbar strip reads `[4] 历史符号(308) [1] [3] 关闭(40) [4]`, the same
     * shape the reference gives its candidate strip (`input_toolbar_quickinput.xml:6-15`).
     */
    @Test
    fun theStripKeepsTheReferencesOwnRowShape() {
        assertEquals(4, RecentSymbolStripPolicy.LEAD_UNITS)
        assertEquals(3, RecentSymbolStripPolicy.GAP_UNITS)
        assertEquals(40, RecentSymbolStripPolicy.CLOSE_UNITS)
        assertEquals(4, RecentSymbolStripPolicy.TAIL_UNITS)
    }

    /** One cell is the reference's 36-unit box with its radius-6 press face. */
    @Test
    fun eachCellCarriesTheReferencesOwnBox() {
        assertEquals(36, RecentSymbolStripPolicy.CELL_UNITS)
        assertEquals(6, RecentSymbolStripPolicy.CELL_RADIUS_DP)
        assertEquals(4, RecentSymbolStripPolicy.CELL_GAP_UNITS)
        assertEquals(3, RecentSymbolStripPolicy.CELL_PAD_DP)
        // The close key is the widest thing in the row after the list itself.
        assertTrue(RecentSymbolStripPolicy.CLOSE_UNITS > RecentSymbolStripPolicy.CELL_UNITS)
    }
}
