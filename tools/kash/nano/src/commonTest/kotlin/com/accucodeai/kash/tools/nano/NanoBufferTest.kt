package com.accucodeai.kash.tools.nano

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NanoBufferTest {
    private fun buf(
        text: String,
        row: Int = 0,
        col: Int = 0,
    ): NanoBuffer = NanoBuffer.fromText(text, "x").copy(cursorRow = row, cursorCol = col)

    @Test fun fromText_singleLineNoNewline() {
        val b = NanoBuffer.fromText("hello", "f")
        assertEquals(listOf("hello"), b.lines)
        assertFalse(b.dirty)
    }

    @Test fun fromText_trailingNewlineDropsEmpty() {
        val b = NanoBuffer.fromText("a\nb\n", "f")
        assertEquals(listOf("a", "b"), b.lines)
    }

    @Test fun fromText_emptyYieldsOneLine() {
        val b = NanoBuffer.fromText("", "f")
        assertEquals(listOf(""), b.lines)
    }

    @Test fun insertCodepoint_advancesCursorAndMarksDirty() {
        val b = buf("ab", col = 1).insertCodepoint('X'.code)
        assertEquals("aXb", b.lines[0])
        assertEquals(2, b.cursorCol)
        assertTrue(b.dirty)
    }

    @Test fun splitLine_separatesAtCursor() {
        val b = buf("hello", col = 2).splitLine()
        assertEquals(listOf("he", "llo"), b.lines)
        assertEquals(1, b.cursorRow)
        assertEquals(0, b.cursorCol)
    }

    @Test fun backspace_atColZeroJoinsWithPrior() {
        val b = buf("a\nb", row = 1, col = 0).backspace()
        assertEquals(listOf("ab"), b.lines)
        assertEquals(0, b.cursorRow)
        assertEquals(1, b.cursorCol)
    }

    @Test fun backspace_inLineDeletesPriorChar() {
        val b = buf("abc", col = 2).backspace()
        assertEquals("ac", b.lines[0])
        assertEquals(1, b.cursorCol)
    }

    @Test fun deleteForward_atEolJoinsWithNext() {
        val b = buf("a\nb", row = 0, col = 1).deleteForward()
        assertEquals(listOf("ab"), b.lines)
    }

    @Test fun cutLine_removesLineAndReturnsContent() {
        val (next, cut) = buf("a\nb\nc", row = 1).cutLine()
        assertEquals("b", cut)
        assertEquals(listOf("a", "c"), next.lines)
        assertEquals(1, next.cursorRow)
    }

    @Test fun pasteLineAbove_insertsAtCursorRow() {
        val b = buf("a\nb", row = 1).pasteLineAbove("X")
        assertEquals(listOf("a", "X", "b"), b.lines)
        assertEquals(2, b.cursorRow)
    }

    @Test fun findNext_findsForwardThenWraps() {
        val b = buf("foo\nbar\nfoo", row = 0, col = 0)
        // First match wraps around current line if no later match found —
        // here "foo" sits at row 0 col 0 and row 2 col 0; from (0,0) we
        // search from col 1 onward in row 0 (no hit), then row 1 (no hit),
        // then row 2 (hit).
        assertEquals(2 to 0, b.findNext("foo"))
        // Searching from (2, 0) wraps to (0, 0).
        val b2 = b.copy(cursorRow = 2, cursorCol = 0)
        assertEquals(0 to 0, b2.findNext("foo"))
    }

    @Test fun findNext_missReturnsNull() {
        assertNull(buf("foo\nbar").findNext("baz"))
    }

    @Test fun moveLeft_wrapsToPriorLineEnd() {
        val b = buf("ab\ncd", row = 1, col = 0).moveLeft()
        assertEquals(0, b.cursorRow)
        assertEquals(2, b.cursorCol)
    }

    @Test fun moveRight_atEOLWrapsToNextLineStart() {
        val b = buf("ab\ncd", row = 0, col = 2).moveRight()
        assertEquals(1, b.cursorRow)
        assertEquals(0, b.cursorCol)
    }

    @Test fun moveDown_clampsColumnToLineLength() {
        val b = buf("longer\nx", row = 0, col = 6).moveDown()
        assertEquals(1, b.cursorRow)
        assertEquals(1, b.cursorCol)
    }
}
