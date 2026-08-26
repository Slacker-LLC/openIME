package llc.slacker.openime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImeStateTest {
    @Test
    fun modeSwitchKeepsPreviousMode() {
        val a = ImeState(keyboardMode = KeyboardMode.PINYIN_26)
        val b = a.withMode(KeyboardMode.ENGLISH_26)
        assertEquals(KeyboardMode.ENGLISH_26, b.keyboardMode)
        assertEquals(KeyboardMode.PINYIN_26, b.previousKeyboardMode)
    }

    @Test
    fun modeSwitchClearsComposition() {
        val a = ImeState(composition = "ni", candidates = listOf("你"))
        val b = a.withMode(KeyboardMode.ENGLISH_T9)
        assertEquals("", b.composition)
        assertEquals(emptyList<String>(), b.candidates)
    }

    @Test
    fun shiftTransitions() {
        val low = ImeState(shiftState = ShiftState.LOWERCASE)
        val once = low.copy(shiftState = ShiftState.SHIFT_ONCE)
        val caps = once.copy(shiftState = ShiftState.CAPS_LOCK)
        assertEquals(ShiftState.SHIFT_ONCE, once.shiftState)
        assertEquals(ShiftState.CAPS_LOCK, caps.shiftState)
    }
}
