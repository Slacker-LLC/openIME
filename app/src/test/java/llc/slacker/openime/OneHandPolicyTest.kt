package llc.slacker.openime

import llc.slacker.openime.core.OneHandMode
import llc.slacker.openime.keyboard.OneHandPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneHandPolicyTest {

    @Test
    fun offKeepsTheSymmetricLayout() {
        assertNull(OneHandPolicy.insets(1200, OneHandMode.OFF, baseInsetPx = 60))
    }

    @Test
    fun leftHandAnchorsTheKeyboardToTheLeft() {
        val insets = OneHandPolicy.insets(1000, OneHandMode.LEFT, baseInsetPx = 0)!!
        assertEquals(0, insets.first)
        assertEquals(240, insets.second) // 1000 - 760 wide keyboard
    }

    @Test
    fun rightHandAnchorsTheKeyboardToTheRight() {
        val insets = OneHandPolicy.insets(1000, OneHandMode.RIGHT, baseInsetPx = 0)!!
        assertEquals(240, insets.first)
        assertEquals(0, insets.second)
    }

    @Test
    fun baseInsetIsKeptOnTheAnchoredSide() {
        val insets = OneHandPolicy.insets(1000, OneHandMode.LEFT, baseInsetPx = 50)!!
        assertEquals(50, insets.first)
        assertEquals(190, insets.second) // 1000 - 760 - 50
    }

    @Test
    fun theKeyboardKeepsTheRequestedWidth() {
        val width = 1400
        for (mode in listOf(OneHandMode.LEFT, OneHandMode.RIGHT)) {
            val insets = OneHandPolicy.insets(width, mode, baseInsetPx = 40)!!
            val content = width - insets.first - insets.second
            assertEquals((width * OneHandPolicy.WIDTH_FRACTION).toInt(), content)
        }
    }

    @Test
    fun tinyWindowsNeverProduceNegativeInsets() {
        val insets = OneHandPolicy.insets(100, OneHandMode.RIGHT, baseInsetPx = 80)!!
        assertEquals(true, insets.first >= 0 && insets.second >= 0)
    }
}
