package llc.slacker.openime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class HardwareKeyPolicyTest {
    private val typing = HardwareContext(pinyinMode = true, composing = true, candidateCount = 5, charBeforeCursor = null)
    private val idle = typing.copy(composing = false, candidateCount = 0)

    private fun key(code: Int, char: Char? = null, shift: Boolean = false, ctrl: Boolean = false, caps: Boolean = false, repeat: Boolean = false) =
        HardwareKey(code, char?.code ?: 0, shift = shift, ctrl = ctrl, capsLock = caps, repeat = repeat)

    private fun letter(c: Char) = key(KeyEvent.KEYCODE_A + (c - 'a'), c)

    @Test
    fun lettersComposePinyinWhetherOrNotAnythingIsOpen() {
        assertEquals(HardwareKeyAction.Letter('n'), HardwareKeyPolicy.decide(letter('n'), idle))
        assertEquals(HardwareKeyAction.Letter('i'), HardwareKeyPolicy.decide(letter('i'), typing))
    }

    @Test
    fun otherModesAndShortcutsBelongToTheApp() {
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(letter('a'), typing.copy(pinyinMode = false)))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_C, 'c', ctrl = true), typing))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_SHIFT_LEFT), typing))
    }

    @Test
    fun capitalLettersAreEnglishAndCloseTheComposition() {
        val a = key(KeyEvent.KEYCODE_A, 'A', shift = true)
        assertEquals(HardwareKeyAction.FinishCompositionThenPassThrough, HardwareKeyPolicy.decide(a, typing))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(a, idle))
        // Caps Lock types lower case keys as capitals too.
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_A, 'A', caps = true), idle))
    }

    @Test
    fun spaceEnterBackspaceAndEscapeOnlyActWhileComposing() {
        for ((code, expected) in listOf(
            KeyEvent.KEYCODE_SPACE to HardwareKeyAction.Space,
            KeyEvent.KEYCODE_ENTER to HardwareKeyAction.Enter,
            KeyEvent.KEYCODE_DEL to HardwareKeyAction.Backspace,
            KeyEvent.KEYCODE_ESCAPE to HardwareKeyAction.Cancel,
        )) {
            assertEquals(expected, HardwareKeyPolicy.decide(key(code), typing))
            assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(code), idle))
        }
    }

    @Test
    fun aHeldSpaceOrEnterDoesNotCommitRepeatedly() {
        assertEquals(HardwareKeyAction.Consume, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_SPACE, repeat = true), typing))
        assertEquals(HardwareKeyAction.Consume, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_ENTER, repeat = true), typing))
    }

    @Test
    fun digitsPickCandidatesOnlyWhileComposing() {
        assertEquals(HardwareKeyAction.SelectCandidate(0), HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_1, '1'), typing))
        assertEquals(HardwareKeyAction.SelectCandidate(4), HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_5, '5'), typing))
        assertEquals(HardwareKeyAction.Consume, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_9, '9'), typing))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_1, '1'), idle))
    }

    @Test
    fun apostropheSeparatesSyllablesOnlyWhileComposing() {
        assertEquals(HardwareKeyAction.Apostrophe, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_APOSTROPHE, '\''), typing))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_APOSTROPHE, '\''), idle))
    }

    @Test
    fun punctuationIsFullWidthAndCommitsTheFirstCandidateWhenComposing() {
        assertEquals(
            HardwareKeyAction.Punctuation("，", commitFirstCandidate = true),
            HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_COMMA, ','), typing),
        )
        assertEquals(
            HardwareKeyAction.Punctuation("。", commitFirstCandidate = false),
            HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_PERIOD, '.'), idle),
        )
        assertEquals(
            HardwareKeyAction.Punctuation("？", commitFirstCandidate = false),
            HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_SLASH, '?', shift = true), idle),
        )
    }

    @Test
    fun numbersKeepTheirAsciiPunctuation() {
        val afterDigit = idle.copy(charBeforeCursor = '3')
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_PERIOD, '.'), afterDigit))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_COMMA, ','), afterDigit))
        // A question mark after a digit is still a question mark.
        assertEquals(
            HardwareKeyAction.Punctuation("？", commitFirstCandidate = false),
            HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_SLASH, '?', shift = true), afterDigit),
        )
    }

    @Test
    fun anyOtherPrintableKeyClosesTheCompositionFirst() {
        assertEquals(HardwareKeyAction.FinishCompositionThenPassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_MINUS, '-'), typing))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_MINUS, '-'), idle))
        assertEquals(HardwareKeyAction.PassThrough, HardwareKeyPolicy.decide(key(KeyEvent.KEYCODE_DPAD_LEFT), typing))
    }
}
