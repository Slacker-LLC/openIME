package llc.slacker.openime.keyboard

import android.text.InputType
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
}
