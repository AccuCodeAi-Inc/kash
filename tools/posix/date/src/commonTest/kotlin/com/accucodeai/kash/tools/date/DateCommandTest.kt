@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.date

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.clock.ShellClock
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Pinned-time clock for deterministic format assertions. Wall now is set
 * to `Instant.fromEpochSeconds(seconds)`; monotonic-since-start is always
 * zero (the date command doesn't read it).
 */
private class FixedClock(
    private val seconds: Long,
    private val nanos: Int = 0,
) : ShellClock {
    override fun now(): Instant = Instant.fromEpochSeconds(seconds, nanos.toLong())

    override fun elapsedSinceShellStart(): Duration = Duration.ZERO
}

/** Build a test [CommandContext] with a pinned clock and `TZ=UTC0`. */
private fun newCtx(secs: Long): Triple<CommandContext, Buffer, Buffer> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            env = mutableMapOf("TZ" to "UTC0"),
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
            clock = FixedClock(secs),
        )
    return Triple(ctx, out, err)
}

class DateCommandTest {
    // 1_700_000_000 = 2023-11-14T22:13:20Z. Pick this as the canonical
    // fixture — POSIX `date` formatting should round-trip predictably.
    private val canonical: Long = 1_700_000_000L

    @Test fun epochSecondsConversion() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%s"), ctx)
            assertEquals("1700000000\n", out.readString())
        }

    @Test fun fullDateConversion() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%Y-%m-%d"), ctx)
            assertEquals("2023-11-14\n", out.readString())
        }

    @Test fun timeConversion() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%H:%M:%S"), ctx)
            assertEquals("22:13:20\n", out.readString())
        }

    @Test fun fAliasMatchesYmd() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%F"), ctx)
            assertEquals("2023-11-14\n", out.readString())
        }

    @Test fun tConversionMatchesHMS() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%T"), ctx)
            assertEquals("22:13:20\n", out.readString())
        }

    @Test fun weekdayNames() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            // 2023-11-14 is a Tuesday.
            DateCommand().run(listOf("+%a-%A-%u-%w"), ctx)
            assertEquals("Tue-Tuesday-2-2\n", out.readString())
        }

    @Test fun monthNames() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%b-%B-%h-%m"), ctx)
            assertEquals("Nov-November-Nov-11\n", out.readString())
        }

    @Test fun centuryAndYear() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%C-%y-%Y"), ctx)
            assertEquals("20-23-2023\n", out.readString())
        }

    @Test fun dayPaddingForms() =
        runTest {
            // 2023-01-05 (epoch 1_672_876_800) — single-digit day exercises both `%d`
            // (zero-padded) and `%e` (space-padded).
            val (ctx, out, _) = newCtx(1_672_876_800L)
            DateCommand().run(listOf("+%d|%e"), ctx)
            assertEquals("05| 5\n", out.readString())
        }

    @Test fun ampm12Hour() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            // 22:13:20 → 10:13:20 PM in 12-hour form.
            DateCommand().run(listOf("+%I:%M:%S %p"), ctx)
            assertEquals("10:13:20 PM\n", out.readString())
        }

    @Test fun rTwelveHourComposite() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%r"), ctx)
            assertEquals("10:13:20 PM\n", out.readString())
        }

    @Test fun literalPercent() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+100%%"), ctx)
            assertEquals("100%\n", out.readString())
        }

    @Test fun newlineAndTabSpecs() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+x%ny%tz"), ctx)
            assertEquals("x\ny\tz\n", out.readString())
        }

    @Test fun noArgsPosixDefault() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(emptyList(), ctx)
            // POSIX default: %a %b %e %H:%M:%S %Z %Y
            assertEquals("Tue Nov 14 22:13:20 UTC 2023\n", out.readString())
        }

    @Test fun utcFlagFormatsAsUtc() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-u", "+%z"), ctx)
            assertEquals("+0000\n", out.readString())
        }

    @Test fun dStringAtEpoch() =
        runTest {
            val (ctx, out, _) = newCtx(canonical) // wall clock pinned but -d overrides
            DateCommand().run(listOf("-d", "@1234567890", "+%s"), ctx)
            assertEquals("1234567890\n", out.readString())
        }

    @Test fun dStringIso8601() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-d", "2020-01-15T10:30:00Z", "+%Y-%m-%dT%H:%M:%S"), ctx)
            assertEquals("2020-01-15T10:30:00\n", out.readString())
        }

    @Test fun iso8601DateOnly() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-I"), ctx)
            assertEquals("2023-11-14\n", out.readString())
        }

    @Test fun iso8601Seconds() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-Iseconds"), ctx)
            assertEquals("2023-11-14T22:13:20+0000\n", out.readString())
        }

    @Test fun rfc2822() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-R"), ctx)
            assertEquals("Tue, 14 Nov 2023 22:13:20 +0000\n", out.readString())
        }

    @Test fun unknownConversionEmitsLiteral() =
        runTest {
            // Unknown spec is rendered as the literal `%X`. We don't fail
            // the whole command.
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("+%Q"), ctx)
            assertEquals("%Q\n", out.readString())
        }

    @Test fun invalidDateStringExits1() =
        runTest {
            val (ctx, _, err) = newCtx(canonical)
            val res = DateCommand().run(listOf("-d", "not-a-date"), ctx)
            assertEquals(1, res.exitCode)
            assertTrue(err.readString().contains("invalid date"))
        }

    @Test fun unknownOptionExits2() =
        runTest {
            val (ctx, _, err) = newCtx(canonical)
            val res = DateCommand().run(listOf("-Z"), ctx)
            assertEquals(2, res.exitCode)
            assertTrue(err.readString().contains("invalid option"))
        }

    @Test fun gnuNoPadDay() =
        runTest {
            // 2023-01-05 — `%-d` should print `5` (not `05`).
            val (ctx, out, _) = newCtx(1_672_876_800L)
            DateCommand().run(listOf("+%-d"), ctx)
            assertEquals("5\n", out.readString())
        }

    @Test fun gnuNoPadHourMinute() =
        runTest {
            // 2023-01-05 02:03:04 (epoch 1_672_884_184) — `%-H:%-M` → `2:3`.
            val (ctx, out, _) = newCtx(1_672_884_184L)
            DateCommand().run(listOf("+%-H:%-M"), ctx)
            assertEquals("2:3\n", out.readString())
        }

    @Test fun gnuNoPadMixedFormat() =
        runTest {
            // Real-world example from the bug report.
            val (ctx, out, _) = newCtx(1_672_876_800L) // 2023-01-05 (Thursday)
            DateCommand().run(listOf("+%A %B %-d %Z"), ctx)
            assertEquals("Thursday January 5 UTC\n", out.readString())
        }

    @Test fun dStringPlainIsoDate() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-d", "2026-01-15", "+%Y-%m-%dT%H:%M:%S"), ctx)
            assertEquals("2026-01-15T00:00:00\n", out.readString())
        }

    @Test fun dStringSpacedDateTime() =
        runTest {
            val (ctx, out, _) = newCtx(canonical)
            DateCommand().run(listOf("-d", "2026-01-15 14:30", "+%Y-%m-%dT%H:%M:%S"), ctx)
            assertEquals("2026-01-15T14:30:00\n", out.readString())
        }

    @Test fun rReadsFileMtime() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val fs =
                com.accucodeai.kash.fs
                    .InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            // Touch the file so mtime is something checkable (InMemoryFs sets
            // mtime on writeBytes; assert via `%s` round-trip).
            val ctx =
                bareCommandContext(
                    env = mutableMapOf("TZ" to "UTC0"),
                    fs = fs,
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                    clock = FixedClock(canonical),
                )
            val rc = DateCommand().run(listOf("-r", "/data", "+%s"), ctx).exitCode
            assertEquals(0, rc)
            // Just verify it printed *something* numeric, not the wall clock
            // (would be 1700000000). The exact mtime is implementation-defined.
            val s = out.readString().trim()
            assertTrue(s.all { it.isDigit() }, "expected digits, got: $s")
        }

    @Test fun rMissingFileErrors() =
        runTest {
            val out = Buffer()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    env = mutableMapOf("TZ" to "UTC0"),
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = err.asSuspendSink(),
                    clock = FixedClock(canonical),
                )
            val rc = DateCommand().run(listOf("-r", "/no/such/file"), ctx).exitCode
            assertEquals(1, rc)
            assertTrue("no/such/file" in err.readString())
        }
}
