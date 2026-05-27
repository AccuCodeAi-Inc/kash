package com.accucodeai.kash.api.clock

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.offsetAt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Unified time source for the shell. Replaces the parallel injections
 * we used to thread separately:
 *  - wall-epoch time (backs `$EPOCHSECONDS`, `$EPOCHREALTIME`, `date`),
 *  - monotonic since-shell-start time (backs `$SECONDS`, `times` user/sys),
 *  - local timezone (for `date +%z`/`%Z`, `git commit` author offsets,
 *    `ls -l` mtime rendering, `git log` `Date:` formatting).
 *
 * Lives in `:api` so tools (`date`, `ls`, `git`) and the shell interpreter
 * all reach the same single source through `ctx.process.machine.clock`.
 * Production binds [SystemShellClock]; conformance tests bind a virtual
 * variant that reads `kotlinx.coroutines.test`'s scheduler and pins
 * timezone to UTC so `+%s`/`%z` round-trip deterministically.
 */
public interface ShellClock {
    /** Wall-clock instant — backs `$EPOCHSECONDS`, `$EPOCHREALTIME`, `date`. */
    public fun now(): Instant

    /** Time since shell start — backs `$SECONDS` and `times` user/sys. */
    public fun elapsedSinceShellStart(): Duration

    /**
     * Local timezone for wall-clock rendering. Default [TimeZone.UTC] so
     * deterministic tests and headless contexts produce stable output;
     * [SystemShellClock] returns [TimeZone.currentSystemDefault] which
     * resolves to the host's IANA zone (e.g. `America/Los_Angeles`).
     *
     * Consumers that want DST-aware wall-clock conversion should go
     * through this; pure offset-at-now helpers can use
     * [localTzOffsetMinutes] / [localTz] which derive from this same
     * zone via `offsetAt(now())`.
     */
    public fun localTimeZone(): TimeZone = TimeZone.UTC

    /**
     * Local timezone offset from UTC at "now", in minutes. East is
     * positive (`+0100` → `60`), west is negative (`-0800` → `-480`).
     * Default derives from [localTimeZone] — override only if the
     * clock impl wants to detach the numeric offset from the zone
     * (most don't need to).
     */
    public fun localTzOffsetMinutes(): Int = localTimeZone().offsetAt(now()).totalSeconds / 60

    /**
     * Default-formatted tz string (`±HHMM`) at "now". Used by git
     * porcelain that wants a bash-shaped author tz.
     * `UtcOffset.Formats.FOUR_DIGITS` is purpose-built for this
     * shape — always four digits, no separator.
     */
    public fun localTz(): String = localTimeZone().offsetAt(now()).format(UtcOffset.Formats.FOUR_DIGITS)
}

/** Default — wall via [Clock.System], monotonic via [TimeSource.Monotonic]. */
public open class SystemShellClock : ShellClock {
    private val startMark = TimeSource.Monotonic.markNow()

    override fun now(): Instant = Clock.System.now()

    override fun elapsedSinceShellStart(): Duration = startMark.elapsedNow()

    public override fun localTimeZone(): TimeZone = TimeZone.currentSystemDefault()
}

/**
 * Process-wide default — handed out by interface defaults so an embedder
 * that doesn't inject a clock still sees a stable monotonic start mark.
 * `KashMachine.clock`'s default returns this instance; explicit
 * implementations override and hold their own instance.
 */
public val DefaultShellClock: ShellClock = SystemShellClock()
