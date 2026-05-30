package com.accucodeai.kash.tools.git.plumbing

import kotlin.math.max

/**
 * Per-file line-count summary suitable for `--stat` / `--shortstat` /
 * `--numstat` rendering. Mirrors the numbers git emits: insertions and
 * deletions are the raw counts of `+`/`-` lines in the unified-diff edit
 * script; binary files don't get a count. [oldSize]/[newSize] are the raw
 * byte sizes, used by `--stat`'s `Bin <a> -> <b> bytes` line.
 */
public data class DiffStatEntry(
    val path: String,
    val insertions: Int,
    val deletions: Int,
    val binary: Boolean = false,
    val oldSize: Int = 0,
    val newSize: Int = 0,
)

/** Count `+` and `-` lines in the LCS edit script over two byte sources. */
public fun diffStat(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    path: String,
    ws: DiffWhitespace = DiffWhitespace(),
): DiffStatEntry {
    if (oldBytes.contentEquals(newBytes)) {
        return DiffStatEntry(path, 0, 0, oldSize = oldBytes.size, newSize = newBytes.size)
    }
    // Heuristic: if either side has a NUL byte in the first 8000 bytes,
    // treat as binary (matches git's binary-detection threshold loosely).
    val sniff = minOf(8000, maxOf(oldBytes.size, newBytes.size))
    val oldHasNul = sniff > 0 && oldBytes.take(minOf(sniff, oldBytes.size)).any { it == 0.toByte() }
    val newHasNul = sniff > 0 && newBytes.take(minOf(sniff, newBytes.size)).any { it == 0.toByte() }
    if (oldHasNul || newHasNul) {
        return DiffStatEntry(path, 0, 0, binary = true, oldSize = oldBytes.size, newSize = newBytes.size)
    }
    val patch = unifiedDiff(oldBytes, newBytes, "a/$path", "b/$path", contextLines = 3, ws = ws)
    var ins = 0
    var del = 0
    for (line in patch.lineSequence()) {
        if (line.startsWith("+++") || line.startsWith("---")) continue
        if (line.startsWith("+")) {
            ins++
        } else if (line.startsWith("-")) {
            del++
        }
    }
    return DiffStatEntry(path, ins, del, binary = false, oldSize = oldBytes.size, newSize = newBytes.size)
}

/**
 * Render entries in git's `--numstat` form: `<added>\t<deleted>\t<path>`
 * per line, with `-\t-\t<path>` for binary files.
 */
public fun renderNumStat(entries: List<DiffStatEntry>): String {
    val sb = StringBuilder()
    for (e in entries) {
        if (e.binary) {
            sb.append("-\t-\t").append(e.path).append('\n')
        } else {
            sb
                .append(e.insertions)
                .append('\t')
                .append(e.deletions)
                .append('\t')
                .append(e.path)
                .append('\n')
        }
    }
    return sb.toString()
}

/**
 * Render a list of [DiffStatEntry] in real-git's `--stat` form:
 * ```
 *  path/to/file     | NN ++++----
 *  N files changed, X insertions(+), Y deletions(-)
 * ```
 * [width] is git's `--stat=<width>` (default 80). The path column is
 * padded to the longest path; the graph column is scaled so the line fits
 * within [width].
 */
public fun renderStat(
    entries: List<DiffStatEntry>,
    width: Int = 80,
): String {
    if (entries.isEmpty()) return ""
    val maxPath = entries.maxOf { it.path.length }
    // Count column is as wide as the largest line-count (binary files use
    // their byte-delta description and don't participate).
    val maxCount = entries.filter { !it.binary }.maxOfOrNull { it.insertions + it.deletions } ?: 0
    val countWidth = maxCount.toString().length
    // Graph budget: leading space + name + " | " + count + space + graph.
    // Empirically `graph = width - maxPath - countWidth - 6`, with a floor.
    val graphBudget = max(width - maxPath - countWidth - 6, MIN_GRAPH)
    val maxChanges = maxCount
    val sb = StringBuilder()
    for (e in entries) {
        sb.append(' ').append(e.path.padEnd(maxPath)).append(" | ")
        if (e.binary) {
            sb
                .append("Bin ")
                .append(e.oldSize)
                .append(" -> ")
                .append(e.newSize)
                .append(" bytes")
        } else {
            val total = e.insertions + e.deletions
            sb.append(total.toString().padStart(countWidth))
            sb.append(' ')
            if (maxChanges > 0) {
                val (plus, minus) = scaleBar(e.insertions, e.deletions, total, graphBudget, maxChanges)
                repeat(plus) { sb.append('+') }
                repeat(minus) { sb.append('-') }
            }
        }
        sb.append('\n')
    }
    sb.append(summaryLine(entries))
    return sb.toString()
}

/**
 * git's graph scaling. When the total change count fits the graph budget,
 * each `+`/`-` is one column (1:1). Otherwise the bar is scaled down so
 * `plus + minus <= budget`, with non-zero counts rounding up to at least 1.
 */
private fun scaleBar(
    insertions: Int,
    deletions: Int,
    total: Int,
    budget: Int,
    maxChanges: Int,
): Pair<Int, Int> {
    if (total == 0) return 0 to 0
    if (maxChanges <= budget) {
        // 1:1 mapping (no file exceeds budget).
        return insertions to deletions
    }
    val scale = budget.toDouble() / maxChanges
    var plus = (insertions * scale).toInt()
    var minus = (deletions * scale).toInt()
    if (insertions > 0 && plus == 0) plus = 1
    if (deletions > 0 && minus == 0) minus = 1
    return plus to minus
}

/** Short form: ` N files changed, X insertions(+), Y deletions(-)`. */
public fun renderShortStat(entries: List<DiffStatEntry>): String {
    if (entries.isEmpty()) return ""
    return summaryLine(entries)
}

private fun summaryLine(entries: List<DiffStatEntry>): String {
    val files = entries.size
    val ins = entries.sumOf { it.insertions }
    val del = entries.sumOf { it.deletions }
    val sb = StringBuilder()
    sb
        .append(' ')
        .append(files)
        .append(" file")
        .append(if (files == 1) "" else "s")
        .append(" changed")
    if (ins > 0) {
        sb
            .append(", ")
            .append(ins)
            .append(" insertion")
            .append(if (ins == 1) "" else "s")
            .append("(+)")
    }
    if (del > 0) {
        sb
            .append(", ")
            .append(del)
            .append(" deletion")
            .append(if (del == 1) "" else "s")
            .append("(-)")
    }
    sb.append('\n')
    return sb.toString()
}

private const val MIN_GRAPH = 5
