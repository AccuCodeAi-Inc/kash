package com.accucodeai.kash.tools.git.plumbing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThreeWayMergeTest {
    private fun lines(vararg ls: String): List<String> = ls.toList()

    @Test fun identicalIsClean() {
        val r = mergeLines(lines("a", "b"), lines("a", "b"), lines("a", "b"))
        assertTrue(r is MergeResult.Clean)
        assertEquals(lines("a", "b"), r.lines)
    }

    @Test fun onlyOursChanged() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "B", "c"),
                theirs = lines("a", "b", "c"),
            )
        assertTrue(r is MergeResult.Clean, r.toString())
        assertEquals(lines("a", "B", "c"), r.lines)
    }

    @Test fun onlyTheirsChanged() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "b", "c"),
                theirs = lines("a", "B", "c"),
            )
        assertTrue(r is MergeResult.Clean)
        assertEquals(lines("a", "B", "c"), r.lines)
    }

    @Test fun nonOverlappingEditsBothApplied() {
        val r =
            mergeLines(
                base = lines("a", "b", "c", "d", "e"),
                ours = lines("a", "B", "c", "d", "e"),
                theirs = lines("a", "b", "c", "D", "e"),
            )
        assertTrue(r is MergeResult.Clean, r.lines.joinToString("\n"))
        assertEquals(lines("a", "B", "c", "D", "e"), r.lines)
    }

    @Test fun bothChangedIdenticallyMergesCleanly() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "X", "c"),
                theirs = lines("a", "X", "c"),
            )
        assertTrue(r is MergeResult.Clean)
        assertEquals(lines("a", "X", "c"), r.lines)
    }

    @Test fun conflictingEditsProduceMarkers() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "OURS", "c"),
                theirs = lines("a", "THEIRS", "c"),
            )
        assertTrue(r is MergeResult.Conflict)
        assertEquals(1, r.count)
        val joined = r.lines.joinToString("\n")
        assertTrue(joined.contains("<<<<<<< HEAD"), joined)
        assertTrue(joined.contains("OURS"), joined)
        assertTrue(joined.contains("======="), joined)
        assertTrue(joined.contains("THEIRS"), joined)
        assertTrue(joined.contains(">>>>>>> theirs"), joined)
    }

    @Test fun diff3StyleIncludesBaseSection() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "OURS", "c"),
                theirs = lines("a", "THEIRS", "c"),
                includeBaseInMarkers = true,
            ) as MergeResult.Conflict
        val joined = r.lines.joinToString("\n")
        assertTrue(joined.contains("||||||| base"), joined)
        assertTrue(joined.contains("\nb\n=======") || joined.contains("base\nb"), joined)
    }

    @Test fun additionsOnBothSidesNonOverlapping() {
        val r =
            mergeLines(
                base = lines("a", "b"),
                ours = lines("a", "b", "ours-add"),
                theirs = lines("theirs-add", "a", "b"),
            )
        // Both sides added at different ends — should be clean.
        assertTrue(r is MergeResult.Clean, r.lines.joinToString("|"))
        assertEquals(lines("theirs-add", "a", "b", "ours-add"), r.lines)
    }

    @Test fun deleteOnOneSideTakeItButPreserveOtherChanges() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "c"),
                theirs = lines("a", "b", "c"),
            )
        assertTrue(r is MergeResult.Clean)
        assertEquals(lines("a", "c"), r.lines)
    }

    @Test fun deleteVsModifyConflicts() {
        val r =
            mergeLines(
                base = lines("a", "b", "c"),
                ours = lines("a", "c"),
                theirs = lines("a", "B", "c"),
            )
        assertTrue(r is MergeResult.Conflict, r.lines.joinToString("\n"))
    }
}
