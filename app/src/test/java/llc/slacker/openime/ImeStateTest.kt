package llc.slacker.openime

import llc.slacker.openime.core.ImeState
import llc.slacker.openime.core.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeStateTest {
    @Test
    fun shiftTransitions() {
        val low = ImeState(shiftState = ShiftState.LOWERCASE)
        val once = low.copy(shiftState = ShiftState.SHIFT_ONCE)
        val caps = once.copy(shiftState = ShiftState.CAPS_LOCK)
        assertEquals(ShiftState.SHIFT_ONCE, once.shiftState)
        assertEquals(ShiftState.CAPS_LOCK, caps.shiftState)
    }
}
