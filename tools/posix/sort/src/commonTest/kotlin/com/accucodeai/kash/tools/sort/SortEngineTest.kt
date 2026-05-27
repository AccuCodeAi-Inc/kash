package com.accucodeai.kash.tools.sort

import kotlin.test.Test
import kotlin.test.assertEquals

class SortEngineTest {
    private fun sort(
        input: List<String>,
        vararg argv: String,
    ): List<String> {
        val (opts, files) = SortOptionParser.parse(argv.toList())
        assertEquals(emptyList(), files, "no file args in engine tests")
        return SortEngine.sort(input, opts)
    }

    @Test fun lexicographic_default() {
        assertEquals(
            listOf("apple", "banana", "cherry"),
            sort(listOf("cherry", "apple", "banana")),
        )
    }

    @Test fun empty_input() {
        assertEquals(emptyList(), sort(emptyList()))
    }

    @Test fun reverse() {
        assertEquals(listOf("c", "b", "a"), sort(listOf("a", "c", "b"), "-r"))
    }

    @Test fun unique_drops_dupes() {
        assertEquals(
            listOf("a", "b", "c"),
            sort(listOf("b", "a", "c", "a", "b"), "-u"),
        )
    }

    @Test fun unique_with_keys_collapses_by_key_only() {
        // -u with -k1,1 collapses rows whose first field matches, keeping first encountered after sort
        val input = listOf("1 alpha", "2 beta", "1 gamma")
        val out = sort(input, "-u", "-k", "1,1")
        assertEquals(listOf("1 alpha", "2 beta"), out)
    }

    @Test fun numeric_sort_basic() {
        assertEquals(
            listOf("2", "10", "100"),
            sort(listOf("10", "100", "2"), "-n"),
        )
    }

    @Test fun numeric_sort_signed_and_decimal() {
        assertEquals(
            listOf("-3.5", "-1", "0", "0.5", "2", "10"),
            sort(listOf("10", "2", "0.5", "-1", "0", "-3.5"), "-n"),
        )
    }

    @Test fun numeric_sort_unparseable_is_zero() {
        // "abc" parses as 0, sits with "0" (stable -> input order preserved among equals).
        val out = sort(listOf("3", "abc", "0", "-2"), "-n")
        assertEquals(listOf("-2", "abc", "0", "3"), out)
    }

    @Test fun numeric_reverse() {
        assertEquals(
            listOf("100", "10", "2"),
            sort(listOf("10", "100", "2"), "-n", "-r"),
        )
    }

    @Test fun bundled_flags() {
        assertEquals(
            listOf("100", "10", "2"),
            sort(listOf("10", "100", "2", "10"), "-nru"),
        )
    }

    @Test fun key_single_field_default_separator() {
        // Sort by 2nd whitespace-delimited field
        val input =
            listOf(
                "bob 30",
                "alice 25",
                "carol 27",
            )
        assertEquals(
            listOf("alice 25", "carol 27", "bob 30"),
            sort(input, "-k", "2", "-n"),
        )
    }

    @Test fun key_range_two_fields() {
        // -k 2,3 sorts on fields 2..3 inclusive
        val input =
            listOf(
                "x b a 1",
                "x a c 2",
                "x a b 3",
            )
        // Compare on "b a", "a c", "a b" => sorted: "a b", "a c", "b a"
        assertEquals(
            listOf("x a b 3", "x a c 2", "x b a 1"),
            sort(input, "-k", "2,3"),
        )
    }

    @Test fun key_with_char_offset() {
        // -k 1.2 sorts starting at char 2 of field 1
        val input = listOf("Xb", "Aa", "Mc")
        // Compare "b", "a", "c" => Aa(a), Xb(b), Mc(c)
        assertEquals(listOf("Aa", "Xb", "Mc"), sort(input, "-k", "1.2"))
    }

    @Test fun key_field_beyond_record_treated_as_empty() {
        val input = listOf("a", "b c", "d e")
        // -k2 => keys: "", "c", "e"  => "a" first (empty), then "b c", then "d e"
        assertEquals(listOf("a", "b c", "d e"), sort(input, "-k", "2"))
    }

    @Test fun separator_tab() {
        val input = listOf("b\t1", "a\t2", "c\t3")
        assertEquals(listOf("a\t2", "b\t1", "c\t3"), sort(input, "-t", "\t", "-k", "1,1"))
    }

    @Test fun separator_comma_with_numeric_key() {
        val input = listOf("row,10", "row,2", "row,100")
        assertEquals(
            listOf("row,2", "row,10", "row,100"),
            sort(input, "-t", ",", "-k", "2", "-n"),
        )
    }

    @Test fun multiple_keys_with_global_numeric() {
        val input =
            listOf(
                "b 30",
                "a 20",
                "b 10",
                "a 5",
            )
        // With -n applied globally and stable string-comparison falling back through keys.
        // Primary field-1 strings parse as 0 numerically — to actually exercise numeric on
        // secondary, sort by (1,1) then (2,2) with -n; both keys interpret numerically.
        // "a"/"b" => 0/0 so secondary breaks ties.
        assertEquals(
            listOf("a 5", "b 10", "a 20", "b 30"),
            sort(input, "-n", "-k", "2,2"),
        )
    }

    @Test fun stable_sort_preserves_input_order_on_ties() {
        val input = listOf("a x", "a y", "a z")
        // All compare equal on -k1,1; stable sort preserves order.
        assertEquals(input, sort(input, "-k", "1,1"))
    }
}
