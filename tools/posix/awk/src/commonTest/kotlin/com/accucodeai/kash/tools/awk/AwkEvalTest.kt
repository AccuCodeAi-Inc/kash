package com.accucodeai.kash.tools.awk

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end behavior tests: compile a program, feed it records, check the
 * output the way a real CLI invocation would see it (each line of output is
 * one element of the returned list, ORS-terminator stripped).
 */
class AwkEvalTest {
    private suspend fun runAwk(
        source: String,
        input: List<String> = emptyList(),
        opts: AwkOptions = AwkOptions(),
    ): List<String> {
        val collected = mutableListOf<String>()
        Awk
            .compile(source)
            .run(input.asSequence(), opts)
            .collect { collected += it }
        // ORS is part of each emitted chunk; strip it for easy
        // comparison since most tests check line content, not the
        // separator policy.
        return collected.map { it.removeSuffix("\n") }
    }

    // -- BEGIN / END --------------------------------------------------------

    @Test fun begin_only() =
        runTest {
            assertEquals(listOf("hello"), runAwk("BEGIN { print \"hello\" }"))
        }

    @Test fun end_runs_after_records() =
        runTest {
            val out = runAwk("END { print NR }", input = listOf("a", "b", "c"))
            assertEquals(listOf("3"), out)
        }

    @Test fun begin_then_end() =
        runTest {
            val out = runAwk("BEGIN { print \"start\" } END { print \"stop\" }")
            assertEquals(listOf("start", "stop"), out)
        }

    // -- Fields -------------------------------------------------------------

    @Test fun default_field_split() =
        runTest {
            val out = runAwk("{ print \$2 }", input = listOf("one two three", "a b c"))
            assertEquals(listOf("two", "b"), out)
        }

    @Test fun nf_counts_fields() =
        runTest {
            val out = runAwk("{ print NF }", input = listOf("a b c", "x"))
            assertEquals(listOf("3", "1"), out)
        }

    @Test fun custom_fs_via_option() =
        runTest {
            val out =
                runAwk(
                    "{ print \$2 }",
                    input = listOf("a,b,c", "x,y,z"),
                    opts = AwkOptions(fieldSeparator = ","),
                )
            assertEquals(listOf("b", "y"), out)
        }

    @Test fun dollar_zero_is_whole_record() =
        runTest {
            val out = runAwk("{ print \$0 }", input = listOf("hello world"))
            assertEquals(listOf("hello world"), out)
        }

    @Test fun field_assignment_rebuilds_dollar_zero() =
        runTest {
            val out =
                runAwk(
                    "{ \$2 = \"X\"; print }",
                    input = listOf("a b c"),
                )
            assertEquals(listOf("a X c"), out)
        }

    // -- Pattern matching ---------------------------------------------------

    @Test fun bare_regex_matches_against_record() =
        runTest {
            val out = runAwk("/foo/ { print }", input = listOf("foobar", "baz", "Xfoo"))
            assertEquals(listOf("foobar", "Xfoo"), out)
        }

    @Test fun expr_pattern_matches_when_truthy() =
        runTest {
            val out = runAwk("NR == 2 { print }", input = listOf("a", "b", "c"))
            assertEquals(listOf("b"), out)
        }

    @Test fun pattern_without_action_defaults_to_print() =
        runTest {
            val out = runAwk("/^x/", input = listOf("xyz", "abc", "xxx"))
            assertEquals(listOf("xyz", "xxx"), out)
        }

    // -- Arithmetic & operators --------------------------------------------

    @Test fun arithmetic_basic() =
        runTest {
            val out = runAwk("BEGIN { print 2 + 3, 10 - 4, 6 * 7, 20 / 4, 17 % 5, 2 ^ 8 }")
            assertEquals(listOf("5 6 42 5 2 256"), out)
        }

    @Test fun string_concat_by_juxtaposition() =
        runTest {
            val out = runAwk("BEGIN { print \"x=\" 42 }")
            assertEquals(listOf("x=42"), out)
        }

    @Test fun comparisons_numeric_vs_string() =
        runTest {
            // POSIX: comparing two unhinted strings is string-compare.
            val out =
                runAwk(
                    "BEGIN { print (\"10\" < \"9\"); print (10 < 9) }",
                )
            // String "10" < "9" lexicographically: true. Numeric 10 < 9: false.
            assertEquals(listOf("1", "0"), out)
        }

