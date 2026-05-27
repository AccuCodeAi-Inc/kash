package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ParameterExpansionOpsTest {
    private suspend fun out(script: String): String = Kash().exec(script).stdout

    // -------- defaults / alternates --------

    @Test fun defaultWhenUnset() =
        runTest {
            assertEquals("fallback\n", out($$"echo ${X:-fallback}"))
        }

    @Test fun defaultNotUsedWhenSet() =
        runTest {
            assertEquals("v\n", out($$"X=v\necho ${X:-fallback}"))
        }

    @Test fun defaultUsedForEmptyWithColon() =
        runTest {
            assertEquals("f\n", out($$"X=\necho ${X:-f}"))
        }

    @Test fun defaultNotUsedForEmptyWithoutColon() =
        runTest {
            // ${X-f}: only substitutes when X is unset (empty counts as set).
            assertEquals("\n", out($$"X=\necho ${X-f}"))
        }

    @Test fun alternateWhenSet() =
        runTest {
            assertEquals("yes\n", out($$"X=v\necho ${X:+yes}"))
        }

    @Test fun alternateEmptyWhenUnset() =
        runTest {
            assertEquals("\n", out($$"echo ${X:+yes}"))
        }

    @Test fun assignDefaultStoresValue() =
        runTest {
            val r = Kash().exec($$"echo ${X:=v}\necho \"$X\"")
            assertEquals("v\nv\n", r.stdout)
        }

    // -------- length --------

    @Test fun lengthOfString() =
        runTest {
            assertEquals("5\n", out($$"X=hello\necho ${#X}"))
        }

    @Test fun lengthOfEmpty() =
        runTest {
            assertEquals("0\n", out($$"X=\necho ${#X}"))
        }

    // -------- strip prefix / suffix --------

    @Test fun stripShortPrefix() =
        runTest {
            assertEquals("file.txt.bak\n", out($$"X=foo.file.txt.bak\necho ${X#*.}"))
        }

    @Test fun stripLongPrefix() =
        runTest {
            assertEquals("bak\n", out($$"X=foo.file.txt.bak\necho ${X##*.}"))
        }

    @Test fun stripShortSuffix() =
        runTest {
            assertEquals("foo.file.txt\n", out($$"X=foo.file.txt.bak\necho ${X%.*}"))
        }

    @Test fun stripLongSuffix() =
        runTest {
            assertEquals("foo\n", out($$"X=foo.file.txt.bak\necho ${X%%.*}"))
        }

    // -------- pattern substitution --------

    @Test fun substituteFirst() =
        runTest {
            assertEquals("xbb\n", out($$"X=abb\necho ${X/a/x}"))
        }

    @Test fun substituteAll() =
        runTest {
            assertEquals("xxb\n", out($$"X=aab\necho ${X//a/x}"))
        }

    @Test fun substitutePrefixAnchored() =
        runTest {
            assertEquals("Xab\n", out($$"X=aab\necho ${X/#a/X}"))
        }

    @Test fun substituteSuffixAnchored() =
        runTest {
            assertEquals("baX\n", out($$"X=baa\necho ${X/%a/X}"))
        }

    // -------- case modification --------

    @Test fun upperFirst() =
        runTest {
            assertEquals("Abc\n", out($$"X=abc\necho ${X^}"))
        }

    @Test fun upperAll() =
        runTest {
            assertEquals("ABC\n", out($$"X=abc\necho ${X^^}"))
        }

    @Test fun lowerFirst() =
        runTest {
            assertEquals("aBC\n", out($$"X=ABC\necho ${X,}"))
        }

    @Test fun lowerAll() =
        runTest {
            assertEquals("abc\n", out($$"X=ABC\necho ${X,,}"))
        }

    // -------- substring --------

    @Test fun substringFromOffset() =
        runTest {
            assertEquals("llo\n", out($$"X=hello\necho ${X:2}"))
        }

    @Test fun substringRange() =
        runTest {
            assertEquals("ell\n", out($$"X=hello\necho ${X:1:3}"))
        }

    @Test fun substringNegativeOffset() =
        runTest {
            assertEquals("lo\n", out($$"X=hello\necho ${X: -2}"))
        }

    // -------- array subscripts (scalar-as-1-elem fallback while arrays not stored) --------

    @Test fun subscriptAtOnScalar() =
        runTest {
            assertEquals("hi\n", out($$"X=hi\necho ${X[@]}"))
        }

    @Test fun subscriptStarOnScalar() =
        runTest {
            assertEquals("hi\n", out($$"X=hi\necho ${X[*]}"))
        }

    @Test fun subscriptZeroOnScalar() =
        runTest {
            assertEquals("hi\n", out($$"X=hi\necho ${X[0]}"))
        }

    @Test fun subscriptNonzeroOnScalarIsEmpty() =
        runTest {
            // bash: a scalar at index >0 prints empty -> single newline.
            assertEquals("\n", out($$"X=hi\necho ${X[1]}"))
        }

    @Test fun lengthOfAtSubscriptCountsOneForSetScalar() =
        runTest {
            assertEquals("1\n", out($$"X=hi\necho ${#X[@]}"))
        }

    @Test fun lengthOfAtSubscriptCountsZeroForUnset() =
        runTest {
            assertEquals("0\n", out($$"echo ${#X[@]}"))
        }

    @Test fun lengthOfZeroSubscriptIsScalarLength() =
        runTest {
            assertEquals("5\n", out($$"X=hello\necho ${#X[0]}"))
        }

    @Test fun subscriptDefaultOpStillWorks() =
        runTest {
            assertEquals("fallback\n", out($$"echo ${X[0]:-fallback}"))
        }

    // -------- case toggle (~, ~~) --------

    @Test fun caseToggleFirstChar() =
        runTest {
            // `~` flips just the first character.
            assertEquals("Hello\nWorlD\n", out($$"X=hello\necho ${X~}\nY=worlD\necho ${Y~}"))
        }

    @Test fun caseToggleAllChars() =
        runTest {
            // `~~` flips every character.
            assertEquals("HELLO\nwOrLd\n", out($$"X=hello\necho ${X~~}\nY=WoRlD\necho ${Y~~}"))
        }

    @Test fun caseToggleWithPatternHitsOnlyMatching() =
        runTest {
            // Only `l` and `o` get flipped — `h` and `e` left alone.
            assertEquals("heLLO\n", out($$"X=hello\necho ${X~~[lo]}"))
        }

    // -------- @ transform (Q, E, L, U) --------

    @Test fun atQQuotesPrintable() =
        runTest {
            assertEquals("'hello'\n", out($$"X=hello\necho ${X@Q}"))
        }

    @Test fun atQOnEmpty() =
        runTest {
            assertEquals("''\n", out($$"X=\necho ${X@Q}"))
        }

    @Test fun atLLowercase() =
        runTest {
            assertEquals("hello\n", out($$"X=HELLO\necho ${X@L}"))
        }

    @Test fun atUUppercase() =
        runTest {
            assertEquals("HELLO\n", out($$"X=hello\necho ${X@U}"))
        }

    // -------- ${!var} indirect --------

    @Test fun indirectExpansion() =
        runTest {
            assertEquals("hello\n", out($$"X=Y\nY=hello\necho ${!X}"))
        }

    @Test fun indirectExpansionUnsetIntermediate() =
        runTest {
            // X is unset → indirect resolves to "" → empty.
            assertEquals("\n", out($$"echo ${!X}"))
        }

    @Test fun indirectWithOps() =
        runTest {
            // `${!X//c/x}` — substitute c→x on the value of the variable named by X.
            assertEquals("habbage\n", out($$"X=fruit\nfruit=cabbage\necho ${!X//c/h}"))
        }

    // -------- ${!prefix*} / ${!prefix@} name globbing --------
    //
    // Bash only supports the indirect form (`${!PREFIX_*}` /
    // `${!PREFIX_@}`); the bareword `${PREFIX_*}` is "bad substitution".

    @Test fun nameGlobIndirectStarJoinsNames() =
        runTest {
            assertEquals(
                "HOST_A HOST_B HOST_C\n",
                out($$"HOST_A=1\nHOST_B=2\nHOST_C=3\necho ${!HOST_*}"),
            )
        }

    @Test fun nameGlobIndirectAtSplitsNames() =
        runTest {
            // `${!HOST_@}` unquoted behaves like `${!HOST_*}` after word splitting.
            assertEquals(
                "HOST_A HOST_B HOST_C\n",
                out($$"HOST_A=1\nHOST_B=2\nHOST_C=3\necho ${!HOST_@}"),
            )
        }

    @Test fun nameGlobEmptyWhenNoMatch() =
        runTest {
            assertEquals("\n", out($$"echo ${!ZZZ_*}"))
        }

    @Test fun nameGlobIndirectStar() =
        runTest {
            assertEquals("HOST_A HOST_B\n", out($$"HOST_A=1\nHOST_B=2\necho ${!HOST_*}"))
        }

    // -------- quotes inside ${...} --------

    @Test fun singleQuotedBraceInsideParamExpansion() =
        runTest {
            // `${X+'}'z}` — the `'}'` is a single-quoted `}` and must NOT
            // close the outer `${...}` (using X to ensure set).
            val r = Kash().exec($$"X=v; echo ${X+'}'z}")
            assertEquals("}z\n", r.stdout)
        }

    @Test fun doubleQuotedBraceInsideParamExpansion() =
        runTest {
            val r = Kash().exec($$"X=v; echo ${X+\"}\"z}")
            assertEquals("}z\n", r.stdout)
        }

    @Test fun outerDoubleQuotedWithSingleQuotedBraceInsideParses() =
        runTest {
            // Smoke test — `'` inside DQ behaves differently from outside DQ for `${...}`
            // arg parsing, in ways the spec doesn't precisely pin down. The minimum bar
            // is that we don't throw a lex error on this input.
            Kash().exec($$"X=v; echo \"${X+'}'z}\"; echo after")
        }

    @Test fun ansiCStringWithEscapedQuoteParses() =
        runTest {
            // `echo $'\'abcd\''` — ANSI-C string with escaped single quotes.
            val r = Kash().exec($$"""echo $'\'abcd\''""")
            assertEquals("'abcd'\n", r.stdout)
        }

    @Test fun ansiCStringEscapesNewlineAndTab() =
        runTest {
            // $'\n\t' produces a literal newline followed by tab.
            val r = Kash().exec($$"""printf '%s' $'\n\t'""")
            assertEquals("\n\t", r.stdout)
        }

    @Test fun ansiCStringHexEscape() =
        runTest {
            val r = Kash().exec($$"""printf '%s' $'\x41\x42'""")
            assertEquals("AB", r.stdout)
        }

    @Test fun nestedDoubleQuoteInsideParamExpansion() =
        runTest {
            // `"foo ${X+"b c"} baz"` has `"..."` nested inside a `${...}`
            // arg that itself sits inside a surrounding `"..."`.
            val r = Kash().exec($$"X=v; printf '%s\\n' \"foo ${X+\"b   c\"} baz\"")
            assertEquals("foo b   c baz\n", r.stdout)
        }
}
