package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Coproc end-to-end. Mirrors the structure of `external/bash/tests/coproc.tests`
 * line-by-line so a hang in this test points at exactly the bash-conformance
 * scenario it replicates.
 *
 * Each test wraps in `withTimeout([DEADLOCK_GUARD_MS])` so a deadlock
 * surfaces as a failure with a stack trace instead of the 1-minute `runTest`
 * ceiling miss in the conformance runner. This is a *liveness* backstop, not
 * a perf bound: a healthy round-trip is milliseconds and a real deadlock
 * hangs forever, so the value just has to sit comfortably above any
 * loaded-machine completion and below the ceiling — generous on purpose so a
 * CPU-starved full build doesn't false-fail.
 */
class CoprocTest {
    private companion object {
        const val DEADLOCK_GUARD_MS = 30_000L
    }

    @Test fun anonymous_coproc_brace_body_one_shot() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc { echo a b c; }
                        read LINE <&${'$'}{COPROC[0]}
                        echo "GOT: ${'$'}LINE"
                        wait ${'$'}COPROC_PID
                        """.trimIndent(),
                    )
                assertEquals(0, r.exitCode, "stderr=${r.stderr}")
                assertEquals("GOT: a b c\n", r.stdout)
            }
        }

    @Test fun coproc_pid_is_decimal_integer() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc { echo hi; }
                        case ${'$'}COPROC_PID in
                          [0-9]*) echo OK ;;
                          *) echo NOT_INT ;;
                        esac
                        read _ <&${'$'}{COPROC[0]}
                        wait ${'$'}COPROC_PID
                        """.trimIndent(),
                    )
                assertEquals(0, r.exitCode, "stderr=${r.stderr}")
                assertEquals("OK\n", r.stdout)
            }
        }

    // The `REFLECT` scenario from `coproc.tests` line 31. cat reads its
    // stdin until EOF and writes whatever it got. The parent kills cat
    // after the round-trip so the body's read completes (no `{var}<&N-`
    // move-fd form needed — that's a separate unimplemented redirect).

    /** Diagnostic: did `>&${'$'}{REFLECT[1]}` actually route to the pipe?
     *  Sends data, kills cat, reads — no concurrent block expected. */
    @Test fun coproc_reflect_write_then_kill_then_read() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc REFLECT { cat - ; }
                        echo flop >&${'$'}{REFLECT[1]}
                        kill ${'$'}REFLECT_PID 2>/dev/null
                        read LINE <&${'$'}{REFLECT[0]}
                        echo "GOT: ${'$'}LINE"
                        """.trimIndent(),
                    )
                println("DIAG stdout=[${r.stdout}] stderr=[${r.stderr}] exit=${r.exitCode}")
                assertTrue(r.stdout.contains("GOT: flop"), "stdout=${r.stdout} stderr=${r.stderr}")
            }
        }

    /** Even simpler: write data to the coproc pipe, then immediately
     *  read from it BEFORE killing — does cat's echo-back even arrive? */
    @Test fun coproc_reflect_read_before_kill() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc REFLECT { cat - ; }
                        echo flop >&${'$'}{REFLECT[1]}
                        # Give cat a moment to read+echo before we read.
                        sleep 0.2
                        read LINE <&${'$'}{REFLECT[0]}
                        echo "GOT: ${'$'}LINE"
                        kill ${'$'}REFLECT_PID 2>/dev/null
                        """.trimIndent(),
                    )
                println("DIAG2 stdout=[${r.stdout}] stderr=[${r.stderr}] exit=${r.exitCode}")
                assertTrue(r.stdout.contains("GOT: flop"), "stdout=${r.stdout} stderr=${r.stderr}")
            }
        }

    /** `coproc xcase -n -u` — simpleCommand-with-args body form. The
     *  command itself won't exist, but the parse must succeed and the
     *  intrinsic should reach command-not-found, not syntax error. */
    @Test fun coproc_simple_command_with_flags_parses() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        "coproc nosuchcmd -n -u",
                    )
                assertTrue(
                    !r.stderr.contains("syntax error"),
                    "stderr=${r.stderr}",
                )
            }
        }

    @Test fun coproc_bare_simple_no_args() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        "coproc nosuchcmd",
                    )
                println("coproc nosuchcmd: stderr=[${r.stderr}] exit=${r.exitCode}")
                assertTrue(
                    !r.stderr.contains("syntax error"),
                    "stderr=${r.stderr}",
                )
            }
        }

    @Test fun coproc_simple_with_arg_no_flag() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        "coproc nosuchcmd arg",
                    )
                println("coproc nosuchcmd arg: stderr=[${r.stderr}] exit=${r.exitCode}")
                assertTrue(
                    !r.stderr.contains("syntax error"),
                    "stderr=${r.stderr}",
                )
            }
        }

    @Test fun named_coproc_reflect_round_trip_then_kill() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc REFLECT { cat - ; }
                        echo flop >&${'$'}{REFLECT[1]}
                        read LINE <&${'$'}{REFLECT[0]}
                        echo "GOT: ${'$'}LINE"
                        kill ${'$'}REFLECT_PID 2>/dev/null
                        wait ${'$'}REFLECT_PID 2>/dev/null
                        """.trimIndent(),
                    )
                assertTrue(r.stdout.contains("GOT: flop"), "stdout=${r.stdout} stderr=${r.stderr}")
            }
        }

    /** Bash dynamic-fd-allocation `{varname}>file`: runtime picks a fresh
     *  high fd, opens the file there, assigns the slot number to `$varname`. */
    @Test fun var_redir_assigns_allocated_fd() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val fs =
                    com.accucodeai.kash.fs
                        .InMemoryFs()
                fs.mkdirs("/tmp")
                val r =
                    Kash(fs = fs, registry = standardRegistry()).exec(
                        """
                        exec {fd}>/tmp/k
                        case ${'$'}fd in
                          [0-9]*) echo "fd=numeric" ;;
                          *) echo "fd=NON_NUMERIC[${'$'}fd]" ;;
                        esac
                        echo via >&${'$'}fd
                        exec {fd}>&-
                        cat /tmp/k
                        """.trimIndent(),
                    )
                assertTrue(r.stdout.contains("fd=numeric"), "stdout=${r.stdout} stderr=${r.stderr}")
                assertTrue(r.stdout.contains("via"), "stdout=${r.stdout} stderr=${r.stderr}")
            }
        }

    /** POSIX move-fd `<&N-`: dup source onto target then close source.
     *  After the move, reading from the old fd must fail (it's closed). */
    @Test fun move_fd_releases_source() =
        runBlocking {
            withTimeout(DEADLOCK_GUARD_MS) {
                val r =
                    Kash(registry = standardRegistry()).exec(
                        """
                        coproc R { cat - ; }
                        OLD=${'$'}{R[0]}
                        exec 4<&${'$'}{R[0]}-
                        # After move, reading from old fd should error.
                        if read X <&${'$'}OLD 2>/dev/null; then
                          echo "ERR: old fd still readable"
                        else
                          echo "OK: old fd closed"
                        fi
                        kill ${'$'}R_PID 2>/dev/null
                        """.trimIndent(),
                    )
                assertTrue(r.stdout.contains("OK: old fd closed"), "stdout=${r.stdout} stderr=${r.stderr}")
            }
        }
}