    @Test fun pre_and_post_increment() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { x = 5; print x++; print x; print ++x; print x }",
                )
            assertEquals(listOf("5", "6", "7", "7"), out)
        }

    // -- Control flow -------------------------------------------------------

    @Test fun if_else_branch() =
        runTest {
            val out =
                runAwk(
                    "{ if (\$1 > 0) print \"pos\"; else print \"nonpos\" }",
                    input = listOf("5", "0", "-3"),
                )
            assertEquals(listOf("pos", "nonpos", "nonpos"), out)
        }

    @Test fun while_loop() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { i = 0; while (i < 3) { print i; i++ } }",
                )
            assertEquals(listOf("0", "1", "2"), out)
        }

    @Test fun for_loop() =
        runTest {
            val out =
                runAwk("BEGIN { for (i = 1; i <= 3; i++) print i }")
            assertEquals(listOf("1", "2", "3"), out)
        }

    @Test fun do_while() =
        runTest {
            val out =
                runAwk("BEGIN { i = 0; do { print i; i++ } while (i < 2) }")
            assertEquals(listOf("0", "1"), out)
        }

    @Test fun break_and_continue() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { for (i = 0; i < 5; i++) { if (i == 1) continue; if (i == 3) break; print i } }",
                )
            assertEquals(listOf("0", "2"), out)
        }

    @Test fun next_skips_remaining_rules() =
        runTest {
            val out =
                runAwk(
                    "/^x/ { next } { print \$1 }",
                    input = listOf("xfoo", "bar", "xbaz", "qux"),
                )
            assertEquals(listOf("bar", "qux"), out)
        }

    // -- Arrays -------------------------------------------------------------

    @Test fun simple_array() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { a[1] = \"one\"; a[2] = \"two\"; print a[1], a[2] }",
                )
            assertEquals(listOf("one two"), out)
        }

    @Test fun for_in_iteration() =
        runTest {
            // Order isn't guaranteed by POSIX; sort the output for stability.
            val out =
                runAwk(
                    "BEGIN { a[\"x\"] = 1; a[\"y\"] = 2; for (k in a) print k }",
                )
            assertEquals(listOf("x", "y"), out.sorted())
        }

    @Test fun in_operator() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { a[\"x\"] = 1; print (\"x\" in a); print (\"z\" in a) }",
                )
            assertEquals(listOf("1", "0"), out)
        }

    @Test fun delete_element() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { a[1] = 10; delete a[1]; print a[1]; print (1 in a) }",
                )
            assertEquals(listOf("", "0"), out)
        }

    // -- Match / regex ------------------------------------------------------

    @Test fun match_operator_against_field() =
        runTest {
            val out =
                runAwk(
                    "{ if (\$0 ~ /^[0-9]+$/) print \"num:\", \$0; else print \"other:\", \$0 }",
                    input = listOf("42", "hello", "100"),
                )
            assertEquals(listOf("num: 42", "other: hello", "num: 100"), out)
        }

    @Test fun match_against_string_pattern_expr() =
        runTest {
            val out =
                runAwk(
                    "{ pat = \"^[A-Z]\"; if (\$0 ~ pat) print }",
                    input = listOf("hello", "World", "again"),
                )
            assertEquals(listOf("World"), out)
        }

    // -- Builtins -----------------------------------------------------------

    @Test fun length_of_string_and_field_zero() =
        runTest {
            val out =
                runAwk(
                    "{ print length, length(\$1) }",
                    input = listOf("hello world"),
                )
            assertEquals(listOf("11 5"), out)
        }

    @Test fun substr_two_and_three_arg() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { print substr(\"hello\", 2); print substr(\"hello\", 2, 3) }",
                )
            assertEquals(listOf("ello", "ell"), out)
        }

    @Test fun index_builtin() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { print index(\"hello\", \"ll\"); print index(\"hello\", \"x\") }",
                )
            assertEquals(listOf("3", "0"), out)
        }

    @Test fun tolower_and_toupper() =
        runTest {
            val out = runAwk("BEGIN { print tolower(\"HeLLo\"), toupper(\"HeLLo\") }")
            assertEquals(listOf("hello HELLO"), out)
        }

    @Test fun sub_and_gsub() =
        runTest {
            val out =
                runAwk(
                    """
                    BEGIN {
                        s = "foo bar foo baz";
                        n = sub(/foo/, "X", s); print n, s;
                        t = "foo bar foo baz";
                        m = gsub(/foo/, "X", t); print m, t
                    }
                    """.trimIndent(),
                )
            assertEquals(listOf("1 X bar foo baz", "2 X bar X baz"), out)
        }

    @Test fun split_into_array() =
        runTest {
            val out =
                runAwk(
                    """
                    BEGIN {
                        n = split("a,b,c", parts, ",");
                        print n, parts[1], parts[2], parts[3]
                    }
                    """.trimIndent(),
                )
            assertEquals(listOf("3 a b c"), out)
        }

    @Test fun sprintf_format() =
        runTest {
            val out =
                runAwk(
                    """BEGIN { print sprintf("%5d-%s", 42, "ok") }""",
                )
            assertEquals(listOf("   42-ok"), out)
        }

    @Test fun printf_no_implicit_newline() =
        runTest {
            val out = runAwk("""BEGIN { printf "%d", 7; printf " %s\n", "done" }""")
            assertEquals(listOf("7 done"), out)
        }

    @Test fun int_truncates_toward_zero() =
        runTest {
            val out = runAwk("BEGIN { print int(3.7), int(-3.7), int(2) }")
            assertEquals(listOf("3 -3 2"), out)
        }

    @Test fun math_builtins() =
        runTest {
            val out = runAwk("BEGIN { printf \"%.4f %.4f %.4f\\n\", sqrt(2), exp(1), log(exp(3)) }")
            assertEquals(listOf("1.4142 2.7183 3.0000"), out)
        }

    // -- Variable pre-assignment -------------------------------------------

    @Test fun pre_assignments_via_options() =
        runTest {
            val out =
                runAwk(
                    "BEGIN { print x, y }",
                    opts = AwkOptions(preAssignments = mapOf("x" to "hi", "y" to "42")),
                )
            assertEquals(listOf("hi 42"), out)
        }

    @Test fun exit_in_record_runs_end() =
        runTest {
            val out =
                runAwk(
                    """
                    /stop/ { exit 0 }
                    { print "saw", \$0 }
                    END { print "end ran" }
                    """.trimIndent(),
                    input = listOf("a", "b", "stop", "c"),
                )
            assertEquals(listOf("saw a", "saw b", "end ran"), out)
        }
}
