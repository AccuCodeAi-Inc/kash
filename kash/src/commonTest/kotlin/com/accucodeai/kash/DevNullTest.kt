package com.accucodeai.kash

import com.accucodeai.kash.api.installStdio
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.asSuspendSink
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `/dev/null`: the bit-bucket. Writes are discarded; reads return EOF.
 * InMemoryFs ships with it pre-wired so scripts that redirect to/from
 * `/dev/null` (an extremely common pattern in shell idioms) don't fail
 * with ENOENT.
 */
class DevNullTest {
    @Test
    fun redirectStdoutToDevNullSucceeds() =
        runTest {
            val r = Kash().exec("echo hello >/dev/null; echo \$?")
            assertEquals("0\n", r.stdout)
        }

    @Test
    fun redirectStderrToDevNullSucceeds() =
        runTest {
            val r = Kash().exec("echo err 1>&2 2>/dev/null; echo done")
            assertEquals("done\n", r.stdout)
        }

    @Test
    fun readFromDevNullReturnsEof() =
        runTest {
            // `wc -c </dev/null` on bash prints `0`. We don't have wc, but cat
            // is a fine substitute: it prints nothing because EOF is immediate.
            val r = Kash().exec("cat </dev/null; echo done")
            assertEquals("done\n", r.stdout)
        }

    @Test
    fun devNullExistsForFileTest() =
        runTest {
            val r = Kash().exec("[ -e /dev/null ] && echo yes")
            assertEquals("yes\n", r.stdout)
        }

    /**
     * Single-exec sanity: a `>/dev/null` redirection on the first
     * statement of a script doesn't blackhole the rest. The batch path
     * captures `outSink` once at runStreaming entry so this works even
     * if fd-table state is corrupted between statements.
     */
    @Test
    fun followUpCommandsStillProduceStdoutAfterDevNullRedirect() =
        runTest {
            val r = Kash().exec("echo first >/dev/null; echo second; echo third")
            assertEquals("second\nthird\n", r.stdout)
        }

    /**
     * Repro for the interactive bug observed by the user: in a REPL
     * (or any Kash.Session driven across multiple `exec` calls), a
     * redirection on a builtin like `echo > file` leaves the shell's
     * `process.fdTable[1]` pointing at the redirected sink. The next
     * `exec` reads `ctx.stdout` lazily from `fdTable[1]` and ends up
     * writing to the stale sink — `ls`, `pwd`, `echo` all produce
     * invisible output.
     *
     * Same root cause whether the target is /dev/null, a regular file,
     * or any other write target. Reproduces against any builtin that
     * runs on the shell process (vs. forked tools, which install on
     * `child.fdTable`).
     */
    @Test
    fun sessionFdTableSurvivesBuiltinRedirection() =
        runTest {
            val s =
                Kash(registry = standardRegistry()).newSession()
            s.exec("echo first >/dev/null")
            val r = s.exec("echo second")
            assertEquals("second\n", r.stdout)
        }

    @Test
    fun sessionFdTableSurvivesBuiltinRedirectionToRegularFile() =
        runTest {
            // User-reported variant: `echo 1 > hell` breaks subsequent commands too.
            val s =
                Kash(registry = standardRegistry()).newSession()
            s.exec("echo 1 > /tmp/hell")
            val r = s.exec("echo afterwards")
            assertEquals("afterwards\n", r.stdout)
        }

    /**
     * Direct repro of the user-observed REPL bug at the fd-table level.
     * The REPL passes `ctx.stdout` (lazy getter reading
     * `process.fdTable[1].ofd.sink`) to each `runStreaming` call. If a
     * builtin redirection mutates the shell's fd 1 without restoration,
     * the next REPL line resolves `ctx.stdout` to the stale sink — `ls`,
     * `pwd`, `echo` all go nowhere.
     *
     * Asserts the invariant directly: after `echo 1 > /dev/null` the
     * shell process's fd 1 OFD must point at the same sink it did before.
     */
    @Test
    fun shellProcessFd1RestoredAfterBuiltinRedirection() =
        runTest {
            val s =
                Kash(registry = standardRegistry()).newSession()
            val hostStdout = kotlinx.io.Buffer()
            s.process.installStdio(
                stdin = EmptySuspendSource,
                stdout = hostStdout.asSuspendSink(),
                stderr = NullSuspendSink,
            )
            val originalFd1Sink =
                s.process.fdTable[1]
                    ?.ofd
                    ?.sink
            kotlin.test.assertNotNull(originalFd1Sink)

            s.execStreaming(
                script = "echo 1 > /dev/null",
                stdin = EmptySuspendSource,
                stdout = hostStdout.asSuspendSink(),
                stderr = NullSuspendSink,
                stdinIsTty = false,
            )

            val afterFd1Sink =
                s.process.fdTable[1]
                    ?.ofd
                    ?.sink
            kotlin.test.assertSame(
                originalFd1Sink,
                afterFd1Sink,
                "fd 1 OFD sink must be restored after redirected builtin",
            )
        }

    /** Same shape, regular-file target (the `echo 1 > hell` variant). */
    @Test
    fun shellProcessFd1RestoredAfterFileRedirection() =
        runTest {
            val s =
                Kash(registry = standardRegistry()).newSession()
            val hostStdout = kotlinx.io.Buffer()
            s.process.installStdio(
                stdin = EmptySuspendSource,
                stdout = hostStdout.asSuspendSink(),
                stderr = NullSuspendSink,
            )
            val originalFd1Sink =
                s.process.fdTable[1]
                    ?.ofd
                    ?.sink

            s.execStreaming(
                script = "echo 1 > /tmp/redir-target",
                stdin = EmptySuspendSource,
                stdout = hostStdout.asSuspendSink(),
                stderr = NullSuspendSink,
                stdinIsTty = false,
            )

            val afterFd1Sink =
                s.process.fdTable[1]
                    ?.ofd
                    ?.sink
            kotlin.test.assertSame(originalFd1Sink, afterFd1Sink)
        }

    /**
     * Repro for the "ls / is empty" symptom — verify the FS itself isn't
     * mutated by a `>/dev/null` write. Listing root should still show the
     * standard mounts (/usr, /bin, /dev).
     */
    @Test
    fun fsStillListableAfterDevNullRedirect() =
        runTest {
            val r = Kash().exec("echo go >/dev/null\nls /")
            assertEquals(0, r.exitCode)
            val lines =
                r.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .toSet()
            kotlin.test.assertTrue(
                "bin" in lines || "usr" in lines || "dev" in lines,
                "ls / produced empty / unexpected output: <${r.stdout}>",
            )
        }
}
