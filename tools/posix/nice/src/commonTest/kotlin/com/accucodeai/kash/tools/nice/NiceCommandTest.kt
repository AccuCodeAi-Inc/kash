@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.nice

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.UtilityRunner
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Recorder(
    private val rc: Int = 0,
) : UtilityRunner {
    var lastName: String? = null
    var lastArgs: List<String> = emptyList()
    var calls: Int = 0

    override suspend fun run(
        name: String,
        args: List<String>,
        stdin: SuspendSource,
        stdout: SuspendSink,
        stderr: SuspendSink,
    ): Int {
        lastName = name
        lastArgs = args
        calls++
        stdout.writeUtf8("ran\n")
        return rc
    }
}

private fun newCtx(runner: UtilityRunner? = null): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            utilityRunner = runner,
        )
    return Triple(ctx, out, err)
}

class NiceCommandTest {
    @Test fun noArgs_printsZero() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = NiceCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("0\n", out.readString())
        }

    @Test fun dashN_thenUtility() =
        runTest {
            val r = Recorder()
            val (ctx, out, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("-n", "10", "echo", "hi"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
            assertEquals(listOf("hi"), r.lastArgs)
            assertEquals("ran\n", out.readString())
        }

    @Test fun legacyDashN_format() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("-10", "echo", "hi"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
            assertEquals(listOf("hi"), r.lastArgs)
        }

    @Test fun legacyNegative_format() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("--", "-5", "x"), ctx) // -- forces no opt parse
            // After `--`, "-5" is the utility name.
            assertEquals(0, rc.exitCode)
            assertEquals("-5", r.lastName)
            assertEquals(listOf("x"), r.lastArgs)
        }

    @Test fun missingValue_afterDashN_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = NiceCommand().run(listOf("-n"), ctx)
            assertEquals(125, rc.exitCode)
            assertTrue(err.readString().contains("requires an argument"))
        }

    @Test fun badAdjustment_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = NiceCommand().run(listOf("-n", "abc", "echo"), ctx)
            assertEquals(125, rc.exitCode)
            assertTrue(err.readString().contains("invalid adjustment"))
        }

    @Test fun adjustmentLong_form() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("--adjustment=5", "echo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
        }

    @Test fun adjustmentLong_separate() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("--adjustment", "5", "echo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
        }

    @Test fun commandPassesThroughExitCode() =
        runTest {
            val r = Recorder(rc = 42)
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("-n", "1", "foo"), ctx)
            assertEquals(42, rc.exitCode)
        }

    @Test fun noUtilityRunner_returns_127() =
        runTest {
            val (ctx, _, err) = newCtx(null)
            val rc = NiceCommand().run(listOf("foo"), ctx)
            assertEquals(127, rc.exitCode)
            assertTrue(err.readString().contains("nice"))
        }

    @Test fun help_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = NiceCommand().run(listOf("--help"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("Usage"))
        }

    @Test fun version_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = NiceCommand().run(listOf("-V"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("nice"))
        }

    @Test fun doubleDash_endsOptions() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("--", "echo", "-n"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
            assertEquals(listOf("-n"), r.lastArgs)
        }

    @Test fun bareUtility_runs() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("echo", "hi"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("echo", r.lastName)
            assertEquals(listOf("hi"), r.lastArgs)
        }

    @Test fun positiveAdjustmentWithPlus() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("-+5", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo", r.lastName)
        }

    @Test fun positiveAdjustment_dashN() =
        runTest {
            val r = Recorder()
            val (ctx, _, _) = newCtx(r)
            val rc = NiceCommand().run(listOf("-n", "+5", "foo"), ctx)
            assertEquals(0, rc.exitCode)
            assertEquals("foo", r.lastName)
        }
}
