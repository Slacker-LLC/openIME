package llc.slacker.openime

import llc.slacker.openime.keyboard.AutoCapitalizationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首字母自动大写: where the English Shift key comes up by itself. */
class AutoCapitalizationPolicyTest {

    @Test
    fun anEmptyFieldOrAnUnreadableOne() {
        // Start of the field: the first letter is a capital.
        assertTrue(AutoCapitalizationPolicy.atSentenceStart(""))
        // The editor could not be read; the policy must leave Shift alone.
        assertFalse(AutoCapitalizationPolicy.atSentenceStart(null))
    }

    @Test
    fun aSentenceMarkEndsTheSentence() {
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hello."))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hi!"))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Why?"))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hi…"))
        // The spaces a keyboard inserts after the mark are not the mark.
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hello. "))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hi!  "))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("Hi.\t"))
    }

    @Test
    fun aFullWidthMarkCountsTooAndALineBreakIsItsOwnMark() {
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("你好。"))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("好！"))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("好？"))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("first line\n"))
        // The newline is the mark, so the space after it is skipped like any other.
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("first line\n "))
    }

    @Test
    fun insideTheSentenceShiftStaysDown() {
        assertFalse(AutoCapitalizationPolicy.atSentenceStart("Hello"))
        assertFalse(AutoCapitalizationPolicy.atSentenceStart("Hello wor"))
        // A comma joins clauses; it does not end a sentence.
        assertFalse(AutoCapitalizationPolicy.atSentenceStart("Hello, "))
        assertFalse(AutoCapitalizationPolicy.atSentenceStart("Hello;"))
    }

    @Test
    fun onlySpacesReadAsTheStartOfTheField() {
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("   "))
        assertTrue(AutoCapitalizationPolicy.atSentenceStart("\t"))
    }

    /** The window the caller reads must hold a mark plus the spaces after it. */
    @Test
    fun theLookbackHoldsAMarkWithItsTrailingSpaces() {
        assertTrue(AutoCapitalizationPolicy.LOOKBACK >= 4)
    }
}
