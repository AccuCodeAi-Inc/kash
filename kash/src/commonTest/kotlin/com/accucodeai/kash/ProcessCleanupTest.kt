package com.accucodeai.kash

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts the exec-shell teardown contract in [Interpreter.runShellScript]
 * — every `${THIS_SH} -c '…'` / `sh ./script` invocation forks a
 * [com.accucodeai.kash.api.KashProcess] (and registers a signal-router
 * deliverer for it); both must be released no matter how the inner
 * shell exits.
 *
 * Regression coverage for the two leaks fixed in `runShellScript`:
 *   1. signalRouter handler captured the fork interpreter and was
 *      registered *before* the try-finally, so a setup-statement throw
 *      retained the entire fork (varTable, jobControl, parent-state
 *      copies) for the session's lifetime.
 *   2. `machine.unregisterProcess` was scoped to the inner runStreaming
 *      try, so a setup-statement throw also leaked the KashProcess from
 *      the machine's pid map.
 *
 * Asserting the machine's [processTable] stays bounded across many
 * `sh -c` invocations is the cheapest signal that catches both: the
 * router-handler closure keeps the fork (and hence its process)
 * reachable, and the process map is a direct view of the second leak.
 */
class ProcessCleanupTest {
    @Test
    fun shDashCDoesNotLeakProcesses() =
        runTest {
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val baseline = s.machine.processTable.size
                // 50 exec'd sub-shells. If runShellScript leaks a
                // KashProcess per invocation the table grows linearly;
                // a working unregister keeps it flat (within ±1 for the
                // brief reaping window).
                for (i in 1..50) {
                    s.exec("sh -c 'echo $i'")
                }
                val after = s.machine.processTable.size
                assertTrue(
                    after - baseline <= 2,
                    "expected bounded processTable after 50 sh -c invocations, " +
                        "baseline=$baseline after=$after",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun shScriptFileDoesNotLeakProcesses() =
        runTest {
            // Same contract through the `sh FILE` entry path — the
            // file-script branch in `runShellScript` shares the setup
            // and teardown code so leaking here would also leak the
            // -c form, but assert both so a future refactor that
            // splits them can't silently lose this coverage.
            val fs = InMemoryFs()
            fs.writeBytes("/work.sh", "x=$$\necho ok=\$x".encodeToByteArray())
            val s =
                Kash(fs = fs, registry = standardRegistry(), parentContext = coroutineContext)
                    .newSession()
            try {
                val baseline = s.machine.processTable.size
                for (i in 1..50) s.exec("sh /work.sh")
                val after = s.machine.processTable.size
                assertTrue(
                    after - baseline <= 2,
                    "processTable grew unbounded across 50 `sh FILE` runs " +
                        "(baseline=$baseline, after=$after) — runShellScript " +
                        "is leaking the fork's KashProcess",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun shDashCCleansUpOnNonZeroExit() =
        runTest {
            // Inner shell exits non-zero (false). The teardown finally
            // must still run; otherwise a script full of error-exit
            // sub-shells would leak one process each.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val baseline = s.machine.processTable.size
                for (i in 1..30) {
                    val r = s.exec("sh -c 'exit 7'")
                    assertEquals(7, r.exitCode)
                }
                val after = s.machine.processTable.size
                assertTrue(
                    after - baseline <= 2,
                    "non-zero-exit sub-shell leaked: baseline=$baseline after=$after",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun shDashCCleansUpOnParseError() =
        runTest {
            // Parse error inside `sh -c '...'` is reported via
            // emitShellParseError, which sits inside the outer
            // try-finally — the fork's process must still be
            // unregistered. Regression: before the finally was widened,
            // a parser exception (lex-level IllegalStateException)
            // returned early from the setup phase, skipping
            // machine.unregisterProcess.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val baseline = s.machine.processTable.size
                for (i in 1..30) {
                    // Unbalanced quote → parse failure → non-zero exit.
                    s.exec("sh -c 'echo \"unterminated'")
                }
                val after = s.machine.processTable.size
                assertTrue(
                    after - baseline <= 2,
                    "parse-failed sub-shell leaked: baseline=$baseline after=$after",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun nestedShDashCDoesNotLeak() =
        runTest {
            // `sh -c 'sh -c ...'` exercises the fork-then-fork path:
            // both forks must be unregistered. If only the outer one
            // unregisters, the inner pid leaks and the table grows.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val baseline = s.machine.processTable.size
                for (i in 1..25) {
                    s.exec("sh -c 'sh -c \"echo $i\"'")
                }
                val after = s.machine.processTable.size
                assertTrue(
                    after - baseline <= 2,
                    "nested sh -c leaked: baseline=$baseline after=$after",
                )
            } finally {
                s.close()
            }
        }

    @Test
    fun shellPidIsFreshInsideShDashC() =
        runTest {
            // Confirms the fix that gives `sh -c '...'` its own $$. If
            // the fork inherited the parent's shellPid, two back-to-
            // back invocations would both print the same value as the
            // outer shell. With the exec-boundary reset they each
            // produce a distinct pid that matches their own
            // KashProcess.pid — and the SignalRouter registration is
            // therefore keyed correctly.
            val s = Kash(registry = standardRegistry(), parentContext = coroutineContext).newSession()
            try {
                val parent = s.exec("echo $$").stdout.trim()
                val child = s.exec("sh -c 'echo $$'").stdout.trim()
                assertTrue(parent.isNotEmpty() && child.isNotEmpty())
                assertTrue(
                    parent != child,
                    "expected `sh -c` to install a fresh shellPid; " +
                        "parent=$parent child=$child",
                )
            } finally {
                s.close()
            }
        }
}
