package com.accucodeai.kash.tools.vi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ViBufferTest {
    private fun buf(
        text: String,
        row: Int = 0,
        col: Int = 0,
        mode: ViMode = ViMode.NORMAL,
    ): ViBuffer = ViBuffer.fromText(text, "x").copy(cursorRow = row, cursorCol = col, mode = mode)

    // ---------- fromText ----------

    @Test fun fromText_singleLineNoNewline() {
        val b = ViBuffer.fromText("hello", "f")
        assertEquals(listOf("hello"), b.lines)
        assertFalse(b.dirty)
        assertFalse(b.trailingNewline)
    }

    @Test fun fromText_trailingNewlineDropsEmpty() {
        val b = ViBuffer.fromText("a\nb\n", "f")
        assertEquals(listOf("a", "b"), b.lines)
        assertTrue(b.trailingNewline)
    }

    @Test fun fromText_emptyYieldsOneLine() {
        val b = ViBuffer.fromText("", "f")
        assertEquals(listOf(""), b.lines)
    }

    // ---------- Motions: hjkl/0/$/^ ----------

    @Test fun motion_h_movesLeft() {
        val b = buf("hello", col = 3)
        assertEquals(Pos(0, 2), ViMotions.left(b))
    }

    @Test fun motion_l_movesRight() {
        val b = buf("hello", col = 1)
        assertEquals(Pos(0, 2), ViMotions.right(b))
    }

    @Test fun motion_l_atEolStays() {
        val b = buf("ab", col = 1)
        assertEquals(Pos(0, 1), ViMotions.right(b))
    }

    @Test fun motion_j_clampsCol() {
        val b = buf("longer\nx", col = 5)
        assertEquals(Pos(1, 0), ViMotions.down(b))
    }

    @Test fun motion_k_atTopStays() {
        val b = buf("a\nb", row = 0)
        assertEquals(Pos(0, 0), ViMotions.up(b))
    }

    @Test fun motion_0_lineStart() {
        val b = buf("  hello", col = 4)
        assertEquals(Pos(0, 0), ViMotions.lineStart(b))
    }

    @Test fun motion_caret_firstNonBlank() {
        val b = buf("   hi", col = 4)
        assertEquals(Pos(0, 3), ViMotions.firstNonBlank(b))
    }

    @Test fun motion_dollar_lineEnd() {
        val b = buf("hello", col = 0)
        assertEquals(Pos(0, 4), ViMotions.lineEnd(b))
    }

    // ---------- Word motions ----------

    @Test fun motion_w_nextWordStart() {
        val b = buf("foo bar baz", col = 0)
        assertEquals(Pos(0, 4), ViMotions.nextWordStart(b))
    }

    @Test fun motion_w_skipsAcrossPunctuation() {
        val b = buf("foo,bar", col = 0)
        assertEquals(Pos(0, 3), ViMotions.nextWordStart(b))
    }

    @Test fun motion_W_treatsPunctuationAsWord() {
        val b = buf("foo,bar baz", col = 0)
        assertEquals(Pos(0, 8), ViMotions.nextWordStart(b, big = true))
    }

    @Test fun motion_w_acrossLines() {
        val b = buf("foo\n  bar", col = 2)
        assertEquals(Pos(1, 2), ViMotions.nextWordStart(b))
    }

    @Test fun motion_b_prevWordStart() {
        val b = buf("foo bar", col = 5)
        assertEquals(Pos(0, 4), ViMotions.prevWordStart(b))
    }

    @Test fun motion_b_jumpsToPrevWord() {
        val b = buf("foo bar", col = 4)
        assertEquals(Pos(0, 0), ViMotions.prevWordStart(b))
    }

    @Test fun motion_e_wordEnd() {
        val b = buf("foo bar", col = 0)
        assertEquals(Pos(0, 2), ViMotions.wordEnd(b))
    }

    @Test fun motion_e_jumpsAcrossWhitespace() {
        val b = buf("foo bar", col = 2)
        assertEquals(Pos(0, 6), ViMotions.wordEnd(b))
    }

    @Test fun motion_w_count3() {
        val b = buf("a b c d e", col = 0)
        assertEquals(Pos(0, 6), ViMotions.nextWordStart(b, count = 3))
    }

    // ---------- f/F/t/T ----------

    @Test fun motion_fFindsChar() {
        val b = buf("hello world", col = 0)
        assertEquals(Pos(0, 6), ViMotions.findCharForward(b, 'w'.code, till = false))
    }

    @Test fun motion_tStopsBeforeChar() {
        val b = buf("hello world", col = 0)
        assertEquals(Pos(0, 5), ViMotions.findCharForward(b, 'w'.code, till = true))
    }

    @Test fun motion_fMiss_returnsNull() {
        assertNull(ViMotions.findCharForward(buf("hello"), 'z'.code, till = false))
    }

    @Test fun motion_FFindsBackward() {
        // "hello world", col=8 is 'r'; first 'l' searching backward is col 3.
        val b = buf("hello world", col = 8)
        assertEquals(Pos(0, 3), ViMotions.findCharBackward(b, 'l'.code, till = false))
    }

    @Test fun motion_fCount2() {
        val b = buf("a-b-c-d", col = 0)
        assertEquals(Pos(0, 3), ViMotions.findCharForward(b, '-'.code, till = false, count = 2))
    }

    // ---------- gg/G ----------

    @Test fun motion_G_atLineN() {
        val b = buf("a\nb\nc\nd")
        assertEquals(Pos(2, 0), ViMotions.gotoLine(b, 3))
    }

    @Test fun motion_gg_docStart() {
        val b = buf("  a\nb", row = 1)
        assertEquals(Pos(0, 2), ViMotions.docStart(b))
    }

    @Test fun motion_G_lastLine_docEnd() {
        val b = buf("a\nb\nc")
        assertEquals(Pos(2, 0), ViMotions.docEnd(b))
    }

    // ---------- % bracket match ----------

    @Test fun motion_pct_findsClose() {
        val b = buf("(abc)", col = 0)
        assertEquals(Pos(0, 4), ViMotions.matchBracket(b))
    }

    @Test fun motion_pct_findsOpenFromClose() {
        val b = buf("(abc)", col = 4)
        assertEquals(Pos(0, 0), ViMotions.matchBracket(b))
    }

    @Test fun motion_pct_nested() {
        val b = buf("((x))", col = 0)
        assertEquals(Pos(0, 4), ViMotions.matchBracket(b))
    }

    @Test fun motion_pct_acrossLines() {
        val b = buf("(\nabc\n)", col = 0)
        assertEquals(Pos(2, 0), ViMotions.matchBracket(b))
    }

    @Test fun motion_pct_noMatch_returnsNull() {
        val b = buf("abc", col = 0)
        assertNull(ViMotions.matchBracket(b))
    }

    // ---------- Search ----------

    @Test fun search_forwardFindsMatch() {
        val b = buf("foo\nbar\nfoo")
        assertEquals(Pos(2, 0), ViMotions.searchForward(b, "foo", 0, 0))
    }

    @Test fun search_forwardWraps() {
        val b = buf("foo\nbar")
        assertEquals(Pos(0, 0), ViMotions.searchForward(b, "foo", 0, 5))
    }

    @Test fun search_backwardFindsPrev() {
        val b = buf("foo\nbar\nfoo")
        assertEquals(Pos(0, 0), ViMotions.searchBackward(b, "foo", 2, 0))
    }

    @Test fun search_regex() {
        val b = buf("aaa1\nbbb2")
        assertEquals(Pos(0, 3), ViMotions.searchForward(b, "[0-9]", 0, 0))
    }

    @Test fun search_miss_returnsNull() {
        assertNull(ViMotions.searchForward(buf("abc"), "xyz", 0, 0))
    }

    // ---------- Edits: insert ----------

    @Test fun edit_insertCodepoint() {
        val b = buf("ab", col = 1, mode = ViMode.INSERT)
        val b2 = ViEdits.insertCodepoint(b, 'X'.code)
        assertEquals("aXb", b2.lines[0])
        assertEquals(2, b2.cursorCol)
        assertTrue(b2.dirty)
    }

    @Test fun edit_splitLine() {
        val b = buf("hello", col = 2, mode = ViMode.INSERT)
        val b2 = ViEdits.splitLine(b)
        assertEquals(listOf("he", "llo"), b2.lines)
        assertEquals(1, b2.cursorRow)
        assertEquals(0, b2.cursorCol)
    }

    @Test fun edit_backspaceInLine() {
        val b = buf("abc", col = 2, mode = ViMode.INSERT)
        val b2 = ViEdits.backspaceInsert(b)
        assertEquals("ac", b2.lines[0])
        assertEquals(1, b2.cursorCol)
    }

    @Test fun edit_backspaceAtCol0JoinsLines() {
        val b = buf("a\nb", row = 1, col = 0, mode = ViMode.INSERT)
        val b2 = ViEdits.backspaceInsert(b)
        assertEquals(listOf("ab"), b2.lines)
        assertEquals(0, b2.cursorRow)
        assertEquals(1, b2.cursorCol)
    }

    // ---------- Edits: x/X/D/C ----------

    @Test fun edit_x_deletesChar() {
        val b = buf("hello", col = 1)
        val b2 = ViEdits.deleteCharAtCursor(b)
        assertEquals("hllo", b2.lines[0])
        assertEquals(1, b2.cursorCol)
    }

    @Test fun edit_x_atLastChar_movesCursorLeft() {
        val b = buf("ab", col = 1)
        val b2 = ViEdits.deleteCharAtCursor(b)
        assertEquals("a", b2.lines[0])
        assertEquals(0, b2.cursorCol)
    }

    @Test fun edit_x_emptyLine_noop() {
        val b = buf("", col = 0)
        val b2 = ViEdits.deleteCharAtCursor(b)
        assertEquals(listOf(""), b2.lines)
    }

    @Test fun edit_X_deletesBefore() {
        val b = buf("hello", col = 2)
        val b2 = ViEdits.deleteCharBefore(b)
        assertEquals("hllo", b2.lines[0])
        assertEquals(1, b2.cursorCol)
    }

    @Test fun edit_D_deletesToEol() {
        val b = buf("hello world", col = 5)
        val b2 = ViEdits.deleteToEol(b)
        assertEquals("hello", b2.lines[0])
    }

    @Test fun edit_C_changeToEolEntersInsert() {
        val b = buf("hello", col = 2)
        val b2 = ViEdits.changeToEol(b)
        assertEquals("he", b2.lines[0])
        assertEquals(ViMode.INSERT, b2.mode)
    }

    @Test fun edit_r_replaceChar() {
        val b = buf("abc", col = 1)
        val b2 = ViEdits.replaceChar(b, 'X'.code)
        assertEquals("aXc", b2.lines[0])
    }

    @Test fun edit_tilde_toggleCase() {
        val b = buf("aBc", col = 0)
        val b2 = ViEdits.toggleCase(b, count = 3)
        assertEquals("AbC", b2.lines[0])
    }

    @Test fun edit_s_substituteCharEntersInsert() {
        val b = buf("abc", col = 1)
        val b2 = ViEdits.substituteChar(b)
        assertEquals("ac", b2.lines[0])
        assertEquals(ViMode.INSERT, b2.mode)
    }

    // ---------- Line ops dd/cc/yy ----------

    @Test fun edit_dd_deletesLine() {
        val b = buf("a\nb\nc", row = 1)
        val b2 = ViEdits.deleteLines(b)
        assertEquals(listOf("a", "c"), b2.lines)
    }

    @Test fun edit_dd_count2() {
        val b = buf("a\nb\nc\nd", row = 1)
        val b2 = ViEdits.deleteLines(b, count = 2)
        assertEquals(listOf("a", "d"), b2.lines)
    }

    @Test fun edit_dd_setsLineWiseRegister() {
        val b = buf("a\nb\nc", row = 1)
        val b2 = ViEdits.deleteLines(b)
        assertEquals(YankType.LINE, b2.registers[ViEdits.UNNAMED]?.type)
        assertEquals("b", b2.registers[ViEdits.UNNAMED]?.text)
    }

    @Test fun edit_yy_yanksLineNoEdit() {
        val b = buf("a\nb\nc", row = 1)
        val b2 = ViEdits.yankLine(b)
        assertEquals(listOf("a", "b", "c"), b2.lines)
        assertEquals("b", b2.registers[ViEdits.UNNAMED]?.text)
        assertFalse(b2.dirty)
    }

    @Test fun edit_cc_enterInsertWithEmptyLine() {
        val b = buf("a\nb\nc", row = 1)
        val b2 = ViEdits.changeLines(b)
        assertEquals(listOf("a", "", "c"), b2.lines)
        assertEquals(ViMode.INSERT, b2.mode)
    }

    // ---------- o/O ----------

    @Test fun edit_o_openBelow() {
        val b = buf("a\nb", row = 0)
        val b2 = ViEdits.openBelow(b)
        assertEquals(listOf("a", "", "b"), b2.lines)
        assertEquals(1, b2.cursorRow)
        assertEquals(ViMode.INSERT, b2.mode)
    }

    @Test fun edit_O_openAbove() {
        val b = buf("a\nb", row = 1)
        val b2 = ViEdits.openAbove(b)
        assertEquals(listOf("a", "", "b"), b2.lines)
        assertEquals(1, b2.cursorRow)
    }

    // ---------- Range ops ----------

    @Test fun edit_deleteCharRange_sameLine() {
        val b = buf("abcdef", col = 1)
        val b2 = ViEdits.deleteCharRange(b, Pos(0, 1), Pos(0, 3))
        assertEquals("aef", b2.lines[0])
        assertEquals("bcd", b2.registers[ViEdits.UNNAMED]?.text)
    }

    @Test fun edit_deleteCharRange_crossLines() {
        val b = buf("hello\nworld\nfoo", row = 0, col = 2)
        val b2 = ViEdits.deleteCharRange(b, Pos(0, 2), Pos(1, 2))
        assertEquals(listOf("held", "foo"), b2.lines)
    }

    @Test fun edit_yankCharRange() {
        val b = buf("hello", col = 0)
        val b2 = ViEdits.yankCharRange(b, Pos(0, 1), Pos(0, 3))
        assertEquals("ell", b2.registers[ViEdits.UNNAMED]?.text)
        assertEquals(YankType.CHAR, b2.registers[ViEdits.UNNAMED]?.type)
    }

    // ---------- Registers ----------

    @Test fun edit_namedRegisterStoresOnDelete() {
        val b = buf("hello", col = 0)
        val b2 =
            ViEdits.deleteCharAtCursor(b, 1).let {
                it.copy(
                    registers =
                        it.registers + ('a' to it.registers[ViEdits.UNNAMED]!!),
                )
            }
        assertEquals("h", b2.registers['a']?.text)
    }

    @Test fun edit_yankCharRange_namedRegister() {
        val b = buf("hello", col = 0)
        val b2 = ViEdits.yankCharRange(b, Pos(0, 0), Pos(0, 1), regName = 'a')
        assertEquals("he", b2.registers['a']?.text)
        assertEquals("he", b2.registers[ViEdits.UNNAMED]?.text)
    }

    // ---------- Paste ----------

    @Test fun edit_p_charwise_pastesAfter() {
        val b = buf("hello", col = 0).copy(registers = mapOf(ViEdits.UNNAMED to Register("XY", YankType.CHAR)))
        val b2 = ViEdits.pasteAfter(b)
        assertEquals("hXYello", b2.lines[0])
        assertEquals(2, b2.cursorCol)
    }

    @Test fun edit_P_charwise_pastesBefore() {
        val b = buf("hello", col = 1).copy(registers = mapOf(ViEdits.UNNAMED to Register("XY", YankType.CHAR)))
        val b2 = ViEdits.pasteBefore(b)
        assertEquals("hXYello", b2.lines[0])
    }

    @Test fun edit_p_linewise_pastesBelow() {
        val b = buf("a\nb", row = 0).copy(registers = mapOf(ViEdits.UNNAMED to Register("X", YankType.LINE)))
        val b2 = ViEdits.pasteAfter(b)
        assertEquals(listOf("a", "X", "b"), b2.lines)
        assertEquals(1, b2.cursorRow)
    }

    @Test fun edit_P_linewise_pastesAbove() {
        val b = buf("a\nb", row = 1).copy(registers = mapOf(ViEdits.UNNAMED to Register("X", YankType.LINE)))
        val b2 = ViEdits.pasteBefore(b)
        assertEquals(listOf("a", "X", "b"), b2.lines)
        assertEquals(1, b2.cursorRow)
    }

    @Test fun edit_p_linewiseMulti() {
        val b = buf("a\nb").copy(registers = mapOf(ViEdits.UNNAMED to Register("X\nY", YankType.LINE)))
        val b2 = ViEdits.pasteAfter(b)
        assertEquals(listOf("a", "X", "Y", "b"), b2.lines)
    }

    // ---------- Undo/Redo ----------

    @Test fun undoRestoresPriorState() {
        val b = buf("hello", col = 0)
        val b2 = ViEdits.deleteCharAtCursor(b)
        assertEquals("ello", b2.lines[0])
        val b3 = b2.undo()
        assertEquals("hello", b3.lines[0])
    }

    @Test fun redoReappliesUndoneEdit() {
        val b = buf("hello", col = 0)
        val b2 = ViEdits.deleteCharAtCursor(b)
        val b3 = b2.undo()
        val b4 = b3.redo()
        assertEquals("ello", b4.lines[0])
    }

    @Test fun newEditClearsRedoStack() {
        val b = buf("hello", col = 0)
        val b2 = ViEdits.deleteCharAtCursor(b)
        val b3 = b2.undo()
        val b4 = ViEdits.insertCodepoint(b3.copy(mode = ViMode.INSERT), 'Z'.code)
        assertTrue(b4.redoStack.isEmpty())
    }

    @Test fun undoMultipleSteps() {
        var b = buf("abc", col = 0)
        b = ViEdits.deleteCharAtCursor(b) // bc
        b = ViEdits.deleteCharAtCursor(b) // c
        b = b.undo()
        assertEquals("bc", b.lines[0])
        b = b.undo()
        assertEquals("abc", b.lines[0])
    }

    @Test fun undoOnEmptyStack_isNoop() {
        val b = buf("hello", col = 0)
        val b2 = b.undo()
        assertEquals(b.lines, b2.lines)
    }

    // ---------- Marks ----------

    @Test fun marks_setAndGet() {
        val b = buf("abc", col = 2).copy(marks = mapOf('a' to Mark(0, 2)))
        assertEquals(Mark(0, 2), b.marks['a'])
    }

    // ---------- Normalization ----------

    @Test fun normalized_clampsCursorToLineLen_normalMode() {
        val b = buf("hi", col = 99)
        val n = b.normalized()
        assertEquals(1, n.cursorCol)
    }

    @Test fun normalized_insertModeAllowsCursorPastLastChar() {
        val b = buf("hi", col = 2, mode = ViMode.INSERT)
        val n = b.normalized()
        assertEquals(2, n.cursorCol)
    }

    @Test fun normalized_clampsRow() {
        val b = buf("a\nb", row = 99)
        val n = b.normalized()
        assertEquals(1, n.cursorRow)
    }
}
