package com.accucodeai.kash.tools.git.plumbing

/**
 * Per-file line-count summary suitable for `--stat` / `--shortstat`
 * rendering. Mirrors the numbers git emits: insertions and deletions
 * are the raw counts of `+`/`-` lines in the unified-diff edit script;
 * binary files don't get a count.
 */
public data class DiffStatEntry(
    val path: String,
    val insertions: Int,
    val deletions: Int,
    val binary: Boolean = false,
)

/** Count `+` and `-` lines in the LCS edit script over two byte sources. */
public fun diffStat(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    path: String,
): DiffStatEntry {
    if (oldBytes.contentEquals(newBytes)) return DiffStatEntry(path, 0, 0)
    // Heuristic: if either side has a NUL byte in the first 8000 bytes,
    // treat as binary (matches git's binary-detection threshold loosely).
    val sniff = minOf(8000, maxOf(oldBytes.size, newBytes.size))
    val oldHasNul = sniff > 0 && oldBytes.take(minOf(sniff, oldBytes.size)).any { it == 0.toByte() }
    val newHasNul = sniff > 0 && newBytes.take(minOf(sniff, newBytes.size)).any { it == 0.toByte() }
    if (oldHasNul || newHasNul) return DiffStatEntry(path, 0, 0, binary = true)
    val patch = unifiedDiff(oldBytes, newBytes, "a/$path", "b/$path")
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
    return DiffStatEntry(path, ins, del, binary = false)
}

/**
 * Render a list of [DiffStatEntry] in real-git's `--stat` form:
 * ```
 *  path/to/file     | NN ++++----
 *  N files changed, X insertions(+), Y deletions(-)
 * ```
 * Path column is padded to the longest path; the bar column is scaled so
 * the total width matches git's default of 80 columns.
 */
public fun renderStat(entries: List<DiffStatEntry>): String {
    if (entries.isEmpty()) return ""
    val maxPath = entries.maxOf { it.path.length }
    val maxChanges = entries.maxOf { it.insertions + it.deletions }
    val barTotalWidth = 80 - maxPath - 12 // " | NN " is ~6 chars, plus padding
    val sb = StringBuilder()
    for (e in entries) {
        sb.append(' ').append(e.path.padEnd(maxPath)).append(" | ")
        if (e.binary) {
            sb.append("Bin")
        } else {
            val total = e.insertions + e.deletions
            sb.append(total.toString().padStart(3))
            sb.append(' ')
            if (maxChanges > 0 && barTotalWidth > 0) {
                val scale = if (total <= barTotalWidth) 1.0 else barTotalWidth.toDouble() / maxChanges
                val plus = (e.insertions * scale).toInt().coerceAtLeast(if (e.insertions > 0) 1 else 0)
                val minus = (e.deletions * scale).toInt().coerceAtLeast(if (e.deletions > 0) 1 else 0)
                repeat(plus) { sb.append('+') }
                repeat(minus) { sb.append('-') }
            }
        }
        sb.append('\n')
    }
    val files = entries.size
    val ins = entries.sumOf { it.insertions }
    val del = entries.sumOf { it.deletions }
    sb
        .append(" ")
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

/** Short form: ` N files changed, X insertions(+), Y deletions(-)`. */
public fun renderShortStat(entries: List<DiffStatEntry>): String {
    if (entries.isEmpty()) return ""
    val files = entries.size
    val ins = entries.sumOf { it.insertions }
    val del = entries.sumOf { it.deletions }
    val sb = StringBuilder()
    sb
        .append(" ")
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
