package llc.slacker.openime.keyboard

import android.text.InputType
import llc.slacker.openime.R
import llc.slacker.openime.core.ShiftState

/**
 * Starting Shift state of the English 26-key surface.
 *
 * English mode never capitalizes on its own: sentence and word auto-capitals
 * requested by the editor are ignored, and keys show lowercase letters. Only a
 * field that demands capitals (TYPE_TEXT_FLAG_CAP_CHARACTERS) starts in Caps
 * Lock; the user can still tap Shift for a capital.
 */
internal object EnglishShiftPolicy {
    fun initial(inputType: Int, password: Boolean): ShiftState =
        if (!password && inputType and InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS != 0) {
            ShiftState.CAPS_LOCK
        } else {
            ShiftState.LOWERCASE
        }

    /** Two taps closer than this lock Caps. */
    const val DOUBLE_TAP_MS = 400L

    /**
     * A tap on Shift: off becomes one capital; a second quick tap locks Caps
     * (the double tap Gboard and SwiftKey use); a slow second tap or a tap on
     * Caps Lock turns it off.
     */
    fun afterTap(current: ShiftState, sinceLastTapMs: Long?): ShiftState = when (current) {
        ShiftState.LOWERCASE -> ShiftState.SHIFT_ONCE
        ShiftState.SHIFT_ONCE ->
            if (sinceLastTapMs != null && sinceLastTapMs <= DOUBLE_TAP_MS) ShiftState.CAPS_LOCK else ShiftState.LOWERCASE
        ShiftState.CAPS_LOCK -> ShiftState.LOWERCASE
    }

    /** A long press on Shift locks Caps at once, or releases the lock. */
    fun afterLongPress(current: ShiftState): ShiftState =
        if (current == ShiftState.CAPS_LOCK) ShiftState.LOWERCASE else ShiftState.CAPS_LOCK

    /** Outline = off, solid = one capital, solid with a bar = Caps Lock. */
    fun icon(state: ShiftState): Int = when (state) {
        ShiftState.LOWERCASE -> R.drawable.ic_shift
        ShiftState.SHIFT_ONCE -> R.drawable.ic_shift_on
        ShiftState.CAPS_LOCK -> R.drawable.ic_caps_lock
    }
}
