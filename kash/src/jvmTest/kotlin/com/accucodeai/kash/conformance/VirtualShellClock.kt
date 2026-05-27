package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.clock.ShellClock
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * A [ShellClock] backed by `kotlinx.coroutines.test`'s virtual time so
 * conformance tests get a consistent clock that advances on `sleep`.
 *
 * Why: production [com.accucodeai.kash.api.clock.SystemShellClock] reads
 * `TimeSource.Monotonic`, which doesn't move during `runTest`. Tests that
 * rely on `$SECONDS` advancing across a `sleep N` (e.g. dynvar) need
 * `$SECONDS` to read from the same clock `kotlinx.coroutines.delay` writes
 * to — namely [TestCoroutineScheduler.currentTime].
 *
 * Wall epoch is pinned to a stable point (2023-11-14T22:13:20Z by default,
 * = 1_700_000_000s) plus the scheduler's elapsed milliseconds, so tests
 * that read `$EPOCHSECONDS` or run `date +%s` get deterministic output.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class VirtualShellClock(
    private val scheduler: TestCoroutineScheduler,
    private val wallEpochMs: Long = 1_700_000_000_000L,
) : ShellClock {
    private val startMillis: Long = scheduler.currentTime

    override fun now(): Instant = Instant.fromEpochMilliseconds(wallEpochMs + scheduler.currentTime)

    override fun elapsedSinceShellStart() = (scheduler.currentTime - startMillis).milliseconds
}
