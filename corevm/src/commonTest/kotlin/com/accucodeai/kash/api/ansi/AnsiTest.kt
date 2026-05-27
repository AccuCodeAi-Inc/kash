package com.accucodeai.kash.api.ansi

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnsiTest {
    private fun ctx(
        env: Map<String, String> = emptyMap(),
        stdoutTty: Boolean = false,
        stderrTty: Boolean = false,
    ) = bareCommandContext(
        fs = InMemoryFs(),
        env = env.toMutableMap(),
        cwd = "/",
        stdin = Buffer().asSuspendSource(),
        stdout = Buffer().asSuspendSink(),
        stderr = Buffer().asSuspendSink(),
        stdoutIsTty = stdoutTty,
        stderrIsTty = stderrTty,
    )

    @Test fun parseValues() {
        assertEquals(ColorMode.AUTO, ColorMode.parse(null))
        assertEquals(ColorMode.AUTO, ColorMode.parse(""))
        assertEquals(ColorMode.AUTO, ColorMode.parse("auto"))
        assertEquals(ColorMode.AUTO, ColorMode.parse("tty"))
        assertEquals(ColorMode.ALWAYS, ColorMode.parse("always"))
        assertEquals(ColorMode.ALWAYS, ColorMode.parse("YES"))
        assertEquals(ColorMode.NEVER, ColorMode.parse("never"))
        assertEquals(ColorMode.NEVER, ColorMode.parse("no"))
        assertNull(ColorMode.parse("maybe"))
    }

    @Test fun alwaysWrapsRegardlessOfTty() {
        val s = Ansi.stylerFor(ctx(stdoutTty = false), mode = ColorMode.ALWAYS)
        assertTrue(s.on)
        assertEquals("[31mfoo[0m", s.style("foo", Sgr.FG_RED))
        assertEquals("[1;31mfoo[0m", s.style("foo", Sgr.BOLD, Sgr.FG_RED))
    }

    @Test fun neverReturnsBareText() {
        val s = Ansi.stylerFor(ctx(stdoutTty = true), mode = ColorMode.NEVER)
        assertFalse(s.on)
        assertEquals("foo", s.style("foo", Sgr.FG_RED))
    }

    @Test fun autoFollowsTtyFlag() {
        assertTrue(Ansi.stylerFor(ctx(stdoutTty = true)).on)
        assertFalse(Ansi.stylerFor(ctx(stdoutTty = false)).on)
    }

    @Test fun autoChecksStderrWhenAsked() {
        val c = ctx(stdoutTty = false, stderrTty = true)
        assertFalse(Ansi.stylerFor(c, Ansi.Stream.STDOUT).on)
        assertTrue(Ansi.stylerFor(c, Ansi.Stream.STDERR).on)
    }

    @Test fun noColorBeatsAuto() {
        val c = ctx(env = mapOf("NO_COLOR" to "1"), stdoutTty = true)
        assertFalse(Ansi.stylerFor(c).on)
    }

    @Test fun noColorDoesNotBeatExplicitAlways() {
        val c = ctx(env = mapOf("NO_COLOR" to "1"))
        assertTrue(Ansi.stylerFor(c, mode = ColorMode.ALWAYS).on)
    }

    @Test fun termDumbDisablesAuto() {
        val c = ctx(env = mapOf("TERM" to "dumb"), stdoutTty = true)
        assertFalse(Ansi.stylerFor(c).on)
    }

    @Test fun clicolorForceEnablesEvenOffTty() {
        val c = ctx(env = mapOf("CLICOLOR_FORCE" to "1"), stdoutTty = false)
        assertTrue(Ansi.stylerFor(c).on)
    }

    @Test fun clicolorForceZeroIsNotForce() {
        val c = ctx(env = mapOf("CLICOLOR_FORCE" to "0"), stdoutTty = false)
        assertFalse(Ansi.stylerFor(c).on)
    }

    @Test fun clicolorZeroDisables() {
        val c = ctx(env = mapOf("CLICOLOR" to "0"), stdoutTty = true)
        assertFalse(Ansi.stylerFor(c).on)
    }

    @Test fun noCodesReturnsBareText() {
        val s = Ansi.stylerFor(ctx(), mode = ColorMode.ALWAYS)
        assertEquals("foo", s.style("foo"))
    }
}
