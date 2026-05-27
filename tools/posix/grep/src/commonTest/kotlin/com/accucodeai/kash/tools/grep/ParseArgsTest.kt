package com.accucodeai.kash.tools.grep

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParseArgsTest {
    @Test fun positional_is_pattern_then_files() {
        val p = parseArgs(listOf("foo", "/a", "/b"), GrepMode.BRE, "grep")
        assertEquals(listOf("foo"), p.opts.patterns)
        assertEquals(listOf("/a", "/b"), p.files)
    }

    @Test fun dash_e_overrides_first_positional() {
        val p = parseArgs(listOf("-e", "foo", "/a"), GrepMode.BRE, "grep")
        assertEquals(listOf("foo"), p.opts.patterns)
        assertEquals(listOf("/a"), p.files)
    }

    @Test fun multiple_dash_e_accumulate() {
        val p = parseArgs(listOf("-e", "x", "-e", "y", "/file"), GrepMode.BRE, "grep")
        assertEquals(listOf("x", "y"), p.opts.patterns)
        assertEquals(listOf("/file"), p.files)
    }

    @Test fun dash_f_is_encoded_as_marker() {
        val p = parseArgs(listOf("-f", "/p"), GrepMode.BRE, "grep")
        assertEquals(1, p.opts.patterns.size)
        assertTrue(p.opts.patterns[0].startsWith(PATTERN_FILE_MARKER))
        assertTrue(p.opts.patterns[0].endsWith("/p"))
    }

    @Test fun clustered_flags_parse() {
        val p = parseArgs(listOf("-ivn", "foo"), GrepMode.BRE, "grep")
        assertTrue(p.opts.ignoreCase)
        assertTrue(p.opts.invert)
        assertTrue(p.opts.lineNumbers)
    }

    @Test fun dash_E_switches_mode_to_ERE() {
        val p = parseArgs(listOf("-E", "foo"), GrepMode.BRE, "grep")
        assertEquals(GrepMode.ERE, p.opts.mode)
    }

    @Test fun dash_F_switches_mode_to_FIXED() {
        val p = parseArgs(listOf("-F", "foo"), GrepMode.ERE, "egrep")
        assertEquals(GrepMode.FIXED, p.opts.mode)
    }

    @Test fun unknown_option_throws() {
        assertFailsWith<GrepUsageError> {
            parseArgs(listOf("-Z", "foo"), GrepMode.BRE, "grep")
        }
    }

    @Test fun double_dash_ends_options() {
        val p = parseArgs(listOf("--", "-v", "/a"), GrepMode.BRE, "grep")
        // -v becomes pattern (first positional), /a is file
        assertEquals(listOf("-v"), p.opts.patterns)
        assertEquals(listOf("/a"), p.files)
        assertTrue(!p.opts.invert)
    }

    @Test fun dash_recursive() {
        val p = parseArgs(listOf("-r", "pat", "/d"), GrepMode.BRE, "grep")
        assertTrue(p.recursive)
    }

    @Test fun long_options() {
        val p = parseArgs(listOf("--ignore-case", "--invert-match", "foo"), GrepMode.BRE, "grep")
        assertTrue(p.opts.ignoreCase)
        assertTrue(p.opts.invert)
    }

    @Test fun long_regexp_assignment() {
        val p = parseArgs(listOf("--regexp=foo", "/f"), GrepMode.BRE, "grep")
        assertEquals(listOf("foo"), p.opts.patterns)
        assertEquals(listOf("/f"), p.files)
    }

    @Test fun multiple_include_globs_accumulate_in_options() {
        val p =
            parseArgs(
                listOf("--include=*.kt", "--include=*.md", "-r", "foo", "/d"),
                GrepMode.BRE,
                "grep",
            )
        assertEquals(listOf("*.kt", "*.md"), p.opts.include)
    }

    @Test fun exclude_dir_accumulates() {
        val p =
            parseArgs(
                listOf("--exclude-dir=.git", "--exclude-dir=node_modules", "-r", "x", "/d"),
                GrepMode.BRE,
                "grep",
            )
        assertEquals(listOf(".git", "node_modules"), p.opts.excludeDir)
    }

    @Test fun binary_files_text_short_and_long_are_equivalent() {
        val short = parseArgs(listOf("-a", "x"), GrepMode.BRE, "grep")
        val long1 = parseArgs(listOf("--binary-files=text", "x"), GrepMode.BRE, "grep")
        val long2 = parseArgs(listOf("--text", "x"), GrepMode.BRE, "grep")
        assertEquals(BinaryMode.TEXT, short.opts.binaryMode)
        assertEquals(BinaryMode.TEXT, long1.opts.binaryMode)
        assertEquals(BinaryMode.TEXT, long2.opts.binaryMode)
    }

    @Test fun binary_files_without_match_short_and_long_are_equivalent() {
        val short = parseArgs(listOf("-I", "x"), GrepMode.BRE, "grep")
        val long = parseArgs(listOf("--binary-files=without-match", "x"), GrepMode.BRE, "grep")
        assertEquals(BinaryMode.WITHOUT_MATCH, short.opts.binaryMode)
        assertEquals(BinaryMode.WITHOUT_MATCH, long.opts.binaryMode)
    }
}

class BreToEreTest {
    @Test fun bre_escaped_paren_becomes_ere_paren() {
        assertEquals("(a|b)", breToEre("\\(a\\|b\\)"))
    }

    @Test fun bre_literal_paren_becomes_escaped() {
        assertEquals("a\\(b\\)", breToEre("a(b)"))
    }

    @Test fun bre_plus_question_are_literal_in_BRE() {
        assertEquals("a\\+b\\?", breToEre("a+b?"))
    }

    @Test fun bre_escaped_plus_becomes_ere_plus() {
        assertEquals("a+b?", breToEre("a\\+b\\?"))
    }

    @Test fun bracket_class_passes_through() {
        assertEquals("[(a-z)]", breToEre("[(a-z)]"))
    }

    @Test fun literal_dot_unchanged() {
        assertEquals("a.b", breToEre("a.b"))
    }
}

class EscapeLiteralTest {
    @Test fun all_meta_chars_escaped() {
        // Should be safe to compile and match the literal.
        val esc = escapeLiteral("a.b*c+(d)[e]{f}|g")
        // Sanity: no unescaped meta survives.
        assertTrue(esc.contains("\\."))
        assertTrue(esc.contains("\\*"))
        assertTrue(esc.contains("\\("))
        assertTrue(esc.contains("\\["))
        assertTrue(esc.contains("\\{"))
        assertTrue(esc.contains("\\|"))
    }
}
