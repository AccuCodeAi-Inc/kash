package com.accucodeai.kash.tools.awk

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Parser-only smoke tests. Each case must compile without throwing —
 * semantic correctness is covered by [AwkEvalTest].
 */
class AwkParserTest {
    private fun parses(source: String) {
        Awk.compile(source)
    }

    @Test fun empty_program_parses() {
        parses("")
    }

    @Test fun begin_end_only() {
        parses("BEGIN { print \"hello\" } END { print \"bye\" }")
    }

    @Test fun pattern_action_rule() {
        parses("/foo/ { print }")
    }

    @Test fun action_only_rule() {
        parses("{ print \$1, \$2 }")
    }

    @Test fun pattern_only_rule() {
        parses("/foo/")
    }

    @Test fun if_else_chain() {
        parses("{ if (\$1 > 5) print \"big\"; else if (\$1 > 0) print \"small\"; else print \"none\" }")
    }

    @Test fun for_loop_classic() {
        parses("BEGIN { for (i = 1; i <= 10; i++) print i }")
    }

    @Test fun for_in_array() {
        parses("BEGIN { a[1] = 2; for (k in a) print k, a[k] }")
    }

    @Test fun while_and_do_while() {
        parses("{ while (\$1 > 0) \$1-- ; do print \$0; while (NR < 5) }")
    }

    @Test fun ternary_and_logical() {
        parses("{ print (\$1 > 0 ? \"+\" : \"-\") }")
        parses("{ if (\$1 && !\$2 || \$3 == \"x\") print }")
    }

    @Test fun multi_arg_print_and_concat() {
        parses("{ print \$1 \" -> \" \$2 }")
        parses("BEGIN { print 1, 2, 3 }")
    }

    @Test fun function_def_parses() {
        parses("function foo(a, b) { return a + b } BEGIN { print foo(1, 2) }")
    }

    @Test fun regex_vs_divide_after_operand() {
        // `n / 2` is division because `n` is an operand. The lexer's
        // regexAllowed flag should be false after the IDENT.
        parses("BEGIN { n = 10; print n / 2 }")
    }

    @Test fun regex_vs_divide_after_operator() {
        // `~ /foo/` — regex required after `~`. Then `n / 2` is divide.
        parses("{ if (\$0 ~ /foo/) print \$0 / 2 }")
    }

    @Test fun assignment_compound() {
        parses("BEGIN { x = 1; x += 2; x -= 1; x *= 3; x /= 2; x %= 2; x ^= 2 }")
    }

    @Test fun delete_statement_forms() {
        parses("BEGIN { a[1] = 1; delete a[1]; delete a }")
    }

    @Test fun rejects_unterminated_string() {
        assertFailsWith<AwkParseError> { Awk.compile("BEGIN { print \"hello") }
    }

    @Test fun rejects_unbalanced_brace() {
        assertFailsWith<AwkParseError> { Awk.compile("BEGIN { print 1") }
    }
}
