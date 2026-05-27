package com.accucodeai.kash.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DifflibTest {
    @Test fun lcsEditsIdentical() {
        val edits = lcsEdits(listOf("a", "b"), listOf("a", "b"))
        assertTrue(edits.all { it is Edit.Keep })
        assertEquals(listOf("a", "b"), edits.map { it.text })
    }

    @Test fun lcsEditsPureInsert() {
        val edits = lcsEdits(listOf("a"), listOf("a", "b"))
        assertEquals(2, edits.size)
        assertTrue(edits[0] is Edit.Keep)
        assertTrue(edits[1] is Edit.Insert)
        assertEquals("b", edits[1].text)
    }

    @Test fun lcsEditsPureDelete() {
        val edits = lcsEdits(listOf("a", "b"), listOf("a"))
        assertTrue(edits[1] is Edit.Delete)
        assertEquals("b", edits[1].text)
    }

    @Test fun lcsEditsChange() {
        // "b" -> "x" renders as delete-then-insert.
        val edits = lcsEdits(listOf("a", "b", "c"), listOf("a", "x", "c"))
        val kinds = edits.map { it::class.simpleName }
        assertTrue("Delete" in kinds && "Insert" in kinds)
        assertEquals(2, edits.count { it is Edit.Keep })
    }

    @Test fun lcsEditsEmptyOld() {
        val edits = lcsEdits(emptyList(), listOf("a", "b"))
        assertTrue(edits.all { it is Edit.Insert })
    }

    @Test fun splitLinesTrailingNewline() {
        val s = splitLines("a\nb\n")
        assertEquals(listOf("a", "b"), s.lines)
        assertTrue(s.hasTrailingNewline)
    }

    @Test fun splitLinesNoTrailingNewline() {
        val s = splitLines("a\nb")
        assertEquals(listOf("a", "b"), s.lines)
        assertTrue(!s.hasTrailingNewline)
    }

    @Test fun splitLinesEmpty() {
        val s = splitLines("")
        assertEquals(emptyList(), s.lines)
        assertTrue(s.hasTrailingNewline)
    }

    @Test fun lcsMatchesAlignment() {
        val m = lcsMatches(listOf("a", "b", "c"), listOf("a", "x", "c"))
        assertEquals(listOf(0 to 0, 2 to 2), m)
    }

    @Test fun lcsMatchesEmpty() {
        assertEquals(emptyList(), lcsMatches(emptyList(), listOf("a")))
    }

    @Test fun groupHunksMergesNearbyChanges() {
        // Two single-line changes 2 lines apart, context 3 -> one hunk.
        val old = (1..10).map { "l$it" }
        val new =
            old.toMutableList().also {
                it[1] = "X"
                it[3] = "Y"
            }
        val edits = lcsEdits(old, new)
        val hunks = groupHunks(edits, 3)
        assertEquals(1, hunks.size)
    }

    @Test fun groupHunksSplitsDistantChanges() {
        val old = (1..20).map { "l$it" }
        val new =
            old.toMutableList().also {
                it[1] = "X"
                it[18] = "Y"
            }
        val edits = lcsEdits(old, new)
        val hunks = groupHunks(edits, 3)
        assertEquals(2, hunks.size)
    }

    @Test fun renderUnifiedSnapshot() {
        val out =
            renderUnifiedDiff(
                "a\nb\nc\n",
                "a\nB\nc\n",
                "old",
                "new",
            )
        assertEquals(
            "--- old\n" +
                "+++ new\n" +
                "@@ -1,3 +1,3 @@\n" +
                " a\n" +
                "-b\n" +
                "+B\n" +
                " c\n",
            out,
        )
    }

    @Test fun renderUnifiedCollapsesUnitRanges() {
        val out = renderUnifiedDiff("a\n", "b\n", "old", "new")
        assertTrue("@@ -1 +1 @@" in out, out)
    }

    @Test fun renderUnifiedKeepsUnitRangesWhenAsked() {
        val out =
            renderUnifiedDiff("a\n", "b\n", "old", "new", collapseUnitRanges = false)
        assertTrue("@@ -1,1 +1,1 @@" in out, out)
    }

    @Test fun renderUnifiedNoNewlineMarker() {
        val out = renderUnifiedDiff("a\nb", "a\nB", "old", "new")
        assertTrue("\\ No newline at end of file" in out, out)
    }

    @Test fun renderUnifiedIdenticalIsEmpty() {
        assertEquals("", renderUnifiedDiff("a\nb\n", "a\nb\n", "x", "y"))
    }

    @Test fun hunkHeaderInsertionAtStart() {
        // 0a1: inserting before line 1 -> old start collapses to 0.
        val edits = lcsEdits(emptyList(), listOf("new"))
        val h = groupHunks(edits, 3).single()
        assertEquals("@@ -0,0 +1 @@", renderUnifiedHunkHeader(h))
    }
}
