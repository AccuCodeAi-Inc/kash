package com.accucodeai.kash

import com.accucodeai.kash.conformance.RechoCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Focused probes for the next-easiest new-exp.tests diff lines.
 * Each test isolates one bash semantics that the new-exp corpus expects.
 * Used as a faster feedback loop than running the full conformance run.
 */
class NewExpProbeTest {
    /**
     * `"${HOME-'}'}"` — single-quoted `}` inside the default-value operand
     * of `${var-WORD}` must not close the surrounding `${...}`. Bash:
     * HOME set → emits HOME's value. The single quotes wrap the literal `}`.
     */
    @Test fun singleQuoteCloseBraceInDefault() =
        runTest {
            // Use recho (kash builtin matching bash's test-corpus helper).
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "HOME=/usr/homes/chet\nrecho \"\${HOME-'}'}\"\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = </usr/homes/chet>\n", r.stdout)
        }

    @Test fun stripSuffixQuotedStarLiteral() =
        runTest {
            // new-exp.tests:65 — `${P%"*"}` strips the suffix matching
            // LITERAL `*` (because the `*` is double-quoted). With P=`*@*`
            // the result is `*@` (drop the trailing `*`).
            val src = "P=*@*\nrecho \"\${P%\"*\"}\"\n"
            val r = Kash(customCommands = listOf(RechoCommand())).exec(src, ExecOptions(replaceEnv = false))
            assertEquals("argv[1] = <*@>\n", r.stdout)
        }

    @Test fun stripSuffixSingleQuotedStarLiteral() =
        runTest {
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "P=*@*; recho \"\${P%'*'}\"\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = <*@>\n", r.stdout)
        }

    @Test fun substringWithTernaryAndParen() =
        runTest {
            // new-exp.tests:151 — `${string1:(j?1:0):j}` with j=4
            // should compute offset=1 length=4 → `home` from `/home/chet/foo//bar/...`
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    """
                    base=/home/chet/foo//bar
                    string1=${'$'}base/abcabcabc
                    x=1 j=4
                    recho ${'$'}{string1:(j?1:0):j}
                    """.trimIndent(),
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = <home>\n", r.stdout)
        }

    @Test fun substringWithLengthExpr() =
        runTest {
            // new-exp.tests:111 — `${z:${#z}-3:3}` should compute
            // offset = 16-3 = 13, length = 3, yielding `nop` (last 3 chars).
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "z=abcdefghijklmnop; recho \${z:\${#z}-3:3}\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = <nop>\n", r.stdout)
        }

    @Test fun echoEDashCStopsOutput() =
        runTest {
            // bash `echo -e "X\c"` interprets `\c` as "stop output here",
            // suppressing the trailing newline AND any chars after.
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "echo -e \"bar\\c \"; echo foo\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("barfoo\n", r.stdout)
        }

    @Test fun procSubBareCmd() =
        runTest {
            // The easy case from new-exp1.sub:21 — does kash support
            // `cat <(...)` at all?
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "cat <(echo hello)\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("hello\n", r.stdout)
        }

    @Test fun procSubInDefaultValueOperand() =
        runTest {
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "foo=\necho [\${foo:-<(echo a)}]\n",
                    ExecOptions(replaceEnv = false),
                )
            // Diagnostic — what does the expansion actually produce?
            println("STDOUT=[${r.stdout}]")
            println("STDERR=[${r.stderr}]")
            // Bash: stdout should be like `[/dev/fd/63]` (a procsub fd path).
            check(r.stdout.startsWith("[/dev/fd/") || r.stdout.startsWith("[/proc/self/fd/")) {
                "expected procsub fd path, got: ${r.stdout}"
            }
        }

    @Test fun singleQuoteAtEndOfDqDefaultPosixMode() =
        runTest {
            // Posixexp.tests:77 — `recho "${IFS+'}'z}"` AFTER `set -o posix`.
            // In POSIX mode, bash's brace-body capture treats `'`
            // as literal in DQ context; operand = `'` (1 char), the first
            // `}` closes the expansion, leaving `'z}` as literal text.
            // Total: `' + 'z}` = `''z}`.
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "set -o posix\nrecho \"\${IFS+'}'z}\"\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = <''z}>\n", r.stdout)
        }

    @Test fun singleQuoteAtEndOfDqDefaultNonPosix() =
        runTest {
            // Bash default (non-POSIX) — `'X'` is a quote region; operand
            // = `'}'z` (4 chars). IFS set → use alt. In outer DQ, `'X'`
            // preserves both chars literally → expansion = `'}'z`.
            val r =
                Kash(customCommands = listOf(RechoCommand())).exec(
                    "set +o posix\nrecho \"\${IFS+'}'z}\"\n",
                    ExecOptions(replaceEnv = false),
                )
            assertEquals("argv[1] = <'}'z>\n", r.stdout)
        }
}
