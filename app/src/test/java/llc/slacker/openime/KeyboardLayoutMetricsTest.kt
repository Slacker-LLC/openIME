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
            ImeGeometryTokens.COMPOSED_TOP_ZONE_HEIGHT_DP + ImeGeometryTokens.KEY_ROW_HEIGHT_DP * 4 +
                ImeGeometryTokens.KEYBOARD_TOP_PAD_DP + ImeGeometryTokens.KEYBOARD_BOTTOM_PAD_DP,
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
        // The top strip is only a little taller than a key row.
        assertEquals(ImeGeometryTokens.LANDSCAPE_TOP_ZONE_HEIGHT_DP, metrics.topZoneHeightDp)
        assertTrue(metrics.topZoneHeightDp - metrics.keyRowHeightDp <= 10)
        assertEquals(metrics.topZoneHeightDp + metrics.keyRowHeightDp * 4 +
            ImeGeometryTokens.KEYBOARD_TOP_PAD_DP + ImeGeometryTokens.LANDSCAPE_BOTTOM_PAD_DP,
            metrics.imeHeightDp)
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
        // Out-of-range requests are clamped to the token range, then floored at 34dp.
        val lo = ImeGeometryTokens.KEYBOARD_HEIGHT_MIN_PERCENT
        val hi = ImeGeometryTokens.KEYBOARD_HEIGHT_MAX_PERCENT
        assertEquals(maxOf(34, ImeGeometryTokens.KEY_ROW_HEIGHT_DP * lo / 100), smallest.keyRowHeightDp)
        assertEquals(ImeGeometryTokens.KEY_ROW_HEIGHT_DP * hi / 100, largest.keyRowHeightDp)
    }

    @Test
    fun defaultHeightMatchesTheUnscaledRow() {
        val default = KeyboardLayoutMetrics(
            landscape = false, fontScale = 1f,
            heightPercent = ImeGeometryTokens.KEYBOARD_HEIGHT_DEFAULT_PERCENT,
        )
        assertEquals(ImeGeometryTokens.KEY_ROW_HEIGHT_DP, default.keyRowHeightDp)
    }

    @Test
    fun everySliderDefaultLiesInsideItsRange() {
        listOf(
            Triple(ImeGeometryTokens.KEYBOARD_HEIGHT_MIN_PERCENT, ImeGeometryTokens.KEYBOARD_HEIGHT_MAX_PERCENT, ImeGeometryTokens.KEYBOARD_HEIGHT_DEFAULT_PERCENT),
            Triple(ImeGeometryTokens.FLOATING_WIDTH_MIN_PERCENT, ImeGeometryTokens.FLOATING_WIDTH_MAX_PERCENT, ImeGeometryTokens.FLOATING_WIDTH_DEFAULT_PERCENT),
            Triple(ImeGeometryTokens.FLOATING_OPACITY_MIN_PERCENT, ImeGeometryTokens.FLOATING_OPACITY_MAX_PERCENT, ImeGeometryTokens.FLOATING_OPACITY_DEFAULT_PERCENT),
            Triple(ImeGeometryTokens.HAPTIC_STRENGTH_MIN_PERCENT, ImeGeometryTokens.HAPTIC_STRENGTH_MAX_PERCENT, ImeGeometryTokens.HAPTIC_STRENGTH_DEFAULT_PERCENT),
        ).forEach { (lo, hi, def) -> org.junit.Assert.assertTrue("$def not in $lo..$hi", def in lo..hi) }
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
