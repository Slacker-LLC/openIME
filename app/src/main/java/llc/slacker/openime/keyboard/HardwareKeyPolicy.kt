package llc.slacker.openime.keyboard

import android.view.KeyEvent

/** What a physical key should do while the keyboard is in Chinese pinyin mode. */
internal sealed interface HardwareKeyAction {
    /** Leave the key to the app. */
    data object PassThrough : HardwareKeyAction

    /** The key is ours and does nothing (e.g. a digit that selects no candidate). */
    data object Consume : HardwareKeyAction

    /** Commit what is being composed, then let the key reach the app. */
    data object FinishCompositionThenPassThrough : HardwareKeyAction

    data class Letter(val char: Char) : HardwareKeyAction
    data object Backspace : HardwareKeyAction
    data object Space : HardwareKeyAction
    data object Enter : HardwareKeyAction
    data object Cancel : HardwareKeyAction
    data object Apostrophe : HardwareKeyAction
    data class SelectCandidate(val index: Int) : HardwareKeyAction

    /** Full-width punctuation; the first candidate is committed first when a composition is open. */
    data class Punctuation(val text: String, val commitFirstCandidate: Boolean) : HardwareKeyAction
}

internal data class HardwareKey(
    val keyCode: Int,
    /** The character the key produces with the current layout and modifiers (0 for none). */
    val unicode: Int,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
    val capsLock: Boolean = false,
    val repeat: Boolean = false,
)

internal data class HardwareContext(
    /** The keyboard is in the Chinese 26-key mode and the editor accepts composing. */
    val pinyinMode: Boolean,
    val composing: Boolean,
    val candidateCount: Int,
    /** The character before the cursor, when known: "3.14" must keep its ASCII dot. */
    val charBeforeCursor: Char?,
)

/**
 * Physical-keyboard typing for Chinese: letters compose pinyin, space picks the
 * first candidate, 1-9 pick a candidate, Enter keeps the typed pinyin, Esc
 * cancels. Everything else, and every shortcut, belongs to the app. Pure, so
 * it can be tested without a device.
 */
internal object HardwareKeyPolicy {
    private val modifierKeys = setOf(
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_FUNCTION,
    )

    private val fullWidth = mapOf(
        ',' to "，", '.' to "。", '?' to "？", '!' to "！", ';' to "；", ':' to "：",
        '(' to "（", ')' to "）",
    )

    /** These keep their ASCII form right after a digit (3.14, 12:30, 1,000). */
    private val asciiAfterDigit = setOf(',', '.', ':')

    fun decide(key: HardwareKey, context: HardwareContext): HardwareKeyAction {
        if (!context.pinyinMode) return HardwareKeyAction.PassThrough
        if (key.keyCode in modifierKeys) return HardwareKeyAction.PassThrough
        if (key.ctrl || key.alt || key.meta) return HardwareKeyAction.PassThrough

        val composing = context.composing
        when (key.keyCode) {
            KeyEvent.KEYCODE_DEL -> return if (composing) HardwareKeyAction.Backspace else HardwareKeyAction.PassThrough
            KeyEvent.KEYCODE_SPACE ->
                return if (!composing) HardwareKeyAction.PassThrough
                else if (key.repeat) HardwareKeyAction.Consume else HardwareKeyAction.Space
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                return if (!composing) HardwareKeyAction.PassThrough
                else if (key.repeat) HardwareKeyAction.Consume else HardwareKeyAction.Enter
            KeyEvent.KEYCODE_ESCAPE ->
                return if (composing && !key.repeat) HardwareKeyAction.Cancel else HardwareKeyAction.PassThrough
        }

        val char = key.unicode.takeIf { it in 0x20..0x7e }?.toChar() ?: return HardwareKeyAction.PassThrough
        return when {
            char in 'a'..'z' && !key.shift && !key.capsLock -> HardwareKeyAction.Letter(char)
            // A capital letter is English: close the composition, then type it.
            char in 'A'..'Z' -> if (composing) HardwareKeyAction.FinishCompositionThenPassThrough else HardwareKeyAction.PassThrough
            char in '1'..'9' && composing ->
                if (char - '1' < context.candidateCount) HardwareKeyAction.SelectCandidate(char - '1') else HardwareKeyAction.Consume
            char == '\'' && composing -> HardwareKeyAction.Apostrophe
            char in fullWidth -> {
                val numeric = char in asciiAfterDigit && context.charBeforeCursor?.let { it in '0'..'9' } == true && !composing
                if (numeric) HardwareKeyAction.PassThrough
                else HardwareKeyAction.Punctuation(fullWidth.getValue(char), commitFirstCandidate = composing)
            }
            composing -> HardwareKeyAction.FinishCompositionThenPassThrough
            else -> HardwareKeyAction.PassThrough
        }
    }
}
