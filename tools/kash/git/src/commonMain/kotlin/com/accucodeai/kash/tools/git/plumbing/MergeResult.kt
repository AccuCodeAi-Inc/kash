package com.accucodeai.kash.tools.git.plumbing

/**
 * Line-level 3-way merge. Algorithm:
 *  1. Diff base→ours and base→theirs separately. Each diff produces
 *     a sequence of *hunks* — `(baseStart, baseEndExclusive, replacement)`.
 *  2. Walk base's lines, advancing through both hunk sequences in
 *     parallel. The next anchor is `min(nextOursHunkStart,
 *     nextTheirsHunkStart, baseEnd)`. The base region up to that
 *     anchor is copied through.
 *  3. At an anchor:
 *     - Only ours has a hunk here → apply ours.
 *     - Only theirs has a hunk here → apply theirs.
 *     - Both have hunks that don't overlap (one strictly before the
 *       other) → apply earlier one, re-examine.
 *     - Both have hunks that overlap on the same base region:
 *         - if their replacements are equal → emit once.
 *         - else → conflict block.
 *
 * Output:
 *  - [MergeResult.Clean] — no conflicts; [lines] is the merged content.
 *  - [MergeResult.Conflict] — at least one conflict block; [lines] is
 *    the working-tree content with `<<<<<<<` / `=======` / `>>>>>>>`
 *    markers. `merge.conflictStyle=diff3` switches in the
 *    `|||||||` base block — pass [includeBaseInMarkers] = true.
 */
public sealed interface MergeResult {
    public val lines: List<String>

    public data class Clean(
        override val lines: List<String>,
    ) : MergeResult

    public data class Conflict(
        override val lines: List<String>,
        val count: Int,
    ) : MergeResult
}

public fun mergeLines(
    base: List<String>,
    ours: List<String>,
    theirs: List<String>,
    oursLabel: String = "HEAD",
    theirsLabel: String = "theirs",
    baseLabel: String = "base",
    includeBaseInMarkers: Boolean = false,
): MergeResult {
    val oursHunks = diffHunks(base, ours)
    val theirsHunks = diffHunks(base, theirs)

    val out = mutableListOf<String>()
    var conflicts = 0
    var basePos = 0
    var oI = 0
    var tI = 0

    while (basePos <= base.size) {
        val oNext = oursHunks.getOrNull(oI)
        val tNext = theirsHunks.getOrNull(tI)
        // Where's the next event?
        val nextEvent =
            listOfNotNull(oNext?.baseStart, tNext?.baseStart).minOrNull() ?: base.size
        // Copy unchanged base region [basePos, nextEvent).
        for (i in basePos until nextEvent) out += base[i]
        basePos = nextEvent
        if (basePos == base.size && oNext == null && tNext == null) break

        val oHere = oNext != null && oNext.baseStart == basePos
        val tHere = tNext != null && tNext.baseStart == basePos

        when {
            oHere && !tHere -> {
                out += oNext.replacement
                basePos = oNext.baseEnd
                oI++
            }

            tHere && !oHere -> {
                out += tNext.replacement
                basePos = tNext.baseEnd
                tI++
            }

            oHere && tHere -> {
                // Same base range or overlap?
                val sameRange = oNext.baseStart == tNext.baseStart && oNext.baseEnd == tNext.baseEnd
                val sameReplacement = oNext.replacement == tNext.replacement
                if (sameRange && sameReplacement) {
                    out += oNext.replacement
                    basePos = oNext.baseEnd
                    oI++
                    tI++
                } else if (oNext.baseEnd <= tNext.baseStart) {
                    // ours fully precedes theirs (rare since starts equal,
                    // but guard the math).
                    out += oNext.replacement
                    basePos = oNext.baseEnd
                    oI++
                } else if (tNext.baseEnd <= oNext.baseStart) {
                    out += tNext.replacement
                    basePos = tNext.baseEnd
                    tI++
                } else {
                    // Overlapping hunks → conflict block.
                    val mergedBaseEnd = maxOf(oNext.baseEnd, tNext.baseEnd)
                    val oursPart = mutableListOf<String>()
                    oursPart += oNext.replacement
                    if (oNext.baseEnd < mergedBaseEnd) {
                        for (i in oNext.baseEnd until mergedBaseEnd) oursPart += base[i]
                    }
                    val theirsPart = mutableListOf<String>()
                    theirsPart += tNext.replacement
                    if (tNext.baseEnd < mergedBaseEnd) {
                        for (i in tNext.baseEnd until mergedBaseEnd) theirsPart += base[i]
                    }
                    out += "<<<<<<< $oursLabel"
                    out += oursPart
                    if (includeBaseInMarkers) {
                        out += "||||||| $baseLabel"
                        for (i in basePos until mergedBaseEnd) out += base[i]
                    }
                    out += "======="
                    out += theirsPart
                    out += ">>>>>>> $theirsLabel"
                    conflicts++
                    basePos = mergedBaseEnd
                    oI++
                    tI++
                }
            }

            else -> {
                // No hunk here — should be caught by the copy above.
                if (basePos < base.size) {
                    out += base[basePos]
                    basePos++
                } else {
                    break
                }
            }
        }
    }

    return if (conflicts == 0) MergeResult.Clean(out) else MergeResult.Conflict(out, conflicts)
}

/**
 * Compute a list of `(baseStart, baseEnd, replacementLines)` hunks
 * representing the diff between [base] and [target]. Adjacent
 * non-keep edits are merged into a single hunk.
 */
private fun diffHunks(
    base: List<String>,
    target: List<String>,
): List<MergeHunk> {
    val n = base.size
    val m = target.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] =
                if (base[i] == target[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
        }
    }
    val out = mutableListOf<MergeHunk>()
    var i = 0
    var j = 0
    var pendingStart = -1
    val pendingReplacement = mutableListOf<String>()

    fun flush(pendingEnd: Int) {
        if (pendingStart >= 0) {
            out += MergeHunk(pendingStart, pendingEnd, pendingReplacement.toList())
            pendingStart = -1
            pendingReplacement.clear()
        }
    }

    while (i < n && j < m) {
        if (base[i] == target[j]) {
            flush(i)
            i++
            j++
        } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
            // delete base[i]
            if (pendingStart < 0) pendingStart = i
            i++
        } else {
            // insert target[j]
            if (pendingStart < 0) pendingStart = i
            pendingReplacement += target[j]
            j++
        }
    }
    while (i < n) {
        if (pendingStart < 0) pendingStart = i
        i++
    }
    while (j < m) {
        if (pendingStart < 0) pendingStart = i
        pendingReplacement += target[j]
        j++
    }
    flush(n)
    return out
}

private data class MergeHunk(
    val baseStart: Int,
    val baseEnd: Int,
    val replacement: List<String>,
)
