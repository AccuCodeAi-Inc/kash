package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ArithmeticTest {
    private suspend fun out(script: String) = Kash().exec(script).stdout

    // -------- basics --------

    @Test fun addition() = runTest { assertEquals("5\n", out("echo \$((2 + 3))")) }

    @Test fun subtraction() = runTest { assertEquals("4\n", out("echo \$((7 - 3))")) }

    @Test fun multiplication() = runTest { assertEquals("12\n", out("echo \$((3 * 4))")) }

    @Test fun divisionTruncates() = runTest { assertEquals("3\n", out("echo \$((10 / 3))")) }

    @Test fun modulo() = runTest { assertEquals("1\n", out("echo \$((10 % 3))")) }

    @Test fun negation() = runTest { assertEquals("-5\n", out("echo \$((-5))")) }

    @Test fun parens() = runTest { assertEquals("14\n", out("echo \$((2 * (3 + 4)))")) }

    @Test fun exponent() = runTest { assertEquals("256\n", out("echo \$((2 ** 8))")) }

    // -------- bitwise --------

    @Test fun bitAnd() = runTest { assertEquals("8\n", out("echo \$((12 & 10))")) }

    @Test fun bitOr() = runTest { assertEquals("14\n", out("echo \$((12 | 10))")) }

    @Test fun bitXor() = runTest { assertEquals("6\n", out("echo \$((12 ^ 10))")) }

    @Test fun shiftLeft() = runTest { assertEquals("8\n", out("echo \$((1 << 3))")) }

    @Test fun shiftRight() = runTest { assertEquals("4\n", out("echo \$((32 >> 3))")) }

    // -------- comparisons / logical --------

    @Test fun lessThan() = runTest { assertEquals("1\n", out("echo \$((2 < 3))")) }

    @Test fun equality() = runTest { assertEquals("1\n", out("echo \$((5 == 5))")) }

    @Test fun logicalAnd() = runTest { assertEquals("1\n", out("echo \$((1 && 2))")) }

    @Test fun logicalOr() = runTest { assertEquals("1\n", out("echo \$((0 || 5))")) }

    @Test fun ternary() =
        runTest {
            assertEquals("1\n", out("echo \$((1 ? 1 : 2))"))
            assertEquals("2\n", out("echo \$((0 ? 1 : 2))"))
        }

    // -------- variables --------

    @Test fun bareNameAutoResolves() =
        runTest {
            assertEquals("11\n", out("x=10\necho \$((x + 1))"))
        }

    @Test fun dollarNameAlsoWorks() =
        runTest {
            assertEquals("11\n", out("x=10\necho \$((\$x + 1))"))
        }

    @Test fun assignmentWritesBack() =
        runTest {
            assertEquals("5\n5\n", out("echo \$((x = 5))\necho \$x"))
        }

    @Test fun compoundAssign() =
        runTest {
            assertEquals("7\n7\n", out("x=5\necho \$((x += 2))\necho \$x"))
        }

    @Test fun preIncrement() =
        runTest {
            assertEquals("6\n6\n", out("x=5\necho \$((++x))\necho \$x"))
        }

    @Test fun postIncrement() =
        runTest {
            assertEquals("5\n6\n", out("x=5\necho \$((x++))\necho \$x"))
        }

    // -------- number bases --------

    @Test fun hexLiteral() = runTest { assertEquals("255\n", out("echo \$((0xff))")) }

    @Test fun octalLiteral() = runTest { assertEquals("8\n", out("echo \$((010))")) }

    @Test fun baseHash() = runTest { assertEquals("10\n", out("echo \$((2#1010))")) }

    @Test fun baseHash64() = runTest { assertEquals("62\n", out($$"echo $((64#@))")) }

    @Test fun baseHashUnderscore() = runTest { assertEquals("63\n", out($$"echo $((64#_))")) }

    @Test fun baseHash56UpperA() = runTest { assertEquals("36\n", out($$"echo $((56#A))")) }

    @Test fun baseHash16UpperLower() = runTest { assertEquals("255\n255\n", out($$"echo $((16#ff))\necho $((16#FF))")) }

    // -------- += operator --------

    @Test fun appendAssignment() =
        runTest {
            assertEquals("hello world\n", out("x=hello\nx+=' world'\necho \"\$x\""))
        }

    @Test fun appendInInline() =
        runTest {
            // a+=Y bare assignment also appends
            assertEquals("abc\n", out("a=ab\na+=c\necho \$a"))
        }
}
