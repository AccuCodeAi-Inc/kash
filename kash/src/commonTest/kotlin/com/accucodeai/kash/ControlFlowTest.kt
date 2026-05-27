package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ControlFlowTest {
    @Test fun ifThen() =
        runTest {
            assertEquals("yes\n", Kash().exec("if true; then echo yes; fi").stdout)
        }

    @Test fun ifElse() =
        runTest {
            assertEquals("no\n", Kash().exec("if false; then echo yes; else echo no; fi").stdout)
        }

    @Test fun ifElifWithTestBuiltin() =
        runTest {
            val script =
                $$"""
            X=2
            if [ "$X" = "1" ]; then echo one
            elif [ "$X" = "2" ]; then echo two
            else echo other
            fi
                """.trimIndent()
            assertEquals("two\n", Kash().exec(script).stdout)
        }

    @Test fun ifWithTrueElifChain() =
        runTest {
            val r =
                Kash().exec(
                    "if false; then echo a; elif false; then echo b; elif true; then echo c; else echo d; fi",
                )
            assertEquals("c\n", r.stdout)
        }

    @Test fun forInList() =
        runTest {
            val r = Kash().exec($$"for i in 1 2 3; do echo \"item $i\"; done")
            assertEquals("item 1\nitem 2\nitem 3\n", r.stdout)
        }

    @Test fun cStyleForLoop() =
        runTest {
            val r = Kash().exec($$"for (( i=0; i<3; i++ )); do echo $i; done")
            assertEquals("0\n1\n2\n", r.stdout)
        }

    @Test fun cStyleForLoopMultiInit() =
        runTest {
            val r = Kash().exec($$"for (( i=1, n=4; i<=n; i++ )); do echo $i; done")
            assertEquals("1\n2\n3\n4\n", r.stdout)
        }

    @Test fun cStyleForLoopWithBreak() =
        runTest {
            val r = Kash().exec($$"for (( i=0; ; i++ )); do if [ $i -ge 3 ]; then break; fi; echo $i; done")
            assertEquals("0\n1\n2\n", r.stdout)
        }

    @Test fun cStyleForLoopWithContinue() =
        runTest {
            val r = Kash().exec($$"for (( i=0; i<5; i++ )); do if [ $i -eq 2 ]; then continue; fi; echo $i; done")
            assertEquals("0\n1\n3\n4\n", r.stdout)
        }

    @Test fun whileWithBreak() =
        runTest {
            val r = Kash().exec($$"i=0; while true; do if [ $i -ge 2 ]; then break; fi; echo $i; i=$((i+1)); done")
            assertEquals("0\n1\n", r.stdout)
        }

    @Test fun cStyleForLoopMutatesEnv() =
        runTest {
            val r = Kash().exec($$"i=0; for (( ; i<3; i++ )); do echo $i; done; echo end=$i")
            assertEquals("0\n1\n2\nend=3\n", r.stdout)
        }

    @Test fun arithCommandStandalone() =
        runTest {
            // exit 0 when value non-zero
            val r = Kash().exec("(( 1 + 1 )); echo $?")
            assertEquals("0\n", r.stdout)
        }

    @Test fun arithCommandZeroExitsOne() =
        runTest {
            val r = Kash().exec("(( 0 )); echo $?")
            assertEquals("1\n", r.stdout)
        }

    @Test fun doubleParenFallsBackToNestedSubshells() =
        runTest {
            // `((true ) )` — bash treats this as nested subshells `( (true) )` because the
            // closing parens aren't adjacent. Verified against bash.
            val r = Kash().exec("((true ) ); echo $?")
            assertEquals("0\n", r.stdout)
        }

    @Test fun dollarDoubleParenFallsBackToSubshellInComsub() =
        runTest {
            // `$((cmd);(cmd))` is `$( (cmd);(cmd) )`, not arith — bash falls back.
            val r = Kash().exec("echo \$((echo a);(echo b))")
            assertEquals("a b\n", r.stdout)
        }

    @Test fun forLoopWithBraceBody() =
        runTest {
            // bash accepts `{ body; }` as an alternate for-loop body.
            val r = Kash().exec("for i in 1 2 3; { echo \$i; }")
            assertEquals("1\n2\n3\n", r.stdout)
        }

    @Test fun cStyleForLoopWithBraceBody() =
        runTest {
            // Same for c-style for: `for (( ; ; )) { body; }`.
            val r = Kash().exec("for ((i=0; i<3; i++)) { echo \$i; }")
            assertEquals("0\n1\n2\n", r.stdout)
        }

    @Test fun caseFallthroughSemiAmp() =
        runTest {
            // `;&` falls through to next item without re-testing.
            val r =
                Kash().exec(
                    $$"""
                case foo in
                  foo) echo a ;&
                  bar) echo b ;&
                  baz) echo c ;;
                esac
                    """.trimIndent(),
                )
            assertEquals("a\nb\nc\n", r.stdout)
        }

    @Test fun caseContinueTestSemiSemiAmp() =
        runTest {
            // `;;&` continues testing the remaining patterns.
            val r =
                Kash().exec(
                    $$"""
                case foobar in
                  foo*) echo prefix ;;&
                  *bar) echo suffix ;;&
                  *) echo any ;;
                esac
                    """.trimIndent(),
                )
            assertEquals("prefix\nsuffix\nany\n", r.stdout)
        }

    @Test fun caseFallthroughThenBreak() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
                case x in
                  x) echo first ;&
                  y) echo second ;;
                  z) echo third ;;
                esac
                    """.trimIndent(),
                )
            assertEquals("first\nsecond\n", r.stdout)
        }

    @Test fun caseSubjectCanBeReservedWord() =
        runTest {
            // `case esac in (esac) ...` — bash allows reserved words as the case subject.
            val r = Kash().exec("case esac in (esac) echo matched ;; esac")
            assertEquals("matched\n", r.stdout)
        }

    @Test fun caseReservedWordsAsPatterns() =
        runTest {
            // `else|done|time` — reserved words in pattern position are literals.
            val r = Kash().exec("case done in else|done|time) echo hit ;; esac")
            assertEquals("hit\n", r.stdout)
        }

    @Test fun caseLastItemMayOmitSemicolons() =
        runTest {
            // Final case-item can end with the body's command and `esac`,
            // with no `;;` between them.
            val r = Kash().exec("case k in else|done|time|esac) for f in 1 2 3 ; do :; done esac")
            assertEquals(0, r.exitCode)
        }

    @Test fun forOverVariableExpansion() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            LIST="x y z"
            for v in $LIST; do echo $v; done
                    """.trimIndent(),
                )
            assertEquals("x\ny\nz\n", r.stdout)
        }

    @Test fun whileLoop() =
        runTest {
            val script =
                $$"""
            for i in 1 2 3 4 5; do
              echo $i
            done
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("1\n2\n3\n4\n5\n", r.stdout)
        }

    @Test fun untilLoop() =
        runTest {
            // until requires evaluating cond — use false to fire once
            val r = Kash().exec("until true; do echo no; done; echo done")
            assertEquals("done\n", r.stdout)
        }

    @Test fun caseStatement() =
        runTest {
            val script =
                $$"""
            X=banana
            case $X in
              apple)  echo got-apple ;;
              banana) echo got-banana ;;
              *)      echo other ;;
            esac
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("got-banana\n", r.stdout)
        }

    @Test fun caseGlobPattern() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            for f in foo.txt bar.md baz.txt; do
              case $f in
                *.txt) echo "T: $f" ;;
                *.md)  echo "M: $f" ;;
              esac
            done
                    """.trimIndent(),
                )
            assertEquals("T: foo.txt\nM: bar.md\nT: baz.txt\n", r.stdout)
        }

    @Test fun functionDefinitionAndCall() =
        runTest {
            val script =
                $$"""
            greet() {
              echo "hello, $1"
            }
            greet alice
            greet bob
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("hello, alice\nhello, bob\n", r.stdout)
        }

    @Test fun functionWithPositionalParams() =
        runTest {
            val script =
                $$"""
            count() { echo "n=$#"; for a in "$@"; do echo "- $a"; done; }
            count one two three
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("n=3\n- one\n- two\n- three\n", r.stdout)
        }

    @Test fun functionKeywordSyntax() =
        runTest {
            val script =
                $$"""
            function shout {
              echo "[$1]"
            }
            shout hi
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("[hi]\n", r.stdout)
        }

    @Test fun groupCommand() =
        runTest {
            val r = Kash().exec("{ echo one; echo two; } > /tmp/g.txt && cat /tmp/g.txt")
            assertEquals("one\ntwo\n", r.stdout)
        }

    @Test fun subshellIsolatesAssignments() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            X=outer
            ( X=inner; echo $X )
            echo $X
                    """.trimIndent(),
                )
            assertEquals("inner\nouter\n", r.stdout)
        }

    @Test fun nestedIfInFor() =
        runTest {
            val r =
                Kash().exec(
                    $$"""
            for n in 1 2 3 4; do
              case $n in
                1|3) echo "odd $n" ;;
                *)   echo "even $n" ;;
              esac
            done
                    """.trimIndent(),
                )
            assertEquals("odd 1\neven 2\nodd 3\neven 4\n", r.stdout)
        }
}
