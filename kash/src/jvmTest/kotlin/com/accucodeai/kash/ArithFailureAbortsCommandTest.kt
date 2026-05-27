package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bash semantic: a failed `$((expr))` substitution inside a simple
 * command's argv aborts the command after word expansion. The
 * diagnostic prints, but the command itself does NOT run.
 *
 * Reference: bash man page §Arithmetic Expansion ("Errors in
 * evaluation cause a message to be printed and the command's
 * exit status to be non-zero"). For non-special builtin invocations
 * the convention is "command not run, exit 1."
 *
 * Prior to OVERFITPASS, two normalize rules masked kash's residual
 * `0\n` stdout from `echo $((bogus))` (kash defaulted to 0 on arith
 * failure, then ran the echo). The behavior is already correct in
 * kash (see [Interpreter.arithSubstFailedInWordExpansion]); these
 * tests gate against regression.
 */
class ArithFailureAbortsCommandTest {
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
                // Non-`-c` scriptName so the script-file mode applies:
                // a single arith failure doesn't abort the whole unit.
                scriptName = "./test.sh",
            ),
        )
    }

    @Test fun arithSyntaxErrorAbortsEcho() =
        runTest {
            // `1 +` is a syntax error. Echo must NOT run; stderr gets
            // the diagnostic, stdout stays empty.
            val r = run("echo \$((1 +)); echo after")
            assertTrue(
                r.stderr.isNotEmpty(),
                "expected arith error on stderr",
            )
            // Echo aborted → no `0` from the failed substitution, and
            // `after` still runs because we're in script mode (not -c).
            assertEquals("after\n", r.stdout)
        }

    @Test fun arithSyntaxErrorInAssignmentDoesNotEmitZero() =
        runTest {
            // `x=$((bogus syntax))` — kash assigns nothing usable and
            // subsequent echo of $x must not produce a stale `0`.
            val r = run("x=\$((1 +)); echo \"[\$x]\"")
            assertTrue(r.stderr.isNotEmpty(), "expected arith error on stderr")
            // After a failed arith assignment, $x is unset / empty.
            // bash and kash both emit `[]` here.
            assertEquals("[]\n", r.stdout)
        }
}
