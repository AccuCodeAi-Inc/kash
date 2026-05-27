package com.accucodeai.kash.tools.ed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdBufferTest {
    private fun buf(vararg lines: String): EdBuffer = EdBuffer(lines = lines.toList(), dot = lines.size)

    @Test fun emptyBufferHasZeroSize() {
        assertEquals(0, EdBuffer().size)
        assertEquals(0, EdBuffer().dot)
        assertFalse(EdBuffer().dirty)
    }

    @Test fun appendAtStart() {
        val b = EdBuffer().append(0, listOf("a", "b"))
        assertEquals(listOf("a", "b"), b.lines)
        assertEquals(2, b.dot)
        assertTrue(b.dirty)
    }

    @Test fun appendAfterLast() {
        val b = buf("x", "y").append(2, listOf("z"))
        assertEquals(listOf("x", "y", "z"), b.lines)
        assertEquals(3, b.dot)
    }

    @Test fun appendInMiddle() {
        val b = buf("a", "b", "c").append(1, listOf("X"))
        assertEquals(listOf("a", "X", "b", "c"), b.lines)
        assertEquals(2, b.dot)
    }

    @Test fun appendEmptyIsNoop() {
        val b = buf("a")
        assertEquals(b, b.append(1, emptyList()))
    }

    @Test fun deleteSingleLine() {
        val b = buf("a", "b", "c").delete(2, 2)
        assertEquals(listOf("a", "c"), b.lines)
        assertEquals(2, b.dot)
    }

    @Test fun deleteRange() {
        val b = buf("a", "b", "c", "d").delete(2, 3)
        assertEquals(listOf("a", "d"), b.lines)
        assertEquals(2, b.dot)
    }

    @Test fun deleteAll() {
        val b = buf("a", "b").delete(1, 2)
        assertEquals(emptyList(), b.lines)
        assertEquals(0, b.dot)
    }

    @Test fun deleteLast() {
        val b = buf("a", "b", "c").delete(3, 3)
        assertEquals(listOf("a", "b"), b.lines)
        assertEquals(2, b.dot)
    }

    @Test fun changeReplacesRange() {
        val b = buf("a", "b", "c").change(2, 2, listOf("X", "Y"))
        assertEquals(listOf("a", "X", "Y", "c"), b.lines)
    }

    @Test fun changeWithEmptyDeletes() {
        val b = buf("a", "b", "c").change(2, 2, emptyList())
        assertEquals(listOf("a", "c"), b.lines)
    }

    @Test fun joinTwoLines() {
        val b = buf("foo", "bar", "baz").join(1, 2)
        assertEquals(listOf("foobar", "baz"), b.lines)
        assertEquals(1, b.dot)
    }

    @Test fun joinThreeLines() {
        val b = buf("a", "b", "c").join(1, 3)
        assertEquals(listOf("abc"), b.lines)
    }

    @Test fun joinSingleIsNoop() {
        val b = buf("a", "b")
        assertEquals(b, b.join(1, 1))
    }

    @Test fun moveForward() {
        val b = buf("a", "b", "c", "d").move(1, 1, 3)
        // move line 1 to after line 3 -> b c a d
        assertEquals(listOf("b", "c", "a", "d"), b.lines)
    }

    @Test fun moveBackward() {
        val b = buf("a", "b", "c", "d").move(3, 3, 1)
        // move line 3 to after line 1 -> a c b d
        assertEquals(listOf("a", "c", "b", "d"), b.lines)
    }

    @Test fun moveRange() {
        val b = buf("a", "b", "c", "d", "e").move(2, 3, 4)
        // move 2,3 (b,c) to after 4 -> a d b c e
        assertEquals(listOf("a", "d", "b", "c", "e"), b.lines)
    }

    @Test fun transferCopiesLines() {
        val b = buf("a", "b").transfer(1, 1, 2)
        assertEquals(listOf("a", "b", "a"), b.lines)
        assertEquals(3, b.dot)
    }

    @Test fun transferRange() {
        val b = buf("a", "b", "c").transfer(1, 2, 3)
        assertEquals(listOf("a", "b", "c", "a", "b"), b.lines)
    }

    @Test fun setMark() {
        val b = buf("a", "b").setMark('x', 2)
        assertEquals(2, b.marks['x'])
    }

    @Test fun marksShiftOnAppend() {
        val b = buf("a", "b", "c").setMark('m', 3).append(1, listOf("X"))
        assertEquals(4, b.marks['m'])
    }

    @Test fun marksShiftOnDelete() {
        val b = buf("a", "b", "c", "d").setMark('m', 4).delete(2, 2)
        assertEquals(3, b.marks['m'])
    }

    @Test fun marksRemovedOnDelete() {
        val b = buf("a", "b", "c").setMark('m', 2).delete(2, 2)
        assertFalse(b.marks.containsKey('m'))
    }

    @Test fun marksUnaffectedBelowDelete() {
        val b = buf("a", "b", "c").setMark('m', 1).delete(2, 2)
        assertEquals(1, b.marks['m'])
    }

    @Test fun dirtyFlagSet() {
        assertTrue(buf("a").delete(1, 1).dirty)
        assertTrue(buf("a").append(1, listOf("b")).dirty)
        assertTrue(buf("a", "b").join(1, 2).dirty)
    }

    @Test fun cleanResetsDirty() {
        assertFalse(buf("a").append(1, listOf("b")).clean().dirty)
    }

    @Test fun withFilename() {
        assertEquals("foo.txt", buf("a").withFilename("foo.txt").filename)
    }

    @Test fun setDot() {
        assertEquals(2, buf("a", "b").setDot(2).dot)
    }

    @Test fun line1Indexed() {
        assertEquals("b", buf("a", "b", "c").line(2))
    }

    @Test fun substituteRegex() {
        // Test through EdRegex
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        assertEquals("baz", EdRegex.substituteOne(m, "baz"))
    }

    @Test fun substituteAmp() {
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        assertEquals("[foo]", EdRegex.substituteOne(m, "[&]"))
    }

    @Test fun substituteEscapedAmp() {
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        assertEquals("&", EdRegex.substituteOne(m, "\\&"))
    }

    @Test fun substituteBackref() {
        val regex = EdRegex.compile("\\(f\\)\\(oo\\)")
        val m = regex.find("foobar")!!
        assertEquals("oof", EdRegex.substituteOne(m, "\\2\\1"))
    }

    @Test fun substituteEscapedBackslash() {
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        assertEquals("\\", EdRegex.substituteOne(m, "\\\\"))
    }

    @Test fun substituteNewline() {
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        assertEquals("a\nb", EdRegex.substituteOne(m, "a\\nb"))
    }

    @Test fun regexBreParens() {
        // BRE: \(...\) = capture
        val re = EdRegex.translate("\\(foo\\)")
        assertEquals("(foo)", re)
    }

    @Test fun regexBreLiteralParens() {
        // BRE: ( and ) are literal
        val re = EdRegex.translate("(x)")
        assertEquals("\\(x\\)", re)
    }

    @Test fun regexBreAlternation() {
        // BRE: | is literal
        val re = EdRegex.translate("a|b")
        assertEquals("a\\|b", re)
    }

    @Test fun regexBreEscapedAlternation() {
        // BRE: \| can vary, but ERE | means alternation
        val re = EdRegex.translate("a\\|b")
        assertEquals("a|b", re)
    }

    @Test fun regexBreStar() {
        // * is the same in BRE
        val re = EdRegex.translate("a*")
        assertEquals("a*", re)
    }

    @Test fun regexBreAnchors() {
        assertEquals("^foo$", EdRegex.translate("^foo$"))
    }

    @Test fun regexBreCharClass() {
        assertEquals("[abc]", EdRegex.translate("[abc]"))
    }

    @Test fun regexBreCharClassWithBracketMeta() {
        // ( inside [] is literal
        assertEquals("[()]", EdRegex.translate("[()]"))
    }

    @Test fun regexBreWordBoundary() {
        assertEquals("\\bfoo\\b", EdRegex.translate("\\<foo\\>"))
    }

    @Test fun appendKeepsDot() {
        val b = EdBuffer().append(0, listOf("a", "b", "c"))
        assertEquals(3, b.dot)
    }

    @Test fun deleteUpdatesDotToFromOrLast() {
        val b = buf("a", "b", "c").delete(2, 3)
        // After deleting 2,3 from a,b,c -> just [a]; dot should be min(from, size) = 1
        assertEquals(1, b.dot)
    }

    @Test fun changeKeepsLeading() {
        val b = buf("a", "b", "c").change(2, 3, listOf("Y"))
        assertEquals(listOf("a", "Y"), b.lines)
    }

    @Test fun joinPreservesContent() {
        val b = buf("foo", "bar").join(1, 2)
        assertEquals("foobar", b.line(1))
    }

    @Test fun moveDoesNotChangeSize() {
        val b = buf("a", "b", "c", "d")
        assertEquals(4, b.move(1, 2, 4).size)
    }

    @Test fun transferIncreasesSize() {
        val b = buf("a", "b").transfer(1, 2, 2)
        assertEquals(4, b.size)
    }

    @Test fun regexEscapedPlus() {
        // ERE: + is repetition; BRE: + is literal; \+ in BRE is repetition.
        // Our translator treats + as literal needing escape, and \+ as ERE +.
        assertEquals("a\\+", EdRegex.translate("a+"))
        assertEquals("a+", EdRegex.translate("a\\+"))
    }

    @Test fun regexBackref1Through9() {
        // \1..\9 carry through
        assertEquals("\\1", EdRegex.translate("\\1"))
    }

    @Test fun substituteUnknownBackrefIsEmpty() {
        val regex = EdRegex.compile("foo")
        val m = regex.find("foobar")!!
        // No groups, so \1 is empty.
        assertEquals("", EdRegex.substituteOne(m, "\\1"))
    }

    @Test fun deleteAllResetsDotZero() {
        val b = buf("only").delete(1, 1)
        assertEquals(0, b.dot)
        assertEquals(0, b.size)
    }

    @Test fun appendAfterDeleteAll() {
        val b = buf("only").delete(1, 1).append(0, listOf("a", "b"))
        assertEquals(listOf("a", "b"), b.lines)
        assertEquals(2, b.dot)
    }
}
