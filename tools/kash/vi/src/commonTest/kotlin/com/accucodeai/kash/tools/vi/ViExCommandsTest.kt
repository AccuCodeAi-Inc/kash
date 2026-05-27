package com.accucodeai.kash.tools.vi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViExCommandsTest {
    private fun buf(text: String): ViBuffer = ViBuffer.fromText(text, "x")

    @Test fun parse_quit() {
        assertEquals(ExParsed.Quit, ViExCommands.parse("q"))
        assertEquals(ExParsed.Quit, ViExCommands.parse("quit"))
    }

    @Test fun parse_quitForce() {
        assertEquals(ExParsed.QuitForce, ViExCommands.parse("q!"))
    }

    @Test fun parse_write_noArg() {
        assertEquals(ExParsed.Write(null), ViExCommands.parse("w"))
    }

    @Test fun parse_write_withPath() {
        assertEquals(ExParsed.Write("foo.txt"), ViExCommands.parse("w foo.txt"))
    }

    @Test fun parse_writeQuit() {
        assertEquals(ExParsed.WriteQuit, ViExCommands.parse("wq"))
        assertEquals(ExParsed.WriteQuit, ViExCommands.parse("x"))
    }

    @Test fun parse_goto_lineNumber() {
        assertEquals(ExParsed.Goto(42), ViExCommands.parse("42"))
    }

    @Test fun parse_substitute_currentLine() {
        val s = ViExCommands.parse("s/foo/bar/") as ExParsed.Substitute
        assertEquals("foo", s.pattern)
        assertEquals("bar", s.replacement)
        assertFalse(s.global)
        assertFalse(s.wholeFile)
    }

    @Test fun parse_substitute_global() {
        val s = ViExCommands.parse("s/foo/bar/g") as ExParsed.Substitute
        assertTrue(s.global)
    }

    @Test fun parse_substitute_wholeFile() {
        val s = ViExCommands.parse("%s/foo/bar/g") as ExParsed.Substitute
        assertTrue(s.wholeFile)
        assertTrue(s.global)
    }

    @Test fun parse_substitute_emptyReplacement() {
        val s = ViExCommands.parse("s/foo//") as ExParsed.Substitute
        assertEquals("", s.replacement)
    }

    @Test fun parse_edit() {
        assertEquals(ExParsed.Edit("x.txt"), ViExCommands.parse("e x.txt"))
        assertEquals(ExParsed.Edit(null), ViExCommands.parse("e"))
    }

    @Test fun parse_setNumber() {
        assertEquals(ExParsed.Set("number", true), ViExCommands.parse("set number"))
        assertEquals(ExParsed.Set("number", true), ViExCommands.parse("set nu"))
    }

    @Test fun parse_setNonumber() {
        assertEquals(ExParsed.Set("number", false), ViExCommands.parse("set nonumber"))
    }

    @Test fun parse_noh() {
        assertEquals(ExParsed.NoHighlight, ViExCommands.parse("noh"))
    }

    @Test fun parse_help() {
        assertEquals(ExParsed.Help, ViExCommands.parse("help"))
    }

    @Test fun parse_unknown() {
        assertTrue(ViExCommands.parse("notacmd") is ExParsed.Unknown)
    }

    @Test fun exec_goto_movesCursor() {
        val b = buf("a\nb\nc\nd\ne")
        val res = ViExCommands.execute(ExParsed.Goto(3), b)
        assertEquals(2, res.buf.cursorRow)
    }

    @Test fun exec_quit_onDirty_warns() {
        val b = buf("hi").copy(dirty = true)
        val res = ViExCommands.execute(ExParsed.Quit, b)
        assertFalse(res.quit)
        assertTrue(res.message.isNotEmpty())
    }

    @Test fun exec_quit_onClean_quits() {
        val b = buf("hi")
        val res = ViExCommands.execute(ExParsed.Quit, b)
        assertTrue(res.quit)
    }

    @Test fun exec_quitForce_alwaysQuits() {
        val b = buf("hi").copy(dirty = true)
        val res = ViExCommands.execute(ExParsed.QuitForce, b)
        assertTrue(res.quit)
    }

    @Test fun exec_write_requestsSave() {
        val res = ViExCommands.execute(ExParsed.Write("out.txt"), buf("hi"))
        assertTrue(res.saveRequested)
        assertEquals("out.txt", res.saveAs)
    }

    @Test fun exec_substitute_currentLine() {
        val b = buf("foo\nfoo\nfoo").copy(cursorRow = 0)
        val res =
            ViExCommands.execute(
                ExParsed.Substitute("foo", "bar", global = false, wholeFile = false),
                b,
            )
        assertEquals(listOf("bar", "foo", "foo"), res.buf.lines)
        assertTrue(res.buf.dirty)
    }

    @Test fun exec_substitute_global_currentLine() {
        val b = buf("foofoo\nfoo").copy(cursorRow = 0)
        val res =
            ViExCommands.execute(
                ExParsed.Substitute("foo", "X", global = true, wholeFile = false),
                b,
            )
        assertEquals(listOf("XX", "foo"), res.buf.lines)
    }

    @Test fun exec_substitute_wholeFile_global() {
        val b = buf("foo bar\nfoo baz")
        val res =
            ViExCommands.execute(
                ExParsed.Substitute("foo", "X", global = true, wholeFile = true),
                b,
            )
        assertEquals(listOf("X bar", "X baz"), res.buf.lines)
    }

    @Test fun exec_substitute_noMatch_message() {
        val b = buf("abc")
        val res =
            ViExCommands.execute(
                ExParsed.Substitute("xyz", "q", global = false, wholeFile = true),
                b,
            )
        assertTrue(res.message.contains("not found"))
        assertEquals(listOf("abc"), res.buf.lines)
    }

    @Test fun exec_substitute_regex() {
        val b = buf("a1b2c3").copy(cursorRow = 0)
        val res =
            ViExCommands.execute(
                ExParsed.Substitute("[0-9]", "X", global = true, wholeFile = false),
                b,
            )
        assertEquals("aXbXcX", res.buf.lines[0])
    }

    @Test fun exec_set_number_on() {
        val res = ViExCommands.execute(ExParsed.Set("number", true), buf("a"))
        assertTrue(res.buf.showLineNumbers)
    }

    @Test fun exec_set_number_off() {
        val b = buf("a").copy(showLineNumbers = true)
        val res = ViExCommands.execute(ExParsed.Set("number", false), b)
        assertFalse(res.buf.showLineNumbers)
    }

    @Test fun exec_noh_clearsLastSearch() {
        val b = buf("a").copy(lastSearch = LastSearch("foo", true))
        val res = ViExCommands.execute(ExParsed.NoHighlight, b)
        assertEquals(null, res.buf.lastSearch)
    }

    @Test fun exec_help_returnsMessage() {
        val res = ViExCommands.execute(ExParsed.Help, buf("a"))
        assertTrue(res.message.contains(":w"))
    }

    @Test fun exec_unknown_returnsErrorMessage() {
        val res = ViExCommands.execute(ExParsed.Unknown("blah"), buf("a"))
        assertTrue(res.message.contains("blah"))
    }
}
