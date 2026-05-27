package com.accucodeai.kash.tools.patch

/**
 * Apply [hunks] to [original] (already split into lines). Exact context:
 * each hunk's [Hunk.oldLines] must match the file verbatim. We first try
 * the hunk's stated [Hunk.oldStart] (1-based), then scan the whole file
 * for the unique location of the old block — this absorbs benign line-
 * number drift from earlier hunks without doing fuzzy matching.
 *
 * TODO: fuzz factor / offset search (GNU patch's `--fuzz`) is not
 * implemented; only exact-context matches succeed.
 */
internal fun applyHunks(
    original: List<String>,
    hunks: List<Hunk>,
    reverse: Boolean,
    hadTrailingNewline: Boolean,
): ApplyResult {
    val work = original.toMutableList()
    val failed = mutableListOf<Int>()
    // Offset accumulated from prior applied hunks shifts later guesses.
    var offset = 0
    hunks.forEachIndexed { idx, raw ->
        val hunk = if (reverse) Hunk(raw.oldStart, raw.newLines, raw.oldLines) else raw
        val applied = tryApply(work, hunk, offset)
        if (applied) {
            offset += (hunk.newLines.size - hunk.oldLines.size)
        } else {
            failed += idx + 1
        }
    }
    return ApplyResult(work, failed, hadTrailingNewline)
}

/** Attempt to apply one [hunk] to [work] in place; returns whether it matched. */
private fun tryApply(
    work: MutableList<String>,
    hunk: Hunk,
    offset: Int,
): Boolean {
    val old = hunk.oldLines
    if (old.isEmpty()) {
        // Pure insertion. Anchor at oldStart (1-based) + offset, clamped.
        val at = ((hunk.oldStart - 1) + offset).coerceIn(0, work.size)
        work.addAll(at, hunk.newLines)
        return true
    }
    // Preferred location from the header, adjusted by running offset.
    val guess = ((hunk.oldStart - 1) + offset).coerceIn(0, work.size)
    val candidates = orderedCandidates(work.size - old.size, guess)
    for (start in candidates) {
        if (start < 0 || start + old.size > work.size) continue
        if (matchesAt(work, old, start)) {
            repeat(old.size) { work.removeAt(start) }
            work.addAll(start, hunk.newLines)
            return true
        }
    }
    return false
}

/** Candidate start positions: the guess first, then expanding outward. */
private fun orderedCandidates(
    maxStart: Int,
    guess: Int,
): Sequence<Int> =
    sequence {
        if (maxStart < 0) return@sequence
        val g = guess.coerceIn(0, maxStart)
        yield(g)
        var d = 1
        while (g - d >= 0 || g + d <= maxStart) {
            if (g + d <= maxStart) yield(g + d)
            if (g - d >= 0) yield(g - d)
            d++
        }
    }

private fun matchesAt(
    work: List<String>,
    old: List<String>,
    start: Int,
): Boolean {
    for (k in old.indices) {
        if (work[start + k] != old[k]) return false
    }
    return true
}
