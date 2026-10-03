package llc.slacker.openime

import llc.slacker.openime.keyboard.KeyboardLayoutMetrics
import llc.slacker.openime.theme.ImeGeometryTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Metrics follow the reference design's 390-unit canvas: a 54dp portrait key
 * row, rows that carry their own inner margin (so stacked rows add no extra
 * gap), and a body that is the top zone plus four rows plus 16dp of padding.
 */
class KeyboardLayoutMetricsTest {
    @Test
    fun portraitUsesTheReferenceRowHeightAtDefaultFontScale() {
        val metrics = KeyboardLayoutMetrics(landscape = false, fontScale = 1f)
        assertEquals(ImeGeometryTokens.KEY_ROW_HEIGHT_DP, metrics.keyRowHeightDp)
        assertEquals(
            ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP + ImeGeometryTokens.KEY_ROW_HEIGHT_DP * 4 + 16,
            metrics.imeHeightDp,
        )
        assertEquals(
            metrics.imeHeightDp - metrics.topZoneHeightDp,
            metrics.keyboardBodyHeightDp,
        )
        // A key row stays a comfortable touch target.
        assertTrue(metrics.keyRowHeightDp >= ImeGeometryTokens.TOUCH_TARGET_DP)
    }

    @Test
    fun landscapeUsesCompactBaseHeight() {
        val metrics = KeyboardLayoutMetrics(landscape = true, fontScale = 1f)
        assertEquals(ImeGeometryTokens.LANDSCAPE_KEY_ROW_HEIGHT_DP, metrics.keyRowHeightDp)
        assertTrue(metrics.imeHeightDp >= 256)
    }

    @Test
    fun largeFontGrowthIsBoundedToTwelveDp() {
        val portrait = KeyboardLayoutMetrics(landscape = false, fontScale = 3f)
        val landscape = KeyboardLayoutMetrics(landscape = true, fontScale = 3f)
        assertEquals(ImeGeometryTokens.KEY_ROW_HEIGHT_DP + 12, portrait.keyRowHeightDp)
        assertEquals(ImeGeometryTokens.LANDSCAPE_KEY_ROW_HEIGHT_DP + 12, landscape.keyRowHeightDp)
    }

    @Test
    fun userHeightScaleStaysWithinTheSupportedRange() {
        val smallest = KeyboardLayoutMetrics(landscape = false, fontScale = 1f, heightPercent = 10)
        val largest = KeyboardLayoutMetrics(landscape = false, fontScale = 1f, heightPercent = 500)
        // Out-of-range requests are clamped to 80%..120%, then floored at 44dp.
        assertEquals(maxOf(44, ImeGeometryTokens.KEY_ROW_HEIGHT_DP * 80 / 100), smallest.keyRowHeightDp)
        assertEquals(ImeGeometryTokens.KEY_ROW_HEIGHT_DP * 120 / 100, largest.keyRowHeightDp)
    }

    @Test
    fun nineKeyDerivedHeightsRemainInternallyConsistent() {
        val metrics = KeyboardLayoutMetrics(landscape = false, fontScale = 1.25f)
        // Rows own their inner margin, so the grid is exactly three rows and the
        // body exactly one more.
        assertEquals(metrics.keyRowHeightDp * 3, metrics.nineGridHeightDp)
        assertEquals(metrics.nineGridHeightDp + metrics.keyRowHeightDp, metrics.nineBodyHeightDp)
        assertEquals(
            metrics.keyRowHeightDp * 2 + ImeGeometryTokens.KEY_ROW_GAP_DP,
            metrics.doubleKeyHeightDp,
        )
    }
}
