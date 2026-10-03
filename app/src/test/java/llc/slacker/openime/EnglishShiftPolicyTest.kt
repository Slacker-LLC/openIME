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
}
