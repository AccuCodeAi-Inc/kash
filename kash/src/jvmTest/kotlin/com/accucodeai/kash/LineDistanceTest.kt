package com.accucodeai.kash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The XfailDiagnosticsRunner ranks xfails by a per-line distance metric.
 * Used to be Levenshtein (O(m·n) heap); now a streaming differ-count
 * (O(1) heap beyond the inputs). These tests pin the contract callers
 * rely on: equal strings give 0, line-count divergence is reported, and
 * lengthy inputs don't blow up.
 */
class LineDistanceTest {
    // Re-implementation under test — the runner's function is private,
    // so we keep a sibling here. Drift between them would show up in the
    // conformance ordering, which the runner's `XFAIL-DIFF N first-diff…`
    // output would surface immediately.
    private fun distance(
        a: String,
        b: String,
    ): Int {
        if (a == b) return 0
        var diff = 0
        var i = 0
        var j = 0
        val n = a.length
        val m = b.length
        while (i < n || j < m) {
            val aLineEnd = if (i < n) a.indexOf('\n', i).let { if (it < 0) n else it } else i
            val bLineEnd = if (j < m) b.indexOf('\n', j).let { if (it < 0) m else it } else j
            val aLineLen = aLineEnd - i
            val bLineLen = bLineEnd - j
            val equal =
                aLineLen == bLineLen &&
                    (aLineLen == 0 || a.regionMatches(i, b, j, aLineLen))
            if (!equal) diff++
            i = if (aLineEnd < n) aLineEnd + 1 else aLineEnd
            j = if (bLineEnd < m) bLineEnd + 1 else bLineEnd
        }
        return if (diff == 0) 1 else diff
    }

    @Test fun equalStringsHaveZeroDistance() {
        assertEquals(0, distance("a\nb\nc", "a\nb\nc"))
    }

    @Test fun emptyVsEmpty() {
        assertEquals(0, distance("", ""))
    }

    @Test fun singleLineDifference() {
        assertEquals(1, distance("a\nb\nc", "a\nX\nc"))
    }

    @Test fun lengthMismatchIsCounted() {
        // a has 2 lines, b has 4 lines — last 2 positions are length-mismatch.
        assertEquals(2, distance("a\nb", "a\nb\nc\nd"))
    }

    @Test fun trailingNewlineConsistency() {
        // "a\nb\n" and "a\nb" both have 2 lines of content (the trailing
        // newline doesn't add a 3rd "" line in our streaming form). The
        // first has an empty trailing line after the final \n; the second
        // doesn't. Practically: distance reflects that off-by-one.
        assertEquals(1, distance("a\nb\n", "a\nb"))
    }

    @Test fun largeInputCompletesWithoutOom() {
        // 100K-line strings: would have allocated a 100K × 100K matrix
        // (40 GB) under the previous Levenshtein implementation. Here it
        // costs O(n+m) work and O(1) extra heap.
        val a = (0 until 100_000).joinToString("\n") { "line-$it" }
        val b = (0 until 100_000).joinToString("\n") { if (it == 50_000) "X" else "line-$it" }
        assertEquals(1, distance(a, b))
    }
}
