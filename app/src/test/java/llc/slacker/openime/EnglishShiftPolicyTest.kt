package llc.slacker.openime

import android.text.InputType
import llc.slacker.openime.core.ShiftState
import llc.slacker.openime.keyboard.EnglishShiftPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishShiftPolicyTest {
    private val text = InputType.TYPE_CLASS_TEXT

    @Test
    fun sentenceAndWordCapitalsRequestedByTheEditorAreIgnored() {
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.initial(text or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, false))
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.initial(text or InputType.TYPE_TEXT_FLAG_CAP_WORDS, false))
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.initial(text, false))
    }

    @Test
    fun aCapitalsOnlyFieldStillStartsInCapsLock() {
        assertEquals(ShiftState.CAPS_LOCK, EnglishShiftPolicy.initial(text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, false))
    }

    @Test
    fun passwordFieldsAlwaysStartLowercase() {
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.initial(text or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS, true))
    }

    @Test
    fun aTapGivesOneCapitalAndAQuickSecondTapLocksCaps() {
        assertEquals(ShiftState.SHIFT_ONCE, EnglishShiftPolicy.afterTap(ShiftState.LOWERCASE, null))
        assertEquals(ShiftState.CAPS_LOCK, EnglishShiftPolicy.afterTap(ShiftState.SHIFT_ONCE, 150))
        assertEquals(
            ShiftState.CAPS_LOCK,
            EnglishShiftPolicy.afterTap(ShiftState.SHIFT_ONCE, EnglishShiftPolicy.DOUBLE_TAP_MS),
        )
    }

    @Test
    fun aSlowSecondTapOrATapOnCapsLockTurnsShiftOff() {
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.afterTap(ShiftState.SHIFT_ONCE, 900))
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.afterTap(ShiftState.SHIFT_ONCE, null))
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.afterTap(ShiftState.CAPS_LOCK, 100))
    }

    @Test
    fun aLongPressLocksCapsFromAnyStateAndReleasesTheLock() {
        assertEquals(ShiftState.CAPS_LOCK, EnglishShiftPolicy.afterLongPress(ShiftState.LOWERCASE))
        assertEquals(ShiftState.CAPS_LOCK, EnglishShiftPolicy.afterLongPress(ShiftState.SHIFT_ONCE))
        assertEquals(ShiftState.LOWERCASE, EnglishShiftPolicy.afterLongPress(ShiftState.CAPS_LOCK))
    }
}
