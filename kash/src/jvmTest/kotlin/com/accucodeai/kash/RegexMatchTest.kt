package com.accucodeai.kash

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** `[[ str =~ regex ]]` — quoted-vs-unquoted semantics, BASH_REMATCH. */
class RegexMatchTest {
    private fun run(script: String): String = runBlocking { Kash().exec(script).stdout }

    @Test fun posixCharClassMatches() {
        assertEquals("match\n", run("[[ a =~ [[:alpha:]] ]] && echo match"))
    }

    @Test fun unquotedVarIsRegex() {
        // VAR='[[:alpha:]]' — unquoted expansion is interpreted as regex.
        assertEquals(
            "yes\n",
            run("VAR='[[:alpha:]]'\n[[ a =~ \$VAR ]] && echo yes"),
        )
    }

    @Test fun doubleQuotedExpansionIsLiteral() {
        // "$VAR" expansion is regex-escaped — `[[:alpha:]]` matches literally.
        assertEquals(
            "yes\n",
            run("VAR='[[:alpha:]]'\n[[ '[[:alpha:]]' =~ \"\$VAR\" ]] && echo yes"),
        )
    }

    @Test fun singleQuotedInlineIsLiteral() {
        // `[[ a =~ '[[:alpha:]]' ]]` must NOT match — pattern is literal.
        assertEquals(
            "literal\n",
            run("[[ a =~ '[[:alpha:]]' ]] || echo literal"),
        )
    }

    @Test fun captureGroupsPopulateBashRematch() {
        val out =
            run(
                """
                [[ jbig2dec-0.9-i586-001.tgz =~ ([^-]+)-([^-]+)-([^-]+)-0*([1-9][0-9]*)\.tgz ]]
                echo "1=${'$'}{BASH_REMATCH[1]}"
                echo "2=${'$'}{BASH_REMATCH[2]}"
                echo "3=${'$'}{BASH_REMATCH[3]}"
                echo "4=${'$'}{BASH_REMATCH[4]}"
                """.trimIndent(),
            )
        assertEquals("1=jbig2dec\n2=0.9\n3=i586\n4=1\n", out)
    }

    @Test fun bashRematchZeroIsWholeMatch() {
        assertEquals(
            "hello\n",
            run("[[ hello =~ h.ll. ]] && echo \${BASH_REMATCH[0]}"),
        )
    }

    @Test fun noMatchClearsBashRematch() {
        // After a failed match, ${BASH_REMATCH[0]} should be empty.
        assertEquals(
            "\n",
            run("[[ aaa =~ zzz ]]\necho \${BASH_REMATCH[0]}"),
        )
    }
}
