package com.accucodeai.kash

import com.accucodeai.kash.app.standardRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end proof that GNU sed's `s/.../.../e` flag works through the
 * full kash pipeline. Sed's engine consumes `ctx.shellRunner` — wired in
 * `Interpreter.runResolvedSpec` — to parse and run the post-substitution
 * pattern space as a kash sub-shell, and the captured stdout replaces the
 * pattern. Without the ShellRunner/UtilityRunner plumbing in `:api`
 * + `:core`, this would fail with `sed: 'e' flag requires interpreter
 * context\n`.
 */
class SedExecIntegrationTest {
    @Test fun substituteThenExec_replacesPatternWithCommandOutput() {
        runTest {
            val r =
                Kash(registry = standardRegistry()).exec(
                    "echo 'xyz' | sed 's/xyz/echo HELLO/e'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("HELLO\n", r.stdout)
        }
    }

    @Test fun substituteThenExec_passesPatternSpaceThroughShell() {
        runTest {
            // Substitution turns the matching line into `printf %s ok`; sed
            // execs that, captures `ok`, replaces pattern space.
            val r =
                Kash(registry = standardRegistry()).exec(
                    """echo 'replace-me' | sed 's/replace-me/printf %s ok/e'""",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("ok\n", r.stdout)
        }
    }

    @Test fun substituteThenExec_skipsWhenPatternDoesNotMatch() {
        runTest {
            // No match → `e` flag does not fire → pattern space is unchanged.
            val r =
                Kash(registry = standardRegistry()).exec(
                    "echo 'unchanged' | sed 's/zzz/echo NO/e'",
                )
            assertEquals(0, r.exitCode, "stderr=${r.stderr}")
            assertEquals("unchanged\n", r.stdout)
        }
    }
}
