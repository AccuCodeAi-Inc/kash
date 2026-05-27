package com.accucodeai.kash.tools.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun eval(vararg toks: String): String = ExprParser(toks.toList()).parse().asString()

private fun evalTruthy(vararg toks: String): Boolean = ExprParser(toks.toList()).parse().isTruthy()

class ExprParserTest {
    // ---- arithmetic ----
    @Test fun simpleAdd() = assertEquals("3", eval("1", "+", "2"))

    @Test fun simpleSub() = assertEquals("5", eval("10", "-", "5"))

    @Test fun mulPrecedence() = assertEquals("-1", eval("5", "-", "2", "*", "3"))

    @Test fun addAndMul() = assertEquals("7", eval("1", "+", "2", "*", "3"))

    @Test fun parens() = assertEquals("9", eval("(", "1", "+", "2", ")", "*", "3"))

    @Test fun nestedParens() = assertEquals("21", eval("(", "1", "+", "(", "2", "*", "3", ")", ")", "*", "3"))

    @Test fun integerDivision() = assertEquals("3", eval("7", "/", "2"))

    @Test fun modulo() = assertEquals("1", eval("7", "%", "2"))

    @Test fun negativeOperand() = assertEquals("-5", eval("-10", "+", "5"))

    @Test fun zeroResult() = assertEquals("0", eval("5", "-", "5"))

    @Test fun chainedAdd() = assertEquals("10", eval("1", "+", "2", "+", "3", "+", "4"))

    @Test fun subIsLeftAssoc() = assertEquals("0", eval("10", "-", "5", "-", "5"))

    @Test fun divIsLeftAssoc() = assertEquals("2", eval("20", "/", "5", "/", "2"))

    @Test fun divByZero() {
        val e = assertFailsWith<ExprError> { eval("5", "/", "0") }
        assertEquals(2, e.exit)
    }

    @Test fun modByZero() {
        assertFailsWith<ExprError> { eval("5", "%", "0") }
    }

    @Test fun nonIntArith() {
        val e = assertFailsWith<ExprError> { eval("1", "+", "abc") }
        assertTrue(e.message!!.contains("non-integer"))
    }

    @Test fun bigNumbers() = assertEquals("1000000000000", eval("1000000", "*", "1000000"))

    @Test fun overflowDetected() {
        assertFailsWith<ExprError> { eval("9223372036854775807", "+", "1") }
    }

    // ---- comparison ----
    @Test fun eqTrue() = assertEquals("1", eval("1", "=", "1"))

    @Test fun eqFalse() = assertEquals("0", eval("1", "=", "2"))

    @Test fun ltTrue() = assertEquals("1", eval("1", "<", "2"))

    @Test fun ltFalse() = assertEquals("0", eval("2", "<", "1"))

    @Test fun leqBoundary() = assertEquals("1", eval("2", "<=", "2"))

    @Test fun neq() = assertEquals("1", eval("1", "!=", "2"))

    @Test fun gtNumeric() = assertEquals("1", eval("10", ">", "2"))

    @Test fun gtStringWouldDiffer() {
        // If "10" and "2" were compared as strings, "10" < "2". As numbers, 10 > 2. POSIX
        // says numeric if both look like numbers — so 10 > 2 → 1.
        assertEquals("1", eval("10", ">", "2"))
    }

    @Test fun stringCompareWhenNonNumeric() = assertEquals("1", eval("abc", "<", "abd"))

    @Test fun stringEq() = assertEquals("1", eval("abc", "=", "abc"))

    @Test fun stringEqDiff() = assertEquals("0", eval("abc", "=", "abd"))

    @Test fun mixedNumericStringCompare() {
        // "abc" is not numeric, so string compare.
        assertEquals("0", eval("abc", "=", "1"))
    }

    // ---- boolean | & ----
    @Test fun orFirstTrue() = assertEquals("1", eval("1", "|", "2"))

    @Test fun orFirstFalseZero() = assertEquals("2", eval("0", "|", "2"))

    @Test fun orFirstFalseEmpty() = assertEquals("hello", eval("", "|", "hello"))

