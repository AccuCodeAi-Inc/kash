package com.accucodeai.kash.tools.expr

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runExpr(vararg args: String): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = ExprCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class ExprCommandTest {
    @Test fun addExits0() =
        runTest {
            val (rc, out, _) = runExpr("1", "+", "2")
            assertEquals(0, rc)
            assertEquals("3\n", out)
        }

    @Test fun zeroExits1() =
        runTest {
            val (rc, out, _) = runExpr("0")
            assertEquals(1, rc)
            assertEquals("0\n", out)
        }

    @Test fun emptyStringOperandExits1() =
        runTest {
            val (rc, out, _) = runExpr("")
            assertEquals(1, rc)
            assertEquals("\n", out)
        }

    @Test fun oneExits0() =
        runTest {
            val (rc, out, _) = runExpr("1")
            assertEquals(0, rc)
            assertEquals("1\n", out)
        }

    @Test fun divByZeroExits2() =
        runTest {
            val (rc, _, err) = runExpr("1", "/", "0")
            assertEquals(2, rc)
            assertTrue(err.contains("expr:"))
            assertTrue(err.contains("division by zero"))
        }

    @Test fun noArgsExits2() =
        runTest {
            val (rc, _, err) = runExpr()
            assertEquals(2, rc)
            assertTrue(err.contains("missing operand"))
        }

    @Test fun helpExits0() =
        runTest {
            val (rc, out, _) = runExpr("--help")
            assertEquals(0, rc)
            assertTrue(out.contains("Usage:"))
        }

    @Test fun versionExits0() =
        runTest {
            val (rc, out, _) = runExpr("--version")
            assertEquals(0, rc)
            assertTrue(out.contains("expr"))
        }

    @Test fun comparisonResultExits0WhenTrue() =
        runTest {
            val (rc, out, _) = runExpr("1", "<", "2")
            assertEquals(0, rc)
            assertEquals("1\n", out)
        }

    @Test fun comparisonResultExits1WhenFalse() =
        runTest {
            val (rc, out, _) = runExpr("2", "<", "1")
            assertEquals(1, rc)
            assertEquals("0\n", out)
        }

    @Test fun lengthOutput() =
        runTest {
            val (rc, out, _) = runExpr("length", "hello")
            assertEquals(0, rc)
            assertEquals("5\n", out)
        }

    @Test fun substrOutput() =
        runTest {
            val (rc, out, _) = runExpr("substr", "hello", "2", "3")
            assertEquals(0, rc)
            assertEquals("ell\n", out)
        }

    @Test fun matchOutput() =
        runTest {
            val (rc, out, _) = runExpr("hello", ":", "h\\(.*\\)")
            assertEquals(0, rc)
            assertEquals("ello\n", out)
        }

    // ---- Recipe-style: feature interactions a shell script would hit ----

    @Test fun recipe_incrementVarStyle() =
        runTest {
            // The classic `i=$(expr $i + 1)` increment pattern.
            val (rc, out, _) = runExpr("5", "+", "1")
            assertEquals(0, rc)
            assertEquals("6\n", out)
        }

    @Test fun recipe_extractCaptureGroup() =
        runTest {
            // Extract version from "v1.2.3".
            val (rc, out, _) = runExpr("v1.2.3", ":", "v\\(.*\\)")
            assertEquals(0, rc)
            assertEquals("1.2.3\n", out)
        }

    @Test fun recipe_stringLengthCheck() =
        runTest {
            // `if [ $(expr length "$s") -gt 5 ]`-style: length is just an int.
            val (rc, out, _) = runExpr("length", "abcdef")
            assertEquals(0, rc)
            assertEquals("6\n", out)
        }

    @Test fun recipe_defaultValueWithOr() =
        runTest {
            // `expr "$x" \| default` returns x if non-empty, else "default".
            val (rc, out, _) = runExpr("", "|", "default")
            assertEquals(0, rc)
            assertEquals("default\n", out)
        }

    @Test fun recipe_rangeCheck() =
        runTest {
            // `expr $n \>= 0 \& $n \< 10` — range membership.
            val (rc, out, _) = runExpr("5", ">=", "0", "&", "5", "<", "10")
            assertEquals(0, rc)
            assertEquals("1\n", out)
        }

    @Test fun recipe_substringAfterSeparator() =
        runTest {
            // Get filename extension: substr after first '.'.
            val pos = ExprParser(listOf("index", "file.txt", ".")).parse().asString().toInt()
            val (rc, out, _) = runExpr("substr", "file.txt", (pos + 1).toString(), "100")
            assertEquals(0, rc)
            assertEquals("txt\n", out)
        }

    @Test fun recipe_arithmeticInParens() =
        runTest {
            val (rc, out, _) = runExpr("(", "10", "+", "5", ")", "*", "2")
            assertEquals(0, rc)
            assertEquals("30\n", out)
        }

    @Test fun recipe_matchReturnsCountForCount() =
        runTest {
            // Count leading digits.
            val (rc, out, _) = runExpr("match", "12345abc", "[0-9]*")
            assertEquals(0, rc)
            assertEquals("5\n", out)
        }
}
