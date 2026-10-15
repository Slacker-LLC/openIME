package llc.slacker.openime

import llc.slacker.openime.core.LetterDigitSpacePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 数字、英文前后加空格: where a space goes between Chinese and digits/English. */
class LetterDigitSpacePolicyTest {

    @Test
    fun chineseMeetsLettersOrDigits() {
        assertTrue(LetterDigitSpacePolicy.boundaryNeedsSpace('好', 'a'))
        assertTrue(LetterDigitSpacePolicy.boundaryNeedsSpace('好', 'Z'))
        assertTrue(LetterDigitSpacePolicy.boundaryNeedsSpace('好', '7'))
        assertTrue(LetterDigitSpacePolicy.boundaryNeedsSpace('a', '好'))
        assertTrue(LetterDigitSpacePolicy.boundaryNeedsSpace('7', '好'))
    }

    @Test
    fun theTwoSidesOfTheSameScriptGetNothing() {
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('好', '好'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('a', 'b'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('7', '9'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('a', '7'))
    }

    @Test
    fun aSpaceOrAnEdgeGetsNothing() {
        // The space a previous commit inserted is not a boundary to fill again.
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace(' ', 'a'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('好', ' '))
        // Chinese punctuation is not 中文 for this rule.
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('。', 'a'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('，', '7'))
        // An empty editor side, or one the editor could not answer.
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace(null, 'a'))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace('好', null))
        assertFalse(LetterDigitSpacePolicy.boundaryNeedsSpace(null, null))
    }

    @Test
    fun theScriptTestsTheReferenceNames() {
        assertTrue(LetterDigitSpacePolicy.isChinese('好'))
        assertTrue(LetterDigitSpacePolicy.isChinese('龙'))
        assertFalse(LetterDigitSpacePolicy.isChinese('a'))
        assertFalse(LetterDigitSpacePolicy.isChinese('２'))
        assertTrue(LetterDigitSpacePolicy.isDigitOrLatin('5'))
        assertTrue(LetterDigitSpacePolicy.isDigitOrLatin('q'))
        assertFalse(LetterDigitSpacePolicy.isDigitOrLatin('好'))
        assertFalse(LetterDigitSpacePolicy.isDigitOrLatin('。'))
    }
}
