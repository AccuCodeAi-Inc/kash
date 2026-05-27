package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `compgen` programmable-completion + IFS-aware -W splitting.
 * Reference: bash man page section "Programmable Completion" and
 * the `compgen` / `complete` builtins.
 *
 * Prior to OVERFITPASS, three corners were narrow:
 *  - `-F func` silently returned no matches (just had a comment about
 *    being a "Phase-3-polish item").
 *  - `-C cmd` silently dropped.
 *  - `-W` split on whitespace regex, not on $IFS.
 */
class CompgenTest {
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

    // ---------- -W IFS splitting ----------

    @Test fun wordlistDefaultIfs() =
        runTest {
            // Default IFS is space/tab/newline. compgen -W treats them
            // all as separators.
            val r = run("compgen -W 'a b c' \"\"")
            assertEquals("a\nb\nc\n", r.stdout)
        }

    @Test fun wordlistCustomIfs() =
        runTest {
            // With IFS=: , the wordlist 'a:b:c' yields three fields.
            val r = run("IFS=: compgen -W 'a:b:c' \"\"")
            assertEquals("a\nb\nc\n", r.stdout)
        }

    @Test fun wordlistTabAndSpace() =
        runTest {
            // Default IFS splits on both tab and space.
            val r = run("compgen -W \$'a\\tb c' \"\"")
            assertEquals("a\nb\nc\n", r.stdout)
        }

    // ---------- -F func ----------

    @Test fun functionPopulatesComprely() =
        runTest {
            // -F func: callback writes to COMPREPLY array. Operand `fo`
            // filters to entries starting with `fo`.
            val script =
                """
                _completer() { COMPREPLY=(foo foobar); }
                compgen -F _completer fo
                """.trimIndent()
            val r = run(script)
            assertEquals("foo\nfoobar\n", r.stdout)
        }

    @Test fun functionFiltersByOperandPrefix() =
        runTest {
            // compgen filters candidates by the operand prefix even
            // when they come from -F.
            val script =
                """
                _all() { COMPREPLY=(apple banana cherry); }
                compgen -F _all b
                """.trimIndent()
            val r = run(script)
            assertEquals("banana\n", r.stdout)
        }

    @Test fun functionReceivesCurWord() =
        runTest {
            // -F passes the operand as `$2` (the current word being
            // completed). `$1` (command) is empty for direct compgen
            // invocations without readline context.
            val script =
                """
                _dump() { echo "2=${'$'}2"; COMPREPLY=(); }
                compgen -F _dump curword
                """.trimIndent()
            val r = run(script)
            assertTrue(
                r.stdout.contains("2=curword"),
                "expected `2=curword` in stdout; got: `${r.stdout}`",
            )
        }

    @Test fun functionNotFoundDiagnoses() =
        runTest {
            // -F with an undefined function must emit a diagnostic and
            // return non-zero — NOT silently emit nothing.
            val r = run("compgen -F nonexistent_fn cmd")
            assertTrue(
                r.stderr.contains("not found") || r.stderr.contains("nonexistent_fn"),
                "expected `function not found` diagnostic; got stderr=`${r.stderr}`",
            )
        }

    // ---------- -C cmd ----------

    @Test fun commandStdoutBecomesCompletions() =
        runTest {
            // -C cmd: each line of cmd's stdout is a candidate.
            // Empty operand → no prefix filter.
            val r = run("compgen -C 'echo apple; echo banana' \"\"")
            assertEquals("apple\nbanana\n", r.stdout)
        }
}
