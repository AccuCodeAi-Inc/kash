package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `set -<flag>` short-flag coverage. POSIX §2.14.1 documents these;
 * bash man page section "set" mirrors the same. Each test runs a small
 * script that demonstrates the flag's observable effect.
 *
 * Prior to OVERFITPASS §P1, `-a/-f/-v/-n` were silently no-op'd by both
 * [IntrinsicsDeclare]'s runtime `set` handler and
 * [com.accucodeai.kash.tools.kash.KashShellCommand]'s CLI
 * `applyShellOpts`. These tests are the gate against re-introducing
 * that pattern.
 */
class SetFlagsTest {
    private suspend fun run(
        script: String,
        env: Map<String, String> = mapOf("PATH" to "/usr/bin"),
    ): ExecResult {
        val fs = InMemoryFs()
        val kash =
            Kash(
                fs = fs,
                initialCwd = "/",
                parentContext = kotlin.coroutines.coroutineContext,
            )
        return kash.exec(
            script,
            ExecOptions(
                env = env,
                cwd = "/",
                replaceEnv = true,
                mergeStderr = false,
            ),
        )
    }

    // ---------- -a (allexport) ----------

    @Test fun allexportRuntime() =
        runTest {
            // After `set -a`, the assigned X is exported and a child shell
            // started via `kash -c` inherits it.
            val r = run("set -a; X=hello; kash -c 'echo \$X'")
            assertEquals("hello\n", r.stdout)
        }

    @Test fun allexportToggleOff() =
        runTest {
            // `set +a` after the assignment doesn't retroactively un-export
            // X (POSIX: -a affects FUTURE assignments). Y assigned after
            // `+a` is NOT exported.
            val r = run("set -a; X=1; set +a; Y=2; kash -c 'echo \"\$X|\${Y:-unset}\"'")
            assertEquals("1|unset\n", r.stdout)
        }

    // ---------- -f (noglob) ----------

    @Test fun noglobRuntime() =
        runTest {
            // `set -f` disables pathname expansion entirely. With matching
            // files present, `echo *.kt` would otherwise list them; with
            // -f, the literal `*.kt` is emitted.
            val r =
                run(
                    """
                    mkdir t
                    cd t
                    echo > a.kt
                    echo > b.kt
                    set -f
                    echo *.kt
                    """.trimIndent(),
                )
            assertEquals("*.kt\n", r.stdout)
        }

    @Test fun noglobToggleOff() =
        runTest {
            // After `set +f`, globbing resumes.
            val r =
                run(
                    """
                    mkdir t
                    cd t
                    echo > a.kt
                    echo > b.kt
                    set -f
                    set +f
                    echo *.kt
                    """.trimIndent(),
                )
            assertEquals("a.kt b.kt\n", r.stdout)
        }

    // ---------- -v (verbose) ----------

    @Test fun verboseRuntimeEchoesToStderr() =
        runTest {
            // After `set -v`, each subsequent statement is echoed to
            // stderr immediately before execution.
            val r = run("set -v\necho hi")
            assertEquals("hi\n", r.stdout)
            assertTrue(
                r.stderr.contains("echo hi"),
                "expected stderr to echo `echo hi` after set -v; got: `${r.stderr}`",
            )
        }

    // ---------- -n (noexec) ----------

    @Test fun noexecSkipsExecution() =
        runTest {
            // After `set -n`, statements are parsed but not executed.
            // The `echo` would output `dont-run` if it ran.
            val r = run("set -n\necho dont-run")
            assertEquals("", r.stdout)
        }

    // ---------- stub flags ----------

    @Test fun unsupportedFlagWarns() =
        runTest {
            // `-b -k -p -t` (job-control notify, command-style-assignment,
            // priv mode, exit-after-one-cmd) emit a one-line diagnostic
            // so scripts depending on them aren't silently mis-behaving.
            // `-h` and `-H` are silent no-ops to match bash (hashall is
            // always-on; histexpand is non-interactive no-op).
            val r = run("set -k")
            assertTrue(
                r.stderr.contains("unsupported"),
                "expected `unsupported` warning for set -k; got stderr=`${r.stderr}`",
            )
        }

    @Test fun silentNoOpFlagsHandH() =
        runTest {
            // `-h`/`-H` must accept silently. nquote1.sub does `set -H`
            // and the test corpus expects no diagnostic.
            val r = run("set -h; set -H; echo ok")
            assertEquals("ok\n", r.stdout)
            assertEquals("", r.stderr)
        }

    // ---------- CLI path ----------

    @Test fun allexportViaCli() =
        runTest {
            // `kash -a -c '...'` reaches applyShellOpts (the CLI path,
            // distinct from the runtime `set -a`). Same observable
            // semantics must hold.
            val r = run("kash -a -c 'X=hello; kash -c \"echo \\\$X\"'")
            assertEquals("hello\n", r.stdout)
        }

    @Test fun noglobViaCli() =
        runTest {
            val r =
                run(
                    """
                    mkdir t
                    cd t
                    echo > a.kt
                    kash -f -c 'echo *.kt'
                    """.trimIndent(),
                )
            assertEquals("*.kt\n", r.stdout)
        }
}
