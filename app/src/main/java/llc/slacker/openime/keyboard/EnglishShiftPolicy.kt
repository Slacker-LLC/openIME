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

    /**
     * The accessible name of the Shift key. The tap gesture is ours to draw but
     * the reference announces it, so the unlocked states carry its own guide
     * text 「双击大写键锁定大写输入」 (`string/caps_lock_guide_text`,
     * 0x7f1100de) the way the enter key announces 「发送，长按换行」.
     */
    fun description(state: ShiftState): String = when (state) {
        ShiftState.LOWERCASE -> "大写，双击锁定大写输入"
        ShiftState.SHIFT_ONCE -> "大写一次，双击锁定大写输入"
        ShiftState.CAPS_LOCK -> "大写锁定"
    }
}
