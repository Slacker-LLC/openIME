package llc.slacker.openime

import llc.slacker.openime.core.ArithmeticExpressionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 算式出结果: the result of the expression being typed. */
class ArithmeticExpressionPolicyTest {

    @Test
    fun theFourOperatorsAndTheirPrecedence() {
        assertEquals("2", ArithmeticExpressionPolicy.evaluate("1+1"))
        assertEquals("-3", ArithmeticExpressionPolicy.evaluate("5-8"))
        assertEquals("36", ArithmeticExpressionPolicy.evaluate("12*3"))
        assertEquals("2.5", ArithmeticExpressionPolicy.evaluate("10/4"))
        assertEquals("7", ArithmeticExpressionPolicy.evaluate("1+2*3"))
        assertEquals("9", ArithmeticExpressionPolicy.evaluate("(1+2)*3"))
        assertEquals("-12", ArithmeticExpressionPolicy.evaluate("-(3*4)"))
        assertEquals("7", ArithmeticExpressionPolicy.evaluate("3.5+3.5"))
    }

    @Test
    fun fractionsKeepUsefulDigitsAndNothingMore() {
        assertEquals("0.333333", ArithmeticExpressionPolicy.evaluate("1/3"))
        assertEquals("0", ArithmeticExpressionPolicy.evaluate("0.1-0.1"))
        assertEquals("0.25", ArithmeticExpressionPolicy.evaluate("1/4"))
    }

    @Test
    fun theFullWidthKeysOfTheChineseLayoutWork() {
        assertEquals("3", ArithmeticExpressionPolicy.evaluate("１＋２"))
        assertEquals("8", ArithmeticExpressionPolicy.evaluate("２×４"))
        assertEquals("2", ArithmeticExpressionPolicy.evaluate("６÷３"))
        assertEquals("5", ArithmeticExpressionPolicy.evaluate("（２＋３）"))
    }

    @Test
    fun onlyAFinishedExpressionHasAResult() {
        // Still being typed.
        assertNull(ArithmeticExpressionPolicy.evaluate("1+"))
        assertNull(ArithmeticExpressionPolicy.evaluate("(1+2"))
        assertNull(ArithmeticExpressionPolicy.evaluate(""))
        // Nothing to calculate: a plain number, and half of a number.
        assertNull(ArithmeticExpressionPolicy.evaluate("12"))
        assertNull(ArithmeticExpressionPolicy.evaluate("."))
        assertNull(ArithmeticExpressionPolicy.evaluate("1.2.3"))
    }

    @Test
    fun anImpossibleSumHasNoResult() {
        assertNull(ArithmeticExpressionPolicy.evaluate("1/0"))
        assertNull(ArithmeticExpressionPolicy.evaluate("3/(2-2)"))
    }

    @Test
    fun theTrailingExpressionCarriesTheLengthItReplaces() {
        val result = ArithmeticExpressionPolicy.evaluateTrailing("价格1+1")
        assertEquals("2", result?.text)
        // 「1+1」 is three characters of the text before the cursor.
        assertEquals(3, result?.expressionLength)
    }

    @Test
    fun aBrokenTrailingRunHasNoResult() {
        // A space ends the expression: the result is offered while it is typed.
        assertNull(ArithmeticExpressionPolicy.evaluateTrailing("1+1 "))
        assertNull(ArithmeticExpressionPolicy.evaluateTrailing(""))
        assertNull(ArithmeticExpressionPolicy.evaluateTrailing("abc"))
        assertNull(ArithmeticExpressionPolicy.evaluateTrailing(null))
        // A Chinese digit is not an expression digit.
        assertNull(ArithmeticExpressionPolicy.evaluateTrailing("一加一"))
    }
}