    @Test fun orBothFalse() = assertEquals("0", eval("0", "|", "0"))

    @Test fun andBothTrue() = assertEquals("1", eval("1", "&", "2"))

    @Test fun andFirstFalse() = assertEquals("0", eval("0", "&", "2"))

    @Test fun andSecondFalse() = assertEquals("0", eval("1", "&", "0"))

    @Test fun andPrecedenceOverOr() {
        // 0 | 1 & 0  ==  0 | (1 & 0) == 0 | 0 == 0
        assertEquals("0", eval("0", "|", "1", "&", "0"))
    }

    @Test fun cmpPrecedenceOverAnd() {
        // 1 < 2 & 3 < 4 → (1<2) & (3<4) → 1
        assertEquals("1", eval("1", "<", "2", "&", "3", "<", "4"))
    }

    // ---- length / substr / index / match keywords ----
    @Test fun lengthAscii() = assertEquals("3", eval("length", "abc"))

    @Test fun lengthEmpty() = assertEquals("0", eval("length", ""))

    @Test fun lengthCodepoint() = assertEquals("3", eval("length", "αβγ"))

    @Test fun substrMid() = assertEquals("bcd", eval("substr", "abcde", "2", "3"))

    @Test fun substrPosZero() = assertEquals("", eval("substr", "abcde", "0", "2"))

    @Test fun substrPosNeg() = assertEquals("", eval("substr", "abcde", "-1", "2"))

    @Test fun substrPastEnd() = assertEquals("", eval("substr", "abcde", "10", "2"))

    @Test fun substrLenOverflow() = assertEquals("de", eval("substr", "abcde", "4", "100"))

    @Test fun substrLenZero() = assertEquals("", eval("substr", "abcde", "2", "0"))

    @Test fun substrCodepoints() = assertEquals("β", eval("substr", "αβγ", "2", "1"))

    @Test fun indexFound() = assertEquals("2", eval("index", "abc", "b"))

    @Test fun indexNotFound() = assertEquals("0", eval("index", "abc", "xyz"))

    @Test fun indexFirstMatchingChar() = assertEquals("2", eval("index", "abcdef", "bd"))

    @Test fun indexFirstChar() = assertEquals("1", eval("index", "abc", "a"))

    @Test fun matchKeyword() = assertEquals("3", eval("match", "aaabbb", "a*"))

    @Test fun matchNoMatch() = assertEquals("0", eval("match", "abc", "z*x"))

    @Test fun colonMatchLength() = assertEquals("3", eval("aaabbb", ":", "a*"))

    @Test fun colonMatchCapture() = assertEquals("bbb", eval("aaabbb", ":", "a*\\(b*\\)"))

    @Test fun colonMatchCaptureNoMatch() = assertEquals("", eval("xyz", ":", "a\\(b*\\)c"))

    @Test fun colonAnyChar() = assertEquals("3", eval("abc", ":", "..."))

    @Test fun colonBracketClass() = assertEquals("3", eval("123", ":", "[0-9][0-9][0-9]"))

    // ---- + escape hatch ----
    @Test fun plusForcesString() = assertEquals("length", eval("+", "length"))

    @Test fun plusForcesStringComparable() = assertEquals("1", eval("+", "length", "=", "+", "length"))

    // ---- truthiness ----
    @Test fun truthyNonzero() = assertTrue(evalTruthy("1"))

    @Test fun truthyZero() = assertEquals(false, evalTruthy("0"))

    @Test fun truthyEmpty() = assertEquals(false, evalTruthy(""))

    @Test fun truthyString() = assertTrue(evalTruthy("hello"))

    // ---- errors ----
    @Test fun emptyExpressionErrors() {
        val e = assertFailsWith<ExprError> { ExprParser(emptyList()).parse() }
        assertEquals(2, e.exit)
    }

    @Test fun unmatchedParenErrors() {
        assertFailsWith<ExprError> { eval("(", "1", "+", "2") }
    }

    @Test fun trailingTokenErrors() {
        assertFailsWith<ExprError> { eval("1", "+", "2", "3") }
    }
}
