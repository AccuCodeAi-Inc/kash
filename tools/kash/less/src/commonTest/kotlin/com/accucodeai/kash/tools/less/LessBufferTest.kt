package com.accucodeai.kash.tools.less

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LessBufferTest {
    private fun buf(
        text: String,
        bodyHeight: Int = 10,
    ): LessBuffer = LessBuffer.fromText(text, "f", bodyHeight = bodyHeight)

    private fun nLines(n: Int): String = (1..n).joinToString("\n") { "L$it" }

    @Test fun fromText_emptyYieldsOneEmptyLine() {
        val b = LessBuffer.fromText("", null)
        assertEquals(listOf(""), b.lines)
        assertEquals(0, b.top)
    }

    @Test fun fromText_singleLineNoTrailingNewline() {
        val b = LessBuffer.fromText("hello", null)
        assertEquals(listOf("hello"), b.lines)
    }

    @Test fun fromText_trailingNewlineDropsEmptyTail() {
        val b = LessBuffer.fromText("a\nb\nc\n", null)
        assertEquals(listOf("a", "b", "c"), b.lines)
    }

    @Test fun fromText_preservesInteriorBlankLines() {
        val b = LessBuffer.fromText("a\n\nb\n", null)
        assertEquals(listOf("a", "", "b"), b.lines)
    }

    @Test fun scrollLineForward_advancesTop() {
        val b = buf(nLines(20), bodyHeight = 5).scrollLineForward()
        assertEquals(1, b.top)
    }

    @Test fun scrollLineBackward_stopsAtZero() {
        val b = buf(nLines(20), bodyHeight = 5).scrollLineBackward()
        assertEquals(0, b.top)
    }

    @Test fun scrollScreenForward_advancesByBodyHeight() {
        val b = buf(nLines(50), bodyHeight = 10).scrollScreenForward()
        assertEquals(10, b.top)
    }

    @Test fun scrollScreenBackward_decrementsByBodyHeight() {
        val b = buf(nLines(50), bodyHeight = 10).scrollScreenForward().scrollScreenForward().scrollScreenBackward()
        assertEquals(10, b.top)
    }

    @Test fun scrollHalfForward_advancesByHalf() {
        val b = buf(nLines(50), bodyHeight = 10).scrollHalfForward()
        assertEquals(5, b.top)
    }

    @Test fun scrollHalfBackward_decrementsByHalf() {
        val b = buf(nLines(50), bodyHeight = 10).scrollScreenForward().scrollHalfBackward()
        assertEquals(5, b.top)
    }

    @Test fun gotoStart_setsTopToZero() {
        val b = buf(nLines(50), bodyHeight = 10).scrollScreenForward().gotoStart()
        assertEquals(0, b.top)
    }

    @Test fun gotoEnd_putsLastScreenfulInView() {
        val b = buf(nLines(50), bodyHeight = 10).gotoEnd()
        assertEquals(40, b.top)
    }

    @Test fun gotoEnd_doesNotOvershoot_whenFileSmallerThanScreen() {
        val b = buf(nLines(3), bodyHeight = 10).gotoEnd()
        assertEquals(0, b.top)
    }

    @Test fun gotoLine_isOneIndexed() {
        val b = buf(nLines(50), bodyHeight = 10).gotoLine(15)
        assertEquals(14, b.top)
    }

    @Test fun gotoLine_clampsToValidRange() {
        val b = buf(nLines(5), bodyHeight = 10).gotoLine(99)
        assertEquals(0, b.top) // normalized: since 5 < bodyHeight, top stays at 0
    }

    @Test fun gotoLine_zeroClampsToFirstLine() {
        val b = buf(nLines(50), bodyHeight = 10).gotoLine(0)
        assertEquals(0, b.top)
    }

    @Test fun gotoPercent_halfWayLandsAroundMiddle() {
        val b = buf(nLines(100), bodyHeight = 10).gotoPercent(50)
        assertTrue(b.top in 45..55)
    }

    @Test fun gotoPercent_zeroGoesToStart() {
        val b = buf(nLines(100), bodyHeight = 10).gotoPercent(0)
        assertEquals(0, b.top)
    }

    @Test fun gotoPercent_hundredGoesToEnd() {
        val b = buf(nLines(100), bodyHeight = 10).gotoPercent(100)
        assertEquals(90, b.top)
    }

    @Test fun searchForward_findsFirstMatchAfterTop() {
        val b = buf("apple\nbanana\ncherry\napple\n", bodyHeight = 5)
        val hit = b.searchForward(Regex("apple"))
        assertEquals(3, hit) // skips current row 0; next match is row 3
    }

    @Test fun searchForward_doesNotWrap() {
        val b = buf("apple\nbanana\ncherry\n", bodyHeight = 5).copy(top = 2)
        val hit = b.searchForward(Regex("apple"))
        assertNull(hit)
    }

    @Test fun searchBackward_findsLastBeforeTop() {
        val b = buf("apple\nbanana\ncherry\napple\n", bodyHeight = 5).copy(top = 3)
        val hit = b.searchBackward(Regex("apple"))
        assertEquals(0, hit)
    }

    @Test fun searchBackward_missReturnsNull() {
        val b = buf("apple\nbanana\ncherry\n", bodyHeight = 5).copy(top = 0)
        // startRow defaults to top - 1 = -1 → empty backward range
        val hit = b.searchBackward(Regex("apple"))
        assertNull(hit)
    }

    @Test fun searchForward_regexSpecialChars() {
        val b = buf("foo.bar\nfoo bar\nfooXbar\n", bodyHeight = 5)
        // Literal "." — start at row 0 since searchForward defaults to top+1.
        val hit1 = b.searchForward(Regex("\\."), startRow = 0)
        assertEquals(0, hit1)
        // "." regex matches anything including space and 'X'
        val hit2 = b.searchForward(Regex("foo.bar"), startRow = 0)
        assertNotNull(hit2)
    }

    @Test fun searchForward_anchorsWork() {
        val b = buf("abc\nXabc\nabc\n", bodyHeight = 5).copy(top = 0)
        val hit = b.searchForward(Regex("^abc"))
        assertEquals(2, hit) // row 1 starts with X, row 2 starts with abc
    }

    @Test fun searchForward_groupsWork() {
        val b = buf("a1\nb2\nc33\n", bodyHeight = 5)
        val hit = b.searchForward(Regex("(\\d)\\1"))
        assertEquals(2, hit)
    }

    @Test fun jumpToRow_centersWhenPossible() {
        val b = buf(nLines(100), bodyHeight = 10).jumpToRow(50)
        // body/2 = 5, so top = 50 - 5 = 45
        assertEquals(45, b.top)
    }

    @Test fun jumpToRow_clampsAtTop() {
        val b = buf(nLines(100), bodyHeight = 10).jumpToRow(2)
        assertEquals(0, b.top)
    }

    @Test fun jumpToRow_clampsAtBottom() {
        val b = buf(nLines(20), bodyHeight = 10).jumpToRow(19)
        assertEquals(10, b.top)
    }

    @Test fun withBodyHeight_renormalizes() {
        val b = buf(nLines(20), bodyHeight = 5).copy(top = 18).withBodyHeight(10)
        // top would be clamped to 20-10 = 10
        assertEquals(10, b.top)
    }

    @Test fun normalized_clampsTopWhenBeyondEnd() {
        val b = buf(nLines(10), bodyHeight = 5).copy(top = 50).normalized()
        assertEquals(5, b.top)
    }

    @Test fun scrollHalfForward_minimumOne_whenBodyHeightOne() {
        val b = buf(nLines(10), bodyHeight = 1).scrollHalfForward()
        assertEquals(1, b.top)
    }

    @Test fun bodyHeightOne_coercesToOne() {
        val b = LessBuffer.fromText("a\nb\n", null, bodyHeight = 0)
        assertEquals(1, b.bodyHeight)
    }

    @Test fun totalBytes_reflectsInputSize() {
        val b = LessBuffer.fromText("hello\n", null)
        assertEquals(6L, b.totalBytes)
    }

    @Test fun scrollLineForward_withCustomCount() {
        val b = buf(nLines(50), bodyHeight = 10).scrollLineForward(7)
        assertEquals(7, b.top)
    }

    @Test fun scrollLineBackward_withCustomCount() {
        val b = buf(nLines(50), bodyHeight = 10).scrollScreenForward().scrollLineBackward(3)
        assertEquals(7, b.top)
    }

    @Test fun searchForward_startRow_explicit() {
        val b = buf("apple\nbanana\napple\n", bodyHeight = 5)
        val hit = b.searchForward(Regex("apple"), startRow = 0)
        assertEquals(0, hit)
    }
}
