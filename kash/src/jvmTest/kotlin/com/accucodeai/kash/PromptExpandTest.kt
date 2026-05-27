package com.accucodeai.kash

import com.accucodeai.kash.api.ExecResult
import com.accucodeai.kash.conformance.VirtualShellClock
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bash `var@P` (PS1 escape decoding) plus full parameter expansion
 * in prompt strings. Reference: bash man page section "PROMPTING".
 *
 * Prior to OVERFITPASS, two corners were narrow:
 *  - `\D{format}` skipped its format entirely (emit empty).
 *  - The final parameter-expansion pass only handled `$NAME` and
 *    bare-name brace form — operators like default-value or
 *    pattern-strip came through literally.
 *
 * Both fixed; tests below pin the new contract.
 */
class PromptExpandTest {
    private suspend fun run(
        script: String,
        env: Map<String, String> = mapOf("PATH" to "/usr/bin"),
    ): ExecResult {
        val fs = InMemoryFs()
        // Pin the wall clock to 2023-11-14T22:13:20Z (epoch 1_700_000_000)
        // so strftime-based tests are stable.
        val scheduler = TestCoroutineScheduler()
        val kash =
            Kash(
                fs = fs,
                initialCwd = "/",
                clock = VirtualShellClock(scheduler),
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

    // ---------- Full parameter expansion in PS1 ----------

    @Test fun defaultValueOperator() =
        runTest {
            // `${X:-default}` was previously emitted literally because the
            // prompt mini-pass only handled `$NAME`/`${NAME}`.
            val r = run("unset X; PS1='[\${X:-default}]'; echo \"\${PS1@P}\"")
            assertEquals("[default]\n", r.stdout)
        }

    @Test fun stripLongestPrefixOperator() =
        runTest {
            val r = run("X=/home/user/proj; PS1='[\${X##*/}]'; echo \"\${PS1@P}\"")
            assertEquals("[proj]\n", r.stdout)
        }

    @Test fun condAltValueOperator() =
        runTest {
            val r = run("Y=v; PS1='\${Y:+(\$Y)}'; echo \"\${PS1@P}\"")
            assertEquals("(v)\n", r.stdout)
            val r2 = run("unset Y; PS1='\${Y:+(\$Y)}'; echo \"\${PS1@P}\"")
            assertEquals("\n", r2.stdout)
        }

    // ---------- strftime escape \D{format} ----------

    // VirtualShellClock pins wall epoch to 2023-11-14T22:13:20Z = 1_700_000_000s.
    // Tests check year, month, day, and HH:MM:SS forms.

    @Test fun strftimeYMD() =
        runTest {
            val r = run("PS1='\\D{%Y%m%d}'; echo \"\${PS1@P}\"")
            // Local timezone may shift the date; check year is present
            // and the format is 8 digits.
            val out = r.stdout.trim()
            assertTrue(
                out.length == 8 && out.all { it.isDigit() },
                "expected 8-digit YMD; got: `$out`",
            )
            assertTrue(out.startsWith("2023"), "expected 2023; got: `$out`")
        }

    @Test fun strftimeHHMM() =
        runTest {
            val r = run("PS1='\\D{%H:%M:%S}'; echo \"\${PS1@P}\"")
            val out = r.stdout.trim()
            // HH:MM:SS shape (zero-padded). Hour depends on local TZ.
            assertTrue(
                out.matches(Regex("\\d{2}:\\d{2}:\\d{2}")),
                "expected HH:MM:SS shape; got: `$out`",
            )
        }

    @Test fun strftimeEmptyFormat() =
        runTest {
            // Bash default: "%a %b %e %H:%M:%S".
            val r = run("PS1='\\D{}'; echo \"\${PS1@P}\"")
            val out = r.stdout.trim()
            // Default format produces something like "Tue Nov 14 22:13:20".
            // Just verify it contains a recognizable month name and
            // HH:MM:SS structure.
            assertTrue(
                Regex("(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)").containsMatchIn(out),
                "expected a month name; got: `$out`",
            )
            assertTrue(
                Regex("\\d{2}:\\d{2}:\\d{2}").containsMatchIn(out),
                "expected HH:MM:SS; got: `$out`",
            )
        }

    @Test fun strftimePercentLiteral() =
        runTest {
            val r = run("PS1='\\D{%%}'; echo \"\${PS1@P}\"")
            assertEquals("%\n", r.stdout)
        }

    @Test fun strftimeUnknownCodeLiteral() =
        runTest {
            // Unknown codes pass through as `%X` literal (matches libc).
            val r = run("PS1='\\D{%Q}'; echo \"\${PS1@P}\"")
            assertEquals("%Q\n", r.stdout)
        }

    // ---------- \t now means time (PS1), not tab ----------

    @Test fun timeEscapeIsTime() =
        runTest {
            // Pre-fix, `\t` was literal tab. PS1 spec says \t is the
            // current time HH:MM:SS.
            val r = run("PS1='\\t'; echo \"\${PS1@P}\"")
            val out = r.stdout.trim()
            assertTrue(
                out.matches(Regex("\\d{2}:\\d{2}:\\d{2}")),
                "expected HH:MM:SS for \\t; got: `$out`",
            )
        }

    // ---------- Regression: existing escapes still work ----------

    @Test fun userEscape() =
        runTest {
            // `\u` reads $USER.
            val r = run("USER=alice; PS1='\\u'; echo \"\${PS1@P}\"")
            assertEquals("alice\n", r.stdout)
        }

    @Test fun newlineEscapeStillWorks() =
        runTest {
            val r = run("PS1='a\\nb'; echo \"\${PS1@P}\"")
            assertEquals("a\nb\n", r.stdout)
        }
}
