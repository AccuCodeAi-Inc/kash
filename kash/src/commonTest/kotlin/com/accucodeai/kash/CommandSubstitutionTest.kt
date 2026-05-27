package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandSubstitutionTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    @Test fun dollarParenSimple() =
        runTest {
            assertEquals("x\n", out("echo $(echo x)"))
        }

    @Test fun backtickSimple() =
        runTest {
            assertEquals("x\n", out("echo `echo x`"))
        }

    @Test fun nestedDollarParen() =
        runTest {
            assertEquals("x\n", out("echo $(echo $(echo x))"))
        }

    @Test fun trailingNewlinesTrimmed() =
        runTest {
            // `echo` adds a newline; substitution strips it.
            assertEquals("'a'\n", out($$"v=$(echo a)\necho \"'$v'\""))
        }

    @Test fun multipleTrailingNewlinesTrimmed() =
        runTest {
            val r = Kash().exec($$"v=$(printf 'a\\n\\n\\n')\necho \"'$v'\"")
            assertEquals("'a'\n", r.stdout)
        }

    @Test fun assignmentCapturesEmpty() =
        runTest {
            val r = Kash().exec($$"v=$(true)\necho \"'$v'\"")
            assertEquals("''\n", r.stdout)
        }

    @Test fun unquotedSubstWordSplits() =
        runTest {
            // for-loop iterates one item per split field — verifies word splitting on substitution result.
            assertEquals("a\nb\nc\n", out($$"for x in $(echo a b c); do echo $x; done"))
        }

    @Test fun quotedSubstNoSplit() =
        runTest {
            // Quoted substitution → single field; for-loop runs once with the joined text.
            assertEquals("a b c\n", out($$"for x in \"$(echo a b c)\"; do echo $x; done"))
        }

    @Test fun substitutionInsideAssignment() =
        runTest {
            assertEquals("hello\n", out($$"v=$(echo hello)\necho $v"))
        }

    @Test fun backtickInsideDoubleQuotes() =
        runTest {
            assertEquals("[hello]\n", out("echo \"[`echo hello`]\""))
        }

    // -------- bash-5.2 ${ cmd; } no-fork command substitution --------

    @Test fun braceCmdSubSimple() =
        runTest {
            assertEquals("x\n", out($$"echo ${ echo x; }"))
        }

    @Test fun braceCmdSubMultiline() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
                echo ${
                  printf '%s\n' aa bb cc dd
                }
                    """.trimIndent(),
                )
            assertEquals("aa bb cc dd\n", r.stdout)
        }

    @Test fun braceCmdSubAdjacentText() =
        runTest {
            assertEquals("AAaaBB\n", out($$"echo AA${ printf '%s' aa; }BB"))
        }

    @Test fun braceCmdSubNestedBraces() =
        runTest {
            // Nested `{` `}` must not terminate early — counted via depth.
            assertEquals("inner\n", out($$"echo ${ { echo inner; }; }"))
        }

    @Test fun caseWithSlashStarPattern() =
        runTest {
            // Repro: `case ... in /*) ...` is a normal bash pattern. Previously failed
            // when inside $(...) because the recursive parse hit this path.
            assertEquals("abs\n", out($$"echo $(case $HOME in /*) echo abs ;; esac)"))
        }

    @Test fun caseWithSlashStarPatternNoSubsub() =
        runTest {
            assertEquals("abs\n", out($$"HOME=/x\ncase $HOME in /*) echo abs ;; esac"))
        }

    @Test fun commentInsideDollarParenSkipsContent() =
        runTest {
            // `#` starts a shell comment inside $(...) — previously the `"` in the comment
            // tripped the unterminated-string detector.
            val r =
                Kash().exec(
                    $$"""
                v=$(
                  echo yes
                  # a comment with " ' \
                )
                echo $v
                    """.trimIndent(),
                )
            assertEquals("yes\n", r.stdout)
        }

    @Test fun dollarParenFollowedByLiteralQuoteSpansLines() =
        runTest {
            // `"..."` opens on one line and closes on the next, with a
            // newline in between, after a $(...).
            val src = ": \$(echo foo)\"\n\"\necho done"
            val r = Kash().exec(src)
            assertEquals("done\n", r.stdout)
        }

    @Test fun dollarParenWithHereStringInside() =
        runTest {
            // `<<<` (here-string) inside $(...).
            val src = "echo \$(\n\tcat <<< \"x y z\"\n)"
            val r = Kash().exec(src)
            assertEquals("x y z\n", r.stdout)
        }

    @Test fun dollarParenWithHeredocInside() =
        runTest {
            // Heredoc inside $(...) with `)` in body.
            val src = "echo \$(\ncat <<eof\nbody with )\neof\n)"
            val r = Kash().exec(src)
            assertEquals("body with )\n", r.stdout)
        }
}
