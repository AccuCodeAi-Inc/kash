package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression coverage for POSIX §2.6.5 word splitting: split *results of
 * expansion*, never source literals. Before this was wired up, `IFS=:; echo
 * a:b:c` was incorrectly splitting the literal `a:b:c` into three args, and
 * `foo=$(echo a:b:c)` was returning a colon-replaced-with-space string.
 */
class WordSplitLiteralTest {
    @Test
    fun literalArgIsNotSplitOnIfs() =
        runTest {
            // bash: `echo a:b:c` with IFS=: prints "a:b:c\n" (one arg).
            val r = Kash().exec("IFS=:\necho a:b:c")
            assertEquals("a:b:c\n", r.stdout)
        }

    @Test
    fun commandSubResultIsSplitOnIfs() =
        runTest {
            // bash: `echo $(echo a:b:c)` with IFS=: splits the substitution
            // result and rejoins with space → "a b c\n".
            val r = Kash().exec("IFS=:\necho \$(echo a:b:c)")
            assertEquals("a b c\n", r.stdout)
        }

    @Test
    fun assignmentRhsKeepsIfsCharsLiteral() =
        runTest {
            // Assignment RHS isn't subject to splitting at all — `foo=$(...)`
            // captures the substitution verbatim.
            val r = Kash().exec("IFS=:\nfoo=\$(echo a:b:c)\nprintf '<%s>\\n' \"\$foo\"")
            assertEquals("<a:b:c>\n", r.stdout)
        }

    @Test
    fun transientIfsAppliesToFunctionCall() =
        runTest {
            // `IFS=: f arg` makes IFS=: visible inside f's body — confirmed by
            // bash. Without the transient-env-for-functions wiring, the
            // function-internal `echo $1` wouldn't split on `:`.
            val script =
                """
                f() { echo ${'$'}1; }
                IFS=: f a:b:c:d:e
                """.trimIndent()
            assertEquals("a b c d e\n", Kash().exec(script).stdout)
        }
}
