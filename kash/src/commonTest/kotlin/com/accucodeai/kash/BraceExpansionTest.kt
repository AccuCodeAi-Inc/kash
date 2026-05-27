package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brace expansion (bash §3.5.1): a textual pre-pass that runs before tilde,
 * parameter, arithmetic, command, split, and glob expansion. Spec-aligned
 * cases — the bash conformance corpus `external/bash/tests/braces.tests`
 * provides the deeper coverage; these tests pin the surface behaviors that
 * are easy to miss and rely on per-stage interactions.
 */
class BraceExpansionTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    @Test fun listForm() = runTest { assertEquals("a b c\n", out("echo {a,b,c}")) }

    @Test fun listFormWithPrefix() = runTest { assertEquals("prexpost preypost\n", out("echo pre{x,y}post")) }

    @Test fun listFormEmptyElement() = runTest { assertEquals("a  b\n", out("echo {a,,b}")) }

    @Test fun listFormNested() = runTest { assertEquals("a b c d\n", out("echo {a,{b,c},d}")) }

    @Test fun listFormCartesian() = runTest { assertEquals("a1 a2 b1 b2\n", out("echo {a,b}{1,2}")) }

    @Test fun seqNumeric() = runTest { assertEquals("1 2 3 4 5\n", out("echo {1..5}")) }

    @Test fun seqReverse() = runTest { assertEquals("5 4 3 2 1\n", out("echo {5..1}")) }

    @Test fun seqStep() = runTest { assertEquals("1 3 5 7 9\n", out("echo {1..10..2}")) }

    @Test fun seqZeroPad() = runTest { assertEquals("01 02 03 04 05\n", out("echo {01..05}")) }

    @Test fun seqAlpha() = runTest { assertEquals("a b c d e\n", out("echo {a..e}")) }

    @Test fun seqAlphaStep() = runTest { assertEquals("a c e g\n", out("echo {a..g..2}")) }

    @Test fun literalNoComma() = runTest { assertEquals("{abc}\n", out("echo {abc}")) }

    @Test fun literalEmpty() = runTest { assertEquals("{}\n", out("echo {}")) }

    @Test fun literalMixedTypeSeq() = runTest { assertEquals("{1..a}\n", out("echo {1..a}")) }

    @Test fun literalUnbalanced() = runTest { assertEquals("{a,b\n", out("echo {a,b")) }

    @Test fun quotedDoubleNoExpand() = runTest { assertEquals("{a,b}\n", out("echo \"{a,b}\"")) }

    @Test fun quotedSingleNoExpand() = runTest { assertEquals("{a,b}\n", out("echo '{a,b}'")) }

    @Test fun escapedBracesNoExpand() = runTest { assertEquals("{a,b}\n", out("echo \\{a,b\\}")) }

    @Test fun paramExprStaysParamExpansion() =
        // ${a,} = lowercase-first-char modifier (not brace expansion).
        runTest { assertEquals("hI\n", out("a=HI\necho \${a,}")) }

    @Test fun runsBeforeParamExpansion() = runTest { assertEquals("1 2\n", out("a=1\nb=2\necho {\$a,\$b}")) }

    @Test fun varValueDoesNotBraceExpand() = runTest { assertEquals("{a,b}\n", out("x='{a,b}'\necho \$x")) }

    @Test fun cmdSubInsideBrace() = runTest { assertEquals("x y\n", out("echo {\$(echo x),\$(echo y)}")) }

    @Test fun dollarParenIsOpaqueToScanner() =
        // The comma inside $(...) must not separate brace elements.
        runTest { assertEquals("a b,c d\n", out("echo {a,\$(echo b,c),d}")) }

    @Test fun disabledByShopt() =
        runTest {
            assertEquals(
                "{a,b,c}\na b c\n",
                out("shopt -u braceexpand\necho {a,b,c}\nshopt -s braceexpand\necho {a,b,c}"),
            )
        }

    @Test fun disabledBySetPlusB() =
        runTest {
            assertEquals(
                "{a,b}\na b\n",
                out("set +B\necho {a,b}\nset -B\necho {a,b}"),
            )
        }

    @Test fun threeWayCartesian() =
        runTest {
            assertEquals("ace acf ade adf bce bcf bde bdf\n", out("echo {a,b}{c,d}{e,f}"))
        }

    @Test fun seqByCartesianSeq() = runTest { assertEquals("1a 1b 2a 2b 3a 3b\n", out("echo {1..3}{a..b}")) }

    @Test fun nestedEmptyOuter() =
        runTest {
            // Outer no-comma groups remain literal; only the innermost expands.
            assertEquals("{{{a}}} {{{b}}}\n", out("echo {{{{a,b}}}}"))
        }

    @Test fun escapedComma() =
        // `\,` is lexed as WordPart.Escaped and is opaque to the brace scanner.
        runTest { assertEquals("a,b c\n", out("echo {a\\,b,c}")) }

    @Test fun escapedCloseBrace() = runTest { assertEquals("a b}c\n", out("echo {a,b\\}c}")) }

    @Test fun quotedCommaInsideGroup() =
        // The comma inside "..." is opaque to the splitter; quotes themselves
        // are stripped later by quote removal.
        runTest { assertEquals("a b,c d\n", out("echo {a,\"b,c\",d}")) }

    @Test fun onlyOpeningEscaped() =
        // \{ is opaque → no open brace recognized → whole token literal.
        runTest { assertEquals("{a,b}\n", out("echo \\{a,b}")) }

    @Test fun bareDollarMergesAcrossBrace() =
        // Brace expansion is textual in bash, so `$var{x,y}` produces `$varx`
        // `$vary` (not `$var` + literal `x`/`y`). Requires the bare-`$NAME`
        // form to merge with trailing ident chars after brace expansion.
        runTest {
            assertEquals(
                "vx vy\n",
                out("varx=vx\nvary=vy\necho \$varx \$vary\n#"),
            )
            assertEquals(
                "vx vy\n",
                out("varx=vx\nvary=vy\nvar=baz\necho \$var{x,y}"),
            )
        }

    @Test fun bracedDollarStaysDelimited() =
        // `${var}{x,y}` — explicit braces delimit the name, so output is
        // `bazx bazy`, not `vx vy`.
        runTest {
            assertEquals(
                "bazx bazy\n",
                out("var=baz\nvarx=vx\nvary=vy\necho \${var}{x,y}"),
            )
        }

    @Test fun seqAlphaSkipsQuoteChars() =
        // POSIX §2.6.7 quote removal would strip `\` / `'` / `"` from
        // expansion results; bash `{a..A}` accordingly emits an empty word at
        // 0x5C (`\`) between `]` and `[`. We reproduce that observable behavior.
        runTest {
            val r = out("echo {a..A}")
            // No literal backslash in the output.
            assertEquals(false, r.contains('\\'), "expected no backslash, got: $r")
            // Two consecutive spaces where the `\` slot is.
            assertEquals(true, r.contains("]  ["), "expected `]  [` (two spaces), got: $r")
        }

    @Test fun overflowCapDoesNotOom() =
        runTest {
            // `{1..10000000}` would emit 10M words past the cap; the implementation
            // must return the original literal rather than OOM. We assert exit 0
            // and a non-empty stdout (the literal echo).
            val r = Kash().exec("echo {1..10000000}")
            assertEquals(0, r.exitCode)
            assertTrue(r.stdout.isNotEmpty(), "expected fallback output, got empty")
        }
}
