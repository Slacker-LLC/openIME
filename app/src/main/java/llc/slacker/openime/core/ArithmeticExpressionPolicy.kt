package llc.slacker.openime.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/**
 * 算式出结果: the result of the arithmetic expression that ends at the cursor.
 *
 * The reference computes this natively — `keyboard::InputModel::Impl::
 * AssociateCalculator` (libkeyboard.so 0x26523c), `GetCalculatorCandidate`
 * (0x255ca4) and `CommitCalculatorCand` (0x264594), whose `cand_length`
 * argument replaces the expression with the result — and its 智能联想 switch
 * names the result among what that switch offers (`string/associate_close_content`
 * 「关闭联想后，将不会推荐诗词、计算结果、emoji表情。」). Neither the
 * reference's resources nor its native library holds a 「=」 display string, so
 * the candidate text is the result alone.
 */
internal object ArithmeticExpressionPolicy {

    /** How far back the trailing expression is read; longer than any real sum. */
    const val MAX_LOOKBACK = 48

    /** A result and the length of the expression it replaces, in UTF-16 units. */
    data class Result(val text: String, val expressionLength: Int)

    /** The characters an expression is made of, half- and full-width. */
    private const val EXPRESSION_CHARS = "0123456789.+-*/()×÷"

    private const val OPERATORS = "+-*/"

    /** The result for the expression ending at the end of [textBefore], or null. */
    fun evaluateTrailing(textBefore: CharSequence?): Result? {
        if (textBefore == null) return null
        var start = textBefore.length
        while (start > 0 && textBefore[start - 1] in EXPRESSION_CHARS) start--
        if (start == textBefore.length) return null
        val trailing = textBefore.subSequence(start, textBefore.length).toString()
        val value = evaluate(trailing) ?: return null
        return Result(value, trailing.length)
    }

    /**
     * The value of [expression] as the candidate shows it, or null when it is
     * not a complete expression (no operator, an unfinished tail, unbalanced
     * brackets, a division by zero) or its value has no useful text.
     */
    fun evaluate(expression: String): String? {
        val normalized = normalize(expression)
        if (normalized.isEmpty() || normalized.none { it in OPERATORS }) return null
        // Only a finished expression has a result to offer: the reference reads
        // the text before the cursor, so a trailing operator is still being typed.
        if (normalized.last() !in '0'..'9' && normalized.last() != ')') return null
        val parser = Parser(normalized)
        val value = parser.parseWhole() ?: return null
        if (!value.isFinite()) return null
        return format(value)
    }

    /** Full-width digits and operators read as their ASCII forms. */
    private fun normalize(expression: String): String = buildString(expression.length) {
        for (char in expression) {
            append(
                when (char) {
                    in '０'..'９' -> '0' + (char - '０')
                    '＋' -> '+'
                    '－' -> '-'
                    '＊' -> '*'
                    '／' -> '/'
                    // The reference's own keys print these two forms.
                    '×' -> '*'
                    '÷' -> '/'
                    '（' -> '('
                    '）' -> ')'
                    '．' -> '.'
                    else -> char
                },
            )
        }
    }

    private fun format(value: Double): String {
        if (value == 0.0) return "0"
        if (abs(value) < 1e15 && value == floor(value)) return value.toLong().toString()
        val rounded = BigDecimal(value).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros()
        val text = rounded.toPlainString()
        if (text.length <= 18) return text
        return BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }

    /**
     * A recursive-descent reader: expression = term (+|- term)*,
     * term = factor (*|/ factor)*, factor = -factor | +factor | number |
     * ( expression ). Null means the text is not a whole expression.
     */
    private class Parser(private val text: String) {
        private var index = 0

        fun parseWhole(): Double? {
            val value = parseExpression() ?: return null
            return if (index == text.length) value else null
        }

        private fun parseExpression(): Double? {
            var value = parseTerm() ?: return null
            while (true) {
                val operator = peek() ?: break
                if (operator != '+' && operator != '-') break
                index++
                val right = parseTerm() ?: return null
                value = if (operator == '+') value + right else value - right
            }
            return value
        }

        private fun parseTerm(): Double? {
            var value = parseFactor() ?: return null
            while (true) {
                val operator = peek() ?: break
                if (operator != '*' && operator != '/') break
                index++
                val right = parseFactor() ?: return null
                if (operator == '/') {
                    if (right == 0.0) return null
                    value /= right
                } else {
                    value *= right
                }
            }
            return value
        }

        private fun parseFactor(): Double? {
            when (peek()) {
                '-' -> {
                    index++
                    return parseFactor()?.let { -it }
                }
                '+' -> {
                    index++
                    return parseFactor()
                }
                '(' -> {
                    index++
                    val inner = parseExpression() ?: return null
                    if (peek() != ')') return null
                    index++
                    return inner
                }
                else -> return parseNumber()
            }
        }

        private fun parseNumber(): Double? {
            val start = index
            while (peek()?.let { it in '0'..'9' || it == '.' } == true) index++
            if (index == start) return null
            val number = text.substring(start, index)
            if (number.count { it == '.' } > 1 || number == ".") return null
            return number.toDoubleOrNull()
        }

        private fun peek(): Char? = text.getOrNull(index)
    }
}
