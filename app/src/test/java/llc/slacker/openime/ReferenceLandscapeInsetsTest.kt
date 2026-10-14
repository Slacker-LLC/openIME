package llc.slacker.openime

import llc.slacker.openime.keyboard.ReferenceLandscapeInsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏留白取自参考键盘横屏皮肤的实测值；这些数字一变，横屏观感就变，
 * 所以在这里钉死。
 */
class ReferenceLandscapeInsetsTest {

    @Test
    fun fractionsMatchTheReferenceSkin() {
        assertEquals(0.0648508f, ReferenceLandscapeInsets.KEYBOARD_FRACTION, 0.0000001f)
        assertEquals(0.095f, ReferenceLandscapeInsets.NINE_KEY_FRACTION, 0.0001f)
        assertEquals(0.082f, ReferenceLandscapeInsets.TOOLBAR_FRACTION, 0.0001f)
        assertEquals(0.095f, ReferenceLandscapeInsets.EXPANDED_CANDIDATES_FRACTION, 0.0001f)
    }

    @Test
    fun nineKeyKeepsMoreRoomThanTwentySixKey() {
        assertEquals(ReferenceLandscapeInsets.KEYBOARD_FRACTION, ReferenceLandscapeInsets.keyboardFraction(nineKey = false), 0.0000001f)
        assertEquals(ReferenceLandscapeInsets.NINE_KEY_FRACTION, ReferenceLandscapeInsets.keyboardFraction(nineKey = true), 0.0000001f)
        assertTrue(ReferenceLandscapeInsets.keyboardFraction(nineKey = true) > ReferenceLandscapeInsets.keyboardFraction(nineKey = false))
    }

    @Test
    fun contentStaysComfortablyWiderThanHalfTheWindow() {
        // 留白只是"把内容往里收一点"：两侧合计必须远小于一半宽度。
        val total = ReferenceLandscapeInsets.KEYBOARD_FRACTION * 2
        assertTrue(total < 0.2f)
        assertTrue(ReferenceLandscapeInsets.TOOLBAR_FRACTION > ReferenceLandscapeInsets.KEYBOARD_FRACTION)
    }
}
