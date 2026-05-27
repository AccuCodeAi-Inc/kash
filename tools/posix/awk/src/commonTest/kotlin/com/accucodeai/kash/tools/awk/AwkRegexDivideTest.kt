package com.accucodeai.kash.tools.awk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Focused exercise of the regex-vs-divide lexer disambiguation.
 *
 * Each case pins one of the positional rules: after an operand-ending
 * token (IDENT, NUMBER, RPAREN, RBRACK, post-INC, REGEX) a `/` is
 * division; everywhere else a `/` opens a regex literal.
 */
class AwkRegexDivideTest {
    private suspend fun runAwk(
        source: String,
        input: List<String> = emptyList(),
    ): List<String> {
        val out = mutableListOf<String>()
        Awk
            .compile(source)
            .run(input.asSequence())
            .collect { out += it }
        return out.map { it.removeSuffix("\n") }
    }

    @Test fun divide_after_number() =
        runTest {
            assertEquals(listOf("5"), runAwk("BEGIN { print 10 / 2 }"))
        }

    @Test fun divide_after_ident() =
        runTest {
            assertEquals(listOf("4"), runAwk("BEGIN { x = 8; print x / 2 }"))
        }

    @Test fun divide_after_rparen() =
        runTest {
            assertEquals(listOf("3"), runAwk("BEGIN { print (6) / 2 }"))
        }

    @Test fun regex_after_match_operator() =
        runTest {
            val out =
                runAwk(
                    "/abc/ { print \"match\" }",
                    input = listOf("xabcx", "nope"),
                )
            assertEquals(listOf("match"), out)
        }

    @Test fun regex_after_explicit_tilde() =
        runTest {
            val out =
                runAwk(
                    "{ if (\$1 ~ /foo/) print \"hit\"; else print \"miss\" }",
                    input = listOf("foo bar", "bar baz"),
                )
            assertEquals(listOf("hit", "miss"), out)
        }

    @Test fun regex_after_comma_in_args() =
        runTest {
            // `match(s, /re/)` — the comma sets regexAllowed.
            val out = runAwk("""BEGIN { if (match("hello", /ell/)) print RSTART, RLENGTH }""")
            assertEquals(listOf("2 3"), out)
        }

    @Test fun regex_with_escaped_slash() =
        runTest {
            // `/\//` should parse as a regex matching a literal slash.
            val out =
                runAwk(
                    "{ if (\$0 ~ /\\//) print \"slash\" }",
                    input = listOf("a/b", "no"),
                )
            assertEquals(listOf("slash"), out)
        }

    @Test fun divide_assign_after_operand() =
        runTest {
            assertEquals(listOf("3"), runAwk("BEGIN { x = 12; x /= 4; print x }"))
        }

    @Test fun regex_at_top_of_program() =
        runTest {
            // /foo/ in pattern position — must be regex even though it's the
            // first non-WS token in the source.
            val out =
                runAwk(
                    "/foo/",
                    input = listOf("a foo b", "nope"),
                )
            assertEquals(listOf("a foo b"), out)
        }
}
