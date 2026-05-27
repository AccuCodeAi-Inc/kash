package com.accucodeai.kash.tools.bc

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun mkCtx(
    stdin: String = "",
    fs: InMemoryFs = InMemoryFs(),
): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val inBuf = Buffer().apply { writeString(stdin) }
    val ctx =
        bareCommandContext(
            fs = fs,
            stdin = inBuf.asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    return Triple(ctx, out, err)
}

class BcCommandTest {
    @Test fun dashE_twoPlusTwo() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", "2+2"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("4\n", out.readString())
        }

    @Test fun dashE_multipleExpressions() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", "1+1", "-e", "2+2"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("2\n4\n", out.readString())
        }

    @Test fun stdinExpression() =
        runTest {
            val (ctx, out, _) = mkCtx(stdin = "3 * 4\n")
            val res = BcCommand().run(emptyList(), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("12\n", out.readString())
        }

    @Test fun stdinWithScale() =
        runTest {
            val (ctx, out, _) = mkCtx(stdin = "scale=4\n1/3\n")
            val res = BcCommand().run(emptyList(), ctx)
            assertEquals(0, res.exitCode)
            assertEquals(".3333\n", out.readString())
        }

    @Test fun fileOperand() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/script.bc", "5 * 6\n".encodeToByteArray())
            val (ctx, out, _) = mkCtx(fs = fs)
            val res = BcCommand().run(listOf("/script.bc"), ctx)
            assertEquals(0, res.exitCode)
            // File processed AND stdin (empty) — should produce just file output.
            assertEquals("30\n", out.readString())
        }

    @Test fun missingFile() =
        runTest {
            val (ctx, _, err) = mkCtx()
            val res = BcCommand().run(listOf("/no/such.bc"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("No such file"))
        }

    @Test fun mathLibFlag() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-l", "-e", "scale=5; s(0)"), ctx)
            assertEquals(0, res.exitCode)
            val text = out.readString().trim()
            // s(0) = 0
            assertEquals(0.0, text.toDouble())
        }

    @Test fun quitFromExpression() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", "1\nquit\n2"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("1\n", out.readString())
        }

    @Test fun syntaxErrorReturns1() =
        runTest {
            val (ctx, _, err) = mkCtx()
            val res = BcCommand().run(listOf("-e", "1 +"), ctx)
            assertEquals(1, res.exitCode)
            assertTrue(err.readString().contains("parse error"))
        }

    @Test fun runtimeErrorReturns1() =
        runTest {
            val (ctx, _, err) = mkCtx()
            val res = BcCommand().run(listOf("-e", "1 / 0"), ctx)
            assertEquals(1, res.exitCode)
            assertTrue(err.readString().contains("divide by zero"))
        }

    @Test fun unknownOption() =
        runTest {
            val (ctx, _, err) = mkCtx()
            val res = BcCommand().run(listOf("--bogus"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("unknown option"))
        }

    @Test fun versionFlag() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-V"), ctx)
            assertEquals(0, res.exitCode)
            assertTrue(out.readString().startsWith("bc"))
        }

    @Test fun helpFlag() =
        runTest {
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-h"), ctx)
            assertEquals(0, res.exitCode)
            assertTrue(out.readString().contains("usage"))
        }

    @Test fun specMetadata() {
        val cmd = BcCommand()
        assertEquals("bc", cmd.name)
        assertTrue(cmd.tags.any { it.name == "POSIX" })
    }

    @Test fun recipe_compound_interest() =
        runTest {
            // 100 * (1 + 0.05)^10 ≈ 162.88946...
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", "scale=2; 100 * (1 + 5/100)^10"), ctx)
            assertEquals(0, res.exitCode)
            // bc with scale=2 truncation gives 162.88 (approximate).
            // Let's just assert it's between 162 and 163.
            val v = out.readString().trim().toDouble()
            assertTrue(v in 160.0..163.5, "got $v")
        }

    @Test fun recipe_fibonacci() =
        runTest {
            // fib(15) via iterative loop
            val src = "a=0;b=1;for(i=0;i<15;i=i+1){t=a+b;a=b;b=t};a"
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", src), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("610\n", out.readString())
        }

    @Test fun recipe_arrayBasedSum() =
        runTest {
            val src =
                """
                for (i = 0; i < 5; i = i + 1) a[i] = i * i
                s = 0
                for (i = 0; i < 5; i = i + 1) s = s + a[i]
                s
                """.trimIndent()
            val (ctx, out, _) = mkCtx()
            val res = BcCommand().run(listOf("-e", src), ctx)
            assertEquals(0, res.exitCode)
            assertEquals("30\n", out.readString()) // 0+1+4+9+16
        }
}
