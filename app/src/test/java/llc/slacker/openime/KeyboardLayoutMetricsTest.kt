package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutMetricsTest {
    @Test
    fun portraitUsesTouchTargetHeightAtDefaultFontScale() {
        val metrics = KeyboardLayoutMetrics(landscape = false, fontScale = 1f)
        assertEquals(ImeGeometryTokens.TOUCH_TARGET_DP, metrics.keyRowHeightDp)
        assertTrue(metrics.imeHeightDp >= 302)
        assertEquals(
            metrics.imeHeightDp - metrics.topZoneHeightDp,
            metrics.keyboardBodyHeightDp,
        )
    }

    @Test
    fun landscapeUsesCompactBaseHeight() {
        val metrics = KeyboardLayoutMetrics(landscape = true, fontScale = 1f)
        assertEquals(ImeGeometryTokens.LANDSCAPE_KEY_ROW_HEIGHT_DP, metrics.keyRowHeightDp)
        assertTrue(metrics.imeHeightDp >= 264)
    }

    @Test
    fun largeFontGrowthIsBoundedToTwelveDp() {
        val portrait = KeyboardLayoutMetrics(landscape = false, fontScale = 3f)
        val landscape = KeyboardLayoutMetrics(landscape = true, fontScale = 3f)
        assertEquals(ImeGeometryTokens.TOUCH_TARGET_DP + 12, portrait.keyRowHeightDp)
        assertEquals(ImeGeometryTokens.LANDSCAPE_KEY_ROW_HEIGHT_DP + 12, landscape.keyRowHeightDp)
    }

    @Test
    fun nineKeyDerivedHeightsRemainInternallyConsistent() {
        val metrics = KeyboardLayoutMetrics(landscape = false, fontScale = 1.25f)
        assertEquals(
            metrics.keyRowHeightDp * 3 + ImeGeometryTokens.KEY_ROW_GAP_DP * 2,
            metrics.nineGridHeightDp,
        )
        assertEquals(
            metrics.nineGridHeightDp + ImeGeometryTokens.KEY_ROW_GAP_DP + metrics.keyRowHeightDp,
            metrics.nineBodyHeightDp,
        )
        assertEquals(
            metrics.keyRowHeightDp * 2 + ImeGeometryTokens.KEY_ROW_GAP_DP,
            metrics.doubleKeyHeightDp,
        )
    }
}
