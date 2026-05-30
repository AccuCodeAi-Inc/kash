package com.accucodeai.kash.tools.git.plumbing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffWhitespaceStatTest {
    private fun bytes(s: String) = s.encodeToByteArray()

    @Test fun ignoreAllSpaceSuppressesWhitespaceOnlyChange() {
        val ws = DiffWhitespace(ignoreAllSpace = true)
        val out =
            unifiedDiff(
                bytes("a b c\nkeep\n"),
                bytes("a    b   c\nkeep\n"),
                "a/f",
                "b/f",
                3,
                ws,
            )
        assertEquals("", out, "whitespace-only diff should be empty under -w")
        assertTrue(whitespaceEqual(bytes("a b\n"), bytes("ab\n"), ws))
    }

    @Test fun ignoreSpaceChangeCollapsesRuns() {
        val ws = DiffWhitespace(ignoreSpaceChange = true)
        assertTrue(whitespaceEqual(bytes("a  b\n"), bytes("a b\n"), ws))
        // Adding a space where there was none is still a difference for -b.
        assertFalse(whitespaceEqual(bytes("ab\n"), bytes("a b\n"), ws))
    }

    @Test fun ignoreSpaceAtEol() {
        val ws = DiffWhitespace(ignoreSpaceAtEol = true)
        assertTrue(whitespaceEqual(bytes("x   \n"), bytes("x\n"), ws))
        assertFalse(whitespaceEqual(bytes("x y\n"), bytes("xy\n"), ws))
    }

    @Test fun ignoreBlankLines() {
        val ws = DiffWhitespace(ignoreBlankLines = true)
        assertTrue(whitespaceEqual(bytes("a\n\nb\n"), bytes("a\nb\n"), ws))
    }

    @Test fun numStatBasic() {
        val e = diffStat(bytes("a\nb\nc\n"), bytes("a\nX\nc\nd\n"), "f")
        assertEquals(2, e.insertions)
        assertEquals(1, e.deletions)
        assertEquals(renderNumStat(listOf(e)), "2\t1\tf\n")
    }

    @Test fun numStatBinary() {
        val e = diffStat(byteArrayOf(0, 1, 2), byteArrayOf(0, 1, 2, 3), "b")
        assertTrue(e.binary)
        assertEquals(renderNumStat(listOf(e)), "-\t-\tb\n")
    }

    @Test fun statDefaultWidthOneToOne() {
        // Small change: graph is 1:1 with counts.
        val e = diffStat(bytes("a\n"), bytes("a\nb\nc\nd\n"), "f")
        val rendered = renderStat(listOf(e), 80)
        // 3 insertions -> "+++"
        assertTrue(rendered.contains("f | 3 +++"), "got: $rendered")
        assertTrue(rendered.contains("1 file changed, 3 insertions(+)"), "got: $rendered")
    }

    @Test fun statBinaryLine() {
        val e = diffStat(byteArrayOf(0, 1, 2, 3, 4, 5), byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), "bin")
        val rendered = renderStat(listOf(e), 80)
        assertTrue(rendered.contains("bin | Bin 6 -> 11 bytes"), "got: $rendered")
    }

    @Test fun shortStatPluralization() {
        val e1 = diffStat(bytes("a\n"), bytes("a\nb\n"), "f1")
        assertEquals(" 1 file changed, 1 insertion(+)\n", renderShortStat(listOf(e1)))
        val e2 = diffStat(bytes("a\nb\n"), bytes("a\n"), "f2")
        assertEquals(" 1 file changed, 1 deletion(-)\n", renderShortStat(listOf(e2)))
    }
}
