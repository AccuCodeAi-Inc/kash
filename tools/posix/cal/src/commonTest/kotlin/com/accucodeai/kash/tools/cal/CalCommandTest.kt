@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.accucodeai.kash.tools.cal

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.clock.ShellClock
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

private class NullFs : FileSystem {
    override fun exists(path: String): Boolean = false

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource = Buffer().asSuspendSource()

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = Buffer().asSuspendSink()

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {}
}

private class FixedClock(
    private val seconds: Long,
) : ShellClock {
    override fun now(): Instant = Instant.fromEpochSeconds(seconds)

    override fun elapsedSinceShellStart(): Duration = Duration.ZERO
}

private fun newCtx(epochSeconds: Long = 0L): Triple<CommandContext, Buffer, Buffer> {
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
            clock = FixedClock(epochSeconds),
        )
    return Triple(ctx, out, err)
}

class CalCommandTest {
    // ---- Pure helpers ----

    @Test fun leapYear2024_feb_has_29() {
        assertEquals(29, daysInMonth(2024, 2))
    }

    @Test fun centuryNon400_1900_feb_has_28() {
        assertEquals(28, daysInMonth(1900, 2))
    }

    @Test fun century400_2000_feb_has_29() {
        assertEquals(29, daysInMonth(2000, 2))
    }

    @Test fun knownFirstDays_jan2024_is_monday() {
        // 2024-01-01 was a Monday. dow=1 in 0=Sun..6=Sat.
        assertEquals(1, dayOfWeekFirst(2024, 1))
    }

    @Test fun knownFirstDays_jan2000_is_saturday() {
        // 2000-01-01 was a Saturday.
        assertEquals(6, dayOfWeekFirst(2000, 1))
    }

    @Test fun knownFirstDays_may2026_is_friday() {
        // 2026-05-01 is a Friday.
        assertEquals(5, dayOfWeekFirst(2026, 5))
    }

    @Test fun dayOfYear_mar1_nonleap() {
        // Jan=31, Feb=28 → Mar 1 = day 60.
        assertEquals(60, dayOfYear(2025, 3, 1))
    }

    @Test fun dayOfYear_mar1_leap() {
        assertEquals(61, dayOfYear(2024, 3, 1))
    }

    // ---- Command-level ----

    @Test fun noArgs_uses_clock_currentMonth() =
        runTest {
            // epoch 1700000000 = 2023-11-14 22:13:20 UTC → November 2023.
            val (ctx, out, err) = newCtx(1_700_000_000L)
            val rc = CalCommand().run(emptyList(), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            val text = out.readString()
            assertTrue(text.contains("November 2023"), "missing title: <$text>")
            assertTrue(text.contains("Su Mo Tu We Th Fr Sa"), "missing default Sunday-first header")
        }

    @Test fun twoArgs_specificMonth() =
        runTest {
            val (ctx, out, err) = newCtx()
            val rc = CalCommand().run(listOf("1", "2026"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            val text = out.readString()
            assertTrue(text.contains("January 2026"))
            // 2026-01-01 is a Thursday. So the first row should have blanks
            // for Su/Mo/Tu/We, then " 1  2  3".
            assertTrue(text.contains(" 1  2  3"), "Jan 2026 first row mis-aligned: <$text>")
        }

    @Test fun singleArg_year_emits12months() =
        runTest {
            val (ctx, out, err) = newCtx()
            val rc = CalCommand().run(listOf("2026"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            val text = out.readString()
            for (
            m in
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )
            ) {
                assertTrue(text.contains(m), "year layout missing month $m")
            }
            assertTrue(text.contains("2026"), "year header missing")
        }

    @Test fun mondayFirst_headerSwapped() =
        runTest {
            val (ctx, out, _) = newCtx()
            CalCommand().run(listOf("-m", "5", "2026"), ctx)
            val text = out.readString()
            assertTrue(text.contains("Mo Tu We Th Fr Sa Su"), "monday-first header expected: <$text>")
        }

    @Test fun julian_emits_3digit_dayOfYear() =
        runTest {
            val (ctx, out, _) = newCtx()
            // March 2026: Jan=31 + Feb=28 = 59 days before March; so Mar 1 = doy 060.
            CalCommand().run(listOf("-j", "3", "2026"), ctx)
            val text = out.readString()
            assertTrue(text.contains(" 60"), "expected day-of-year 060 cell: <$text>")
        }

    @Test fun threeMonths_emits_threeTitlesSideBySide() =
        runTest {
            val (ctx, out, _) = newCtx()
            CalCommand().run(listOf("-3", "5", "2026"), ctx)
            val text = out.readString()
            assertTrue(text.contains("April 2026"))
            assertTrue(text.contains("May 2026"))
            assertTrue(text.contains("June 2026"))
            // First line should have all three side-by-side.
            val firstLine = text.lineSequence().first()
            assertTrue(firstLine.contains("April") && firstLine.contains("May") && firstLine.contains("June"))
        }

    @Test fun invalidMonth_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = CalCommand().run(listOf("13", "2026"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("month '13' out of range"))
        }

    @Test fun yearZero_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = CalCommand().run(listOf("0"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("out of range"))
        }

    @Test fun year9999_works() =
        runTest {
            val (ctx, out, err) = newCtx()
            val rc = CalCommand().run(listOf("9999"), ctx)
            assertEquals(0, rc.exitCode, err.readString())
            assertTrue(out.readString().contains("December"))
        }

    @Test fun tooManyArgs_errors() =
        runTest {
            val (ctx, _, err) = newCtx()
            val rc = CalCommand().run(listOf("1", "2", "3"), ctx)
            assertEquals(2, rc.exitCode)
            assertTrue(err.readString().contains("too many"))
        }

    @Test fun feb2024_lastRow_has_29() =
        runTest {
            val (ctx, out, _) = newCtx()
            CalCommand().run(listOf("2", "2024"), ctx)
            val text = out.readString()
            assertTrue(text.contains("29"), "Feb 2024 should include day 29: <$text>")
        }

    @Test fun help_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = CalCommand().run(listOf("--help"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("Usage"))
        }

    @Test fun version_exits_0() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = CalCommand().run(listOf("-V"), ctx)
            assertEquals(0, rc.exitCode)
            assertTrue(out.readString().contains("cal"))
        }

    @Test fun bundledOpts_mj_accepted() =
        runTest {
            val (ctx, out, _) = newCtx()
            val rc = CalCommand().run(listOf("-mj", "5", "2026"), ctx)
            assertEquals(0, rc.exitCode)
            val text = out.readString()
            // Julian header has 3-char cells: " Mo  Tu  We ...".
            assertTrue(text.contains("Mo") && text.contains("Tu"), "expected monday-first header: <$text>")
            assertTrue(text.indexOf("Mo") < text.indexOf("Tu"), "Mo before Tu in monday-first")
        }
}
