package com.accucodeai.kash.tools.jq

import kotlin.test.Test
import kotlin.test.assertFailsWith

class JqParserTest {
    @Test fun parses_basic_filters() {
        // No assertion on AST shape — just shouldn't throw.
        Jq.compile(".")
        Jq.compile(".a.b.c")
        Jq.compile(".[]")
        Jq.compile(".[1:3]")
        Jq.compile(".a | .b")
        Jq.compile(".a, .b")
        Jq.compile("if . then 1 else 2 end")
        Jq.compile("try .a catch .b")
        Jq.compile("reduce .[] as \$x (0; . + \$x)")
        Jq.compile("[.a, .b]")
        Jq.compile("{a, b: .c}")
        Jq.compile(""""hello \(.x)"""")
        Jq.compile(". // 0")
        Jq.compile(". as \$x | \$x + 1")
    }

    @Test fun parses_assignment_operators() {
        Jq.compile(".a = 1")
        Jq.compile(".a |= . + 1")
        Jq.compile(".a += 1")
        Jq.compile(".a -= 1")
        Jq.compile(".a *= 2")
        Jq.compile(".a /= 2")
        Jq.compile(".a %= 2")
        Jq.compile(".a //= 0")
    }

    @Test fun rejects_unterminated_string() {
        assertFailsWith<JqParseError> { Jq.compile(""""abc""") }
    }
}
