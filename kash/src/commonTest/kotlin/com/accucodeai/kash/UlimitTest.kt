package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the `ulimit` builtin's read/write/format surface. NPROC
 * enforcement is in [NprocLimitTest]; this file pins the builtin's own
 * argument parsing and rlimit-map plumbing.
 *
 * The procsub-bearing cases (`<(...)`) spawn background driver coroutines;
 * threading `coroutineContext` into [Kash] keeps them on the `runTest` test
 * dispatcher (virtual time, single-threaded) instead of the shared
 * `Dispatchers.Default` pool, so fd reclamation is deterministic rather
 * than racing the pool under concurrent suite load.
 */
class UlimitTest {
    private suspend fun out(script: String): String = Kash(parentContext = coroutineContext).exec(script).stdout

    private suspend fun result(script: String) = Kash(parentContext = coroutineContext).exec(script)

    @Test
    fun dashU_readsDefault() =
        runTest {
            // Init seeds RLIMIT_NPROC soft=4096 — see DefaultKashMachine.ensureInit.
            assertEquals("4096\n", out("ulimit -u"))
        }

    @Test
    fun dashU_setsAndReads() =
        runTest {
            assertEquals("100\n", out("ulimit -u 100; ulimit -u"))
        }

    @Test
    fun unlimitedValue() =
        runTest {
            assertEquals("unlimited\n", out("ulimit -u unlimited; ulimit -u"))
        }

    @Test
    fun softAndHard_separate() =
        runTest {
            // After `ulimit -u 200` both soft and hard are 200. Then
            // `ulimit -S -u 100` lowers soft only. -H reads hard (200),
            // -S reads soft (100).
            val r = result("ulimit -u 200; ulimit -S -u 100; ulimit -H -u; ulimit -S -u")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("200\n100\n", r.stdout)
        }

    @Test
    fun cannotRaiseHardLimit() =
        runTest {
            // Lower hard to 100, then try to raise it to 200 — must fail.
            val r = result("ulimit -H -u 100; ulimit -H -u 200")
            assertTrue(r.exitCode != 0, "expected non-zero exit, got ${r.exitCode}")
            assertTrue(
                r.stderr.contains("cannot modify limit"),
                "expected 'cannot modify limit' in stderr, got: ${r.stderr}",
            )
        }

    @Test
    fun dashA_includesNproc() =
        runTest {
            val r = out("ulimit -a")
            assertTrue(r.contains("max user processes"), "expected nproc row, got:\n$r")
            assertTrue(r.contains("-u"), "expected -u flag in row, got:\n$r")
        }

    @Test
    fun invalidNumber() =
        runTest {
            val r = result("ulimit -u foo")
            assertTrue(r.exitCode != 0)
            assertTrue(r.stderr.contains("invalid number"), "got: ${r.stderr}")
        }

    @Test
    fun unknownOption() =
        runTest {
            val r = result("ulimit -Z")
            assertEquals(2, r.exitCode)
            assertTrue(r.stderr.contains("invalid option"), "got: ${r.stderr}")
        }

    @Test
    fun nofile_roundTripsThroughRlimits() =
        runTest {
            // `ulimit -n 200; ulimit -n` round-trips through the rlimits
            // map. Enforcement is verified in [nofile_redirAllocationRefused]
            // and [nofile_redirSucceedsBelowLimit].
            assertEquals("200\n", out("ulimit -n 200; ulimit -n"))
        }

    @Test
    fun nofile_redirAllocationRefused() =
        runTest {
            // POSIX RLIMIT_NOFILE: fd must satisfy `fd < soft`. The anon-fd
            // allocator starts at fd 10, so a soft limit ≤ 10 makes every
            // `{NAME}>file` allocation fail. The script's `${v-unset}`
            // observer prints "unset" to prove the assignment never landed
            // — bash/vredir6.sub depends on this contract.
            val r =
                result(
                    """
                    unset v
                    ulimit -n 6
                    exec {v}</dev/null
                    echo ${'$'}{v-unset}
                    """.trimIndent(),
                )
            assertEquals("unset\n", r.stdout, "stderr=${r.stderr}")
            assertTrue(
                r.stderr.contains("Too many open files"),
                "expected NOFILE diagnostic, got: ${r.stderr}",
            )
        }

