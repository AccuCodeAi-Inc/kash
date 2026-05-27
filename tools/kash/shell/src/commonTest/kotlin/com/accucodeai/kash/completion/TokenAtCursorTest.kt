package com.accucodeai.kash.completion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [tokenAtCursor] — the pure-functional part of
 * [ShellCompleter] that decides which substring of the line the user is
 * currently editing and whether it's in command position vs. argument
 * position. Top-level fun in commonMain; same-module access so this test
 * can reach it via `internal`.
 */
class TokenAtCursorTest {
    @Test fun empty_line_at_zero() {
        val t = tokenAtCursor("", 0)
        assertEquals(0, t.start)
        assertEquals(0, t.end)
        assertEquals("", t.text)
        assertTrue(t.isCommandPosition, "empty line is command position")
    }

    @Test fun first_word_is_command_position() {
        val t = tokenAtCursor("ec", 2)
        assertEquals(0, t.start)
        assertEquals(2, t.end)
        assertEquals("ec", t.text)
        assertTrue(t.isCommandPosition)
    }

    @Test fun second_word_is_argument_position() {
        val t = tokenAtCursor("ls foo", 6)
        assertEquals(3, t.start)
        assertEquals(6, t.end)
        assertEquals("foo", t.text)
        assertTrue(!t.isCommandPosition)
    }

    @Test fun after_pipe_is_command_position() {
        val t = tokenAtCursor("ls | gr", 7)
        assertEquals(5, t.start)
        assertEquals(7, t.end)
        assertEquals("gr", t.text)
        assertTrue(t.isCommandPosition, "first word after | is command position")
    }

    @Test fun after_semicolon_is_command_position() {
        val t = tokenAtCursor("a; b", 4)
        assertEquals(3, t.start)
        assertEquals("b", t.text)
        assertTrue(t.isCommandPosition)
    }

    @Test fun after_and_and_is_command_position() {
        // `&&` ends with `&`, which is a command predecessor.
        val t = tokenAtCursor("true && ec", 10)
        assertEquals(8, t.start)
        assertEquals("ec", t.text)
        assertTrue(t.isCommandPosition)
    }

    @Test fun cursor_at_end_of_word_grabs_whole_word() {
        val t = tokenAtCursor("echo hello", 10)
        assertEquals(5, t.start)
        assertEquals(10, t.end)
        assertEquals("hello", t.text)
        assertTrue(!t.isCommandPosition)
    }

    @Test fun cursor_in_middle_of_word_grabs_prefix_only() {
        // Bash convention: completion uses [start..cursor), not the whole word.
        val t = tokenAtCursor("echo hello", 7)
        assertEquals(5, t.start)
        assertEquals(7, t.end)
        assertEquals("he", t.text)
    }

    @Test fun after_whitespace_only_is_command_position_at_start_of_line() {
        val t = tokenAtCursor("   ec", 5)
        assertEquals(3, t.start)
        assertEquals("ec", t.text)
        assertTrue(t.isCommandPosition, "first word — preceded only by leading whitespace")
    }

    @Test fun variable_prefix_token_included() {
        val t = tokenAtCursor("echo \$HO", 8)
        assertEquals(5, t.start)
        assertEquals("\$HO", t.text)
        assertTrue(!t.isCommandPosition)
    }

    @Test fun path_partial_is_one_token() {
        // `/` is NOT a token break — paths stay as one token.
        val t = tokenAtCursor("ls /usr/bi", 10)
        assertEquals(3, t.start)
        assertEquals("/usr/bi", t.text)
        assertTrue(!t.isCommandPosition)
    }

    @Test fun tilde_prefix_kept_in_token() {
        val t = tokenAtCursor("cat ~/.kash", 11)
        assertEquals(4, t.start)
        assertEquals("~/.kash", t.text)
        assertTrue(!t.isCommandPosition)
    }
}
