package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `preexpandArithmetic`'s `$(...)` scanner must be case-keyword aware
 * — same rule as OVERFITPASS A1's `Lexer.readProcessSubstitution` /
 * `readCommandSubstitution` work, applied to the sibling preexpand
 * scanner used by arithmetic-context cmdsub expansion.
 *
 * Reference: POSIX §2.6.3 (Command Substitution) and bash's parser:
 * the command-substitution body parses as a full command list, so
 * `case x in p) ... esac` inside `$(...)` consumes the `)` of the
 * pattern terminator without ending the cmdsub. Naive paren-counting
 * (kash pre-fix) terminates at the first `)`, leaving `esac)` as
 * trailing text that the arith evaluator then chokes on.
 *
 * The failing input shape comes from arith-for.tests:145
 * `for (( $(case x in x) esac);; )); do break; done` — pre-fix kash
 * emitted a spurious "(( :" diagnostic for the `esac)` residue, which
 * was masked by an `<ARITH_FOR_TAIL>` normalize rule. With this fix,
 * the rule becomes redundant.
 */
class PreexpandCaseAwarenessTest {
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

    @Test fun cmdsubInArithInitWithCaseBody() =
        runTest {
            // Exact shape from bash's arith-for.tests:145. Empty
            // case-action between pattern `)` and `esac` is legal
            // (bash treats it as no commands for the clause).
            val r =
                run(
                    "for (( \$(case x in x) esac);; )); do echo body; break; done; echo after",
                )
            assertTrue(
                r.stderr.isEmpty(),
                "expected no preexpand diagnostic; got stderr=`${r.stderr}`",
            )
            assertEquals("body\nafter\n", r.stdout)
        }

    @Test fun cmdsubInArithExprWithCaseBody() =
        runTest {
            // Same shape but the cmdsub produces an integer the arith
            // expression then uses.
            val r =
                run(
                    "x=\$(( \$(case 1 in 1) echo 5;; esac) + 10 )); echo \$x",
                )
            assertTrue(
                r.stderr.isEmpty(),
                "expected no diagnostic; got stderr=`${r.stderr}`",
            )
            assertEquals("15\n", r.stdout)
        }

    @Test fun cmdsubInArithNestedCase() =
        runTest {
            // Nested case inside cmdsub inside arith. The outer `)`
            // terminating the outer case pattern, plus the inner case,
            // must not confuse the boundary finder.
            val r =
                run(
                    "x=\$(( \$(case a in a) case b in b) echo 7;; esac;; esac) + 1 )); echo \$x",
                )
            assertTrue(
                r.stderr.isEmpty(),
                "expected no diagnostic; got stderr=`${r.stderr}`",
            )
            assertEquals("8\n", r.stdout)
        }
}
