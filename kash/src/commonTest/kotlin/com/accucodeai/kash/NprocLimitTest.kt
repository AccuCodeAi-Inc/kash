package com.accucodeai.kash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * RLIMIT_NPROC enforcement: kash refuses to fork past the soft limit,
 * surfaces bash's `fork: retry: Resource temporarily unavailable\n`
 * stderr line, and the shell stays alive afterward.
 */
class NprocLimitTest {
    @Test
    fun spawningPastLimit_emitsForkError() =
        runTest {
            // Tight limit + a fork attempt that *can't* race-complete before
            // the next one fires. `sleep` is coroutine-aware (uses `delay`),
            // which is virtual-time under `runTest` — bg `sleep 0 &` would
            // finish and unregister from the process table before the loop
            // can fork again, so the limit is never observed. Forking a
            // subshell that contains a never-firing `read` keeps the slot
            // pinned for the duration of the test and forces the fork-retry
            // branch to fire on the next dispatch.
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
            try {
                // init + session = 2 live processes already. With `ulimit -u 3`
                // there's exactly one bg slot; the first `&` consumes it and
                // the second has to surface `fork: retry`.
                val r =
                    s.exec(
                        """
                        ulimit -u 3
                        read -t 60 < /dev/null &
                        read -t 60 < /dev/null &
                        """.trimIndent(),
                    )
                assertTrue(
                    r.stderr.contains("fork: retry"),
                    "expected 'fork: retry' on stderr, got:\n${r.stderr}",
                )
                // Init + session + at most (soft - reserved) live readers.
                // Asserts the bomb didn't run away.
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
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
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
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
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
            val s = Kash(registry = standardRegistry(), parentContext = Dispatchers.Default).newSession()
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
