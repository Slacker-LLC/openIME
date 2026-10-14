package llc.slacker.openime

import llc.slacker.openime.panel.TileGridPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridPolicyTest {
    /**
     * The container takes 88.9% of the page with its own paddings and lays its
     * tiles out 4 by 2, capped at 192 units (`cls_settings_bk_adaptive`,
     * `style.xml:616-619`).
     */
    @Test
    fun theGridKeepsTheReferencesOwnRuler() {
        assertEquals(4, TileGridPolicy.COLUMNS)
        assertEquals(2, TileGridPolicy.ROWS)
        assertEquals(8, TileGridPolicy.PAGE_SIZE)
        assertEquals(0.88888f, TileGridPolicy.WIDTH_FRACTION, 0.00001f)
        assertEquals(0.05555f, TileGridPolicy.PAD_H_FRACTION, 0.00001f)
        assertEquals(0.0721f, TileGridPolicy.PAD_TOP_FRACTION, 0.00001f)
        assertEquals(0.10096f, TileGridPolicy.PAD_BOTTOM_FRACTION, 0.00001f)
        assertEquals(192, TileGridPolicy.MAX_HEIGHT_UNITS)
        assertEquals(0.05f, TileGridPolicy.CHILD_PAD_FRACTION, 0.00001f)
        assertEquals(0.11111f, TileGridPolicy.CHILD_V_PAD_FRACTION, 0.00001f)
        assertEquals(14, TileGridPolicy.TILE_RADIUS_DP)
    }

    /** A page is two rows deep; anything longer takes another row rather than a hidden entry. */
    @Test
    fun theRowsCoverEveryEntry() {
        assertEquals(2, TileGridPolicy.rows(0))
        assertEquals(2, TileGridPolicy.rows(1))
        assertEquals(2, TileGridPolicy.rows(8))
        assertEquals(3, TileGridPolicy.rows(9))
        assertEquals(3, TileGridPolicy.rows(12))
        // The glyph leaves room for the name inside the tile.
        assertTrue(TileGridPolicy.ICON_FRACTION in 0.3f..0.7f)
    }
}
