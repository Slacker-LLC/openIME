package llc.slacker.openime

import llc.slacker.openime.keyboard.KeyboardHeightDragPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardHeightDragPolicyTest {

    @Test
    fun draggingUpGrowsAndDraggingDownShrinks() {
        assertTrue(KeyboardHeightDragPolicy.percentDelta(-100f, 2400) > 0)
        assertTrue(KeyboardHeightDragPolicy.percentDelta(100f, 2400) < 0)
    }

    @Test
    fun aFifthOfTheScreenHeightCoversTheWholeRange() {
        // 屏高的 20% 对应 40 个百分点（80%..120% 的整段）。
        assertEquals(40, KeyboardHeightDragPolicy.percentDelta(-480f, 2400))
        assertEquals(1, KeyboardHeightDragPolicy.percentDelta(-12f, 2400))
    }

    @Test
    fun degenerateInputsAreSafe() {
        assertEquals(0, KeyboardHeightDragPolicy.percentDelta(0f, 2400))
        assertEquals(0, KeyboardHeightDragPolicy.percentDelta(-100f, 0))
    }

    @Test
    fun applyToMovesFromTheCurrentPercent() {
        assertEquals(105, KeyboardHeightDragPolicy.applyTo(100, -60f, 2400))
        assertEquals(95, KeyboardHeightDragPolicy.applyTo(100, 60f, 2400))
    }
}
