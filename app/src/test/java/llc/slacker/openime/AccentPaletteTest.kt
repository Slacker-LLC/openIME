package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPaletteTest {
    @Test
    fun exposesTheProductAccentPaletteInDesignOrder() {
        assertEquals("#1D9BF0", AccentPalette.DEFAULT)
        assertEquals(
            listOf(
                "#1D9BF0" to "蓝色",
                "#FFD400" to "黄色",
                "#F91880" to "粉色",
                "#7856FF" to "紫色",
                "#FF7A00" to "橙色",
                "#00BA7C" to "绿色",
                "#00C2D7" to "青色",
                "#38BDF8" to "天蓝",
                "#5865F2" to "靛蓝",
                "#9B5DE5" to "深紫",
                "#E94FB8" to "洋红",
                "#F4212E" to "红色",
                "#FF5A5F" to "珊瑚红",
                "#F59E0B" to "琥珀",
                "#84CC16" to "青柠",
                "#22C55E" to "翠绿",
                "#10CFA0" to "薄荷",
                "#14B8A6" to "蓝绿",
            ),
            AccentPalette.presets,
        )
    }

    @Test
    fun everyPresetIsAValidSixDigitColor() {
        assertTrue(AccentPalette.presets.all { (hex, _) -> AccentPalette.normalize(hex) == hex })
    }

    @Test
    fun focusRingUsesAccentWhenItHasEnoughContrast() {
        val accent = 0xff00c2d7.toInt()
        assertEquals(accent, ImeFocusRingPolicy.resolve(0xff303238.toInt(), accent))
    }

    @Test
    fun focusRingFallsBackToDarkHighContrastForBrightAccentOnLightSurface() {
        assertEquals(
            0xff0f172a.toInt(),
            ImeFocusRingPolicy.resolve(0xffffffff.toInt(), 0xffffd400.toInt()),
        )
    }
}
