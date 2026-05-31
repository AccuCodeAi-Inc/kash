package com.accucodeai.kash

import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A [SuspendSource] that never yields data and never reaches EOF — its
 * read parks until the reading coroutine is cancelled. Used to pin a
 * background `read` job's process-table slot for a test's duration without
 * relying on wall-clock timing: on the `runTest` test dispatcher the parked
 * reader never reaps its slot, so the fork limit is observed deterministically.
 */
private val neverSource: SuspendSource =
    object : SuspendSource {
        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = awaitCancellation()

        override fun close() {}
    }

/**
 * RLIMIT_NPROC enforcement: kash refuses to fork past the soft limit,
 * surfaces bash's `fork: retry: Resource temporarily unavailable\n`
 * stderr line, and the shell stays alive afterward.
 *
 * Every session is built with `parentContext = coroutineContext` so all
 * background work runs on the `runTest` test dispatcher (single-threaded,
 * virtual time) rather than the shared `Dispatchers.Default` pool — the
 * latter reintroduced a scheduling race between the foreground statement
 * loop and the background job's reap that made these flaky under load.
 */
class NprocLimitTest {
    @Test
    fun spawningPastLimit_emitsForkError() =
        runTest {
            // Tight limit + a background job whose slot stays pinned. The
            // earlier `read -t 60 < /dev/null &` did NOT pin anything:
            // `/dev/null` is instant EOF, so the reader reaped its slot
            // immediately and `fork: retry` only fired if the foreground
            // loop happened to out-race the bg reap — the flake.
            //
            // The pin needs two things. (1) A *builtin* (`read`), not a tool
            // like `sleep`: a tool double-forks and its inner fork failure is
            // swallowed into the job's own stderr buffer, not the foreground
            // one we assert on. (2) A stdin that never EOFs, so the reader
            // parks instead of reaping. Only the FIRST statement of a script
            // receives the supplied stdin (runStreaming resets it to an empty
            // EOF source thereafter), so `ulimit` is set in a prior `exec`
            // (the rlimit persists on the session process) — that makes
            // `read &` the first statement and hands it [neverSource].
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                // init + session = 2 live processes already. With `ulimit -u 3`
                // there's exactly one bg slot; the first `read &` parks on
                // [neverSource] and consumes it, so the second `read &`'s
                // pre-fork must surface `fork: retry` on the foreground stderr.
                s.exec("ulimit -u 3")
                val errBuf = Buffer()
                s.execStreaming(
                    script = "read &\nread &\n",
                    stdin = neverSource,
                    stdout = NullSuspendSink,
                    stderr = errBuf.asSuspendSink(),
                )
                val err = errBuf.readString()
                assertTrue(
                    err.contains("fork: retry"),
                    "expected 'fork: retry' on stderr, got:\n$err",
                )
                // Init + session + the one parked reader. Asserts the bomb
                // didn't run away.
                assertTrue(
                    s.machine.processTable.size <= 10,
                    "expected bounded table, got ${s.machine.processTable.size}",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun subshellFork_alsoChecked() =
        runTest {
            // Nested subshells past the limit: each `(...)` is a fork. With
            // a soft limit of 3 and init+session already taking 2 slots,
            // the first level subshell consumes 3, the second level fails.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("ulimit -u 3; ( ( ( ( true ) ) ) )")
                assertTrue(
                    r.stderr.contains("fork: retry"),
                    "expected fork error in nested subshells, got: ${r.stderr}",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun forkFailure_doesNotCrashShell() =
        runTest {
            // After hitting the limit, subsequent commands still work.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r =
                    s.exec(
                        """
                        ulimit -u 4
                        for i in 1 2 3 4 5 6 7 8; do sleep 5 & done
                        wait
                        echo OK
                        """.trimIndent(),
                    )
                assertTrue(r.stdout.contains("OK"), "shell didn't survive past the bomb, stdout:\n${r.stdout}")
            } finally {
                s.close()
            }
        }

    @Test
    fun underLimit_bgWorksFine() =
        runTest {
            // Sanity: with the default 4096 limit, normal bg workloads
            // are unaffected.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val r = s.exec("for i in 1 2 3; do true & done; wait")
                assertTrue(
                    !r.stderr.contains("fork:"),
                    "no fork error expected under default limit, got: ${r.stderr}",
                )
            } finally {
                s.close()
            }
        }
}
