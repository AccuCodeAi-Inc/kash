package com.accucodeai.kash

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POSIX §2.6.1 tilde-prefix rules — a tilde-prefix exists only when the
 * `~` is followed (within the same literal segment) by characters bounded
 * by `/` or end-of-word. When the very next part is a quoted segment,
 * escape, or parameter/command/arith expansion, the leading `~` stays
 * literal.
 */
class TildeRulesTest {
    @Test
    fun tildeFollowedByExpansionStaysLiteral() =
        runTest {
            // bash: `~$USER` does not expand — the user name is dynamic.
            val r = Kash().exec("USER=root; echo ~\$USER")
            assertEquals("~root\n", r.stdout)
        }

    @Test
    fun tildeFollowedByEscapeStaysLiteral() =
        runTest {
            // `~\chet/bar` — backslash escapes the `c`, so there is no
            // contiguous literal user-name segment for the tilde-prefix.
            val r = Kash().exec("echo ~\\chet/bar")
            assertEquals("~chet/bar\n", r.stdout)
        }

    @Test
    fun cdSetsOldpwd() =
        runTest {
            val script =
                """
                cd /usr
                cd /tmp
                echo "${'$'}OLDPWD"
                echo ~-
                """.trimIndent()
            val r = Kash().exec(script)
            assertEquals("/usr\n/usr\n", r.stdout)
        }

    @Test
    fun cdDashGoesToOldpwd() =
        runTest {
            val r = Kash().exec("cd /usr\ncd /tmp\ncd -")
            // `cd -` echoes the new pwd to stdout (the prior OLDPWD).
            assertEquals("/usr\n", r.stdout)
        }

    @Test
    fun exportAssignmentTildeAfterColonExpands() =
        runTest {
            // bash: `export VAR=A:~/foo` expands the tilde after `:`.
            val script =
                """
                HOME=/x
                export VAR=A:~/foo
                echo "${'$'}VAR"
                """.trimIndent()
            assertEquals("A:/x/foo\n", Kash().exec(script).stdout)
        }
}