    @Test
    fun nofile_redirSucceedsBelowLimit() =
        runTest {
            // With NOFILE high enough to admit fd 10, the allocator
            // returns a valid fd and the variable is assigned. Default
            // (no ulimit -n) is "unlimited" — confirms the unset NOFILE
            // path doesn't accidentally enforce.
            val r = result("exec {v}</dev/null; echo \"v=\$v\"")
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertTrue(
                r.stdout.matches(Regex("v=\\d+\n")),
                "expected v=<digits>, got: ${r.stdout}",
            )
        }

    @Test
    fun nofile_procsubReclaimedAcrossLoop() =
        runTest {
            // Procsub fds are reclaimed at the end of each enclosing
            // command, so a loop body that allocates one each iteration
            // can run far more times than NOFILE allows. Without
            // reclamation this would refuse after ~6 iterations under
            // NOFILE=20; with reclamation we get a clean 50 lines out.
            val r =
                result(
                    """
                    ulimit -n 20
                    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40 41 42 43 44 45 46 47 48 49 50
                    do
                      read -r v < <(echo "got=${'$'}i")
                      echo "${'$'}v"
                    done
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(50, lines.size, "expected 50 lines, got: ${r.stdout}")
            assertEquals("got=1", lines.first())
            assertEquals("got=50", lines.last())
            assertTrue(
                !r.stderr.contains("Too many open files"),
                "did not expect NOFILE diagnostic, got: ${r.stderr}",
            )
        }

    @Test
    fun nofile_functionScopeReclamation() =
        runTest {
            // Procsubs assigned to variables inside a function persist
            // across the function's statements (so `cat $A` after
            // `A=<(echo ...)` works), but get reclaimed at function
            // return. Without that cleanup, repeated calls to a function
            // that does an assignment-procsub would accumulate fds and
            // hit NOFILE.
            val r =
                result(
                    """
                    ulimit -n 20
                    f() {
                        A=<(echo hi)
                        read v < ${'$'}A
                        echo "${'$'}v"
                    }
                    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30
                    do
                      f
                    done
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            val lines = r.stdout.trim().lines()
            assertEquals(30, lines.size, "expected 30 hi lines, got: ${r.stdout}")
            assertTrue(lines.all { it == "hi" }, "expected all lines to be 'hi', got: ${r.stdout}")
            assertTrue(
                !r.stderr.contains("Too many open files"),
                "did not expect NOFILE diagnostic, got: ${r.stderr}",
            )
        }

    @Test
    fun nofile_procsubFdSurvivesExecRedir() =
        runTest {
            // `exec 4< <(...)` opens /dev/fd/N (the procsub fd), which
            // dups to fd 4. Reclamation of the procsub fd at the `exec`
            // command's end MUST NOT close the pipe — fd 4 still
            // references it via the OFD refcount, and a later
            // `read v <&4` should still produce data.
            val r =
                result(
                    """
                    exec 4< <(echo hello)
                    read v <&4
                    echo "v=${'$'}v"
                    """.trimIndent(),
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("v=hello\n", r.stdout)
        }

    @Test
    fun nofile_softExceededButHardOk() =
        runTest {
            // Soft is what the allocator gates on (POSIX/bash semantics).
            // Raise hard high but keep soft at 6 — allocation still fails.
            val r =
                result(
                    """
                    ulimit -H -n 100000
                    ulimit -S -n 6
                    unset v
                    exec {v}</dev/null
                    echo ${'$'}{v-unset}
                    """.trimIndent(),
                )
            assertEquals("unset\n", r.stdout, "stderr=${r.stderr}")
        }

    @Test
    fun bareUlimit_readsFsize() =
        runTest {
            // Bash convention: bare `ulimit` (no flag) reports file size (-f).
            // No default seeded → unlimited.
            assertEquals("unlimited\n", out("ulimit"))
        }

    @Test
    fun clusteredFlags() =
        runTest {
            // `-Hu 100` means -H -u 100 — set hard limit on NPROC to 100.
            // Then read hard: 100. Read soft: still defaults to old 4096? No —
            // bash semantics: `-H -u N` sets hard only; soft stays as it was.
            // Init seeded soft=4096, hard=MAX. After `-Hu 100`, hard=100,
            // but soft=4096 > 100 → invalid. So this should fail.
            val r = result("ulimit -Hu 100")
            assertTrue(r.exitCode != 0, "should refuse soft > hard")
            assertTrue(r.stderr.contains("Invalid argument") || r.stderr.contains("cannot modify"))
        }
}
