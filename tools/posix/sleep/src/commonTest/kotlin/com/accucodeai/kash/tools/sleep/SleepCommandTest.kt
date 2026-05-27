// File-level OptIn below silences ExperimentalCoroutinesApi warnings for
// `TestScope.currentTime` — TestScope's virtual clock is what makes these
// duration assertions cheap and deterministic.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.sleep

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.NullFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun newCtx(): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = NullFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    return Triple(ctx, out, err)
}

class SleepCommandTest {
    // --- Duration semantics (verified via runTest virtual clock) ---

    @Test fun integerSeconds() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            val res = SleepCommand().run(listOf("3"), ctx)
            val elapsed = currentTime - before
            assertEquals(0, res.exitCode)
            assertEquals(3_000L, elapsed)
        }

    @Test fun fractionalSeconds() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            val res = SleepCommand().run(listOf("0.5"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals(500L, currentTime - before)
        }

    @Test fun fractionalSecondsTruncatesToMillis() =
        runTest {
            // 0.0005 s == 0.5 ms → truncates to 0 ms → no delay issued, success.
            val (ctx, _, _) = newCtx()
            val before = currentTime
            val res = SleepCommand().run(listOf("0.0005"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals(0L, currentTime - before)
        }

    @Test fun zeroSecondsIsNoop() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            val res = SleepCommand().run(listOf("0"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals(0L, currentTime - before)
        }

    // --- Suffixes ---

    @Test fun suffixSeconds() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("2s"), ctx)
            assertEquals(2_000L, currentTime - before)
        }

    @Test fun suffixMinutes() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("1m"), ctx)
            assertEquals(60_000L, currentTime - before)
        }

    @Test fun suffixHours() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("1h"), ctx)
            assertEquals(3_600_000L, currentTime - before)
        }

    @Test fun suffixDays() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("1d"), ctx)
            assertEquals(86_400_000L, currentTime - before)
        }

    @Test fun fractionalWithSuffix() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("0.5m"), ctx)
            assertEquals(30_000L, currentTime - before)
        }

    // --- Multi-operand summation (POSIX) ---

    @Test fun multipleOperandsSum() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            val res = SleepCommand().run(listOf("1", "2", "0.5"), ctx)
            assertEquals(0, res.exitCode)
            assertEquals(3_500L, currentTime - before)
        }

    @Test fun multipleOperandsMixedSuffixes() =
        runTest {
            val (ctx, _, _) = newCtx()
            val before = currentTime
            SleepCommand().run(listOf("1s", "1m"), ctx)
            assertEquals(61_000L, currentTime - before)
        }

    // --- Error paths ---

    @Test fun missingOperandIsError() =
        runTest {
            val (ctx, _, err) = newCtx()
            val res = SleepCommand().run(emptyList(), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("missing operand"))
        }

    @Test fun nonNumericIsError() =
        runTest {
            val (ctx, _, err) = newCtx()
            val res = SleepCommand().run(listOf("abc"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("invalid time interval"))
        }

    @Test fun unknownSuffixIsError() =
        runTest {
            val (ctx, _, _) = newCtx()
            // 'x' is not a recognized suffix and not a digit → rejected.
            val res = SleepCommand().run(listOf("5x"), ctx)
            assertEquals(2, res.exitCode)
        }

    @Test fun negativeIsError() =
        runTest {
            val (ctx, _, err) = newCtx()
            val res = SleepCommand().run(listOf("-1"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("invalid time interval"))
        }

    @Test fun emptyStringIsError() =
        runTest {
            val (ctx, _, _) = newCtx()
            val res = SleepCommand().run(listOf(""), ctx)
            assertEquals(2, res.exitCode)
        }

    @Test fun suffixOnlyIsError() =
        runTest {
            val (ctx, _, _) = newCtx()
            val res = SleepCommand().run(listOf("s"), ctx)
            assertEquals(2, res.exitCode)
        }

    @Test fun scientificNotationRejected() =
        runTest {
            // POSIX `sleep` does not accept scientific notation.
            val (ctx, _, _) = newCtx()
            val res = SleepCommand().run(listOf("1e2"), ctx)
            assertEquals(2, res.exitCode)
        }

    @Test fun multipleDotsRejected() =
        runTest {
            val (ctx, _, _) = newCtx()
            val res = SleepCommand().run(listOf("1.2.3"), ctx)
            assertEquals(2, res.exitCode)
        }

    @Test fun ifOneOperandInvalidWholeCallFails() =
        runTest {
            val (ctx, _, err) = newCtx()
            val res = SleepCommand().run(listOf("1", "bogus", "1"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("bogus"))
        }

    // --- Metadata ---

    @Test fun specMetadata() {
        val cmd = SleepCommand()
        assertEquals("sleep", cmd.name)
        assertTrue(cmd.tags.any { it.name == "POSIX" })
    }
}
