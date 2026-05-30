package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.diff.Edit
import com.accucodeai.kash.diff.groupHunks
import com.accucodeai.kash.diff.renderUnifiedDiff
import com.accucodeai.kash.diff.renderUnifiedHunkHeader
import com.accucodeai.kash.diff.splitLines

/**
 * Whitespace-folding options for diff comparison. Mirrors git's
 * `-w`/`-b`/`--ignore-space-at-eol`/`--ignore-blank-lines`: the *original*
 * line bytes are what the diff emits, the folded form is used only to
 * decide whether two lines are "the same".
 */
public class DiffWhitespace(
    public val ignoreAllSpace: Boolean = false, // -w / --ignore-all-space
    public val ignoreSpaceChange: Boolean = false, // -b / --ignore-space-change
    public val ignoreSpaceAtEol: Boolean = false, // --ignore-space-at-eol
    public val ignoreBlankLines: Boolean = false, // --ignore-blank-lines
) {
    public val active: Boolean
        get() = ignoreAllSpace || ignoreSpaceChange || ignoreSpaceAtEol || ignoreBlankLines

    /** Comparison key for a single line (newline already stripped). */
    public fun key(line: String): String {
        var s = line
        when {
            ignoreAllSpace -> {
                s = s.filter { !isBlank(it) }
            }

            ignoreSpaceChange -> {
                val sb = StringBuilder()
                var prevSpace = false
                for (c in s) {
                    if (isBlank(c)) {
                        if (!prevSpace) sb.append(' ')
                        prevSpace = true
                    } else {
                        sb.append(c)
                        prevSpace = false
                    }
                }
                s = trimTrailingBlanks(sb.toString())
            }

            ignoreSpaceAtEol -> {
                s = trimTrailingBlanks(s)
            }
        }
        return s
    }

    private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\r'

    private fun trimTrailingBlanks(s: String): String {
        var end = s.length
        while (end > 0 && isBlank(s[end - 1])) end--
        return s.substring(0, end)
    }
}

/**
 * Line-oriented unified diff. Thin wrapper over
 * [com.accucodeai.kash.diff.renderUnifiedDiff] in `:shared:difflib`.
 * Uses git's hunk-header style: a unit range collapses to a bare start
 * (`@@ -1 +1,2 @@`), while a zero range keeps its count (`@@ -0,0 +1 @@`).
 */
public fun unifiedDiff(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    oldLabel: String,
    newLabel: String,
    contextLines: Int = 3,
): String =
    renderUnifiedDiff(
        oldText = oldBytes.decodeToString(),
        newText = newBytes.decodeToString(),
        oldLabel = oldLabel,
        newLabel = newLabel,
        contextLines = contextLines,
        collapseUnitRanges = true,
    )

/**
 * Unified diff with whitespace-folding comparison keys. When [ws] is
 * inactive this produces byte-identical output to [unifiedDiff]. Lines
 * that differ only in folded-out whitespace are treated as unchanged
 * (context) lines and the original *old*-side text is shown for them, as
 * git does.
 */
public fun unifiedDiff(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    oldLabel: String,
    newLabel: String,
    contextLines: Int,
    ws: DiffWhitespace,
): String {
    if (!ws.active) {
        return unifiedDiff(oldBytes, newBytes, oldLabel, newLabel, contextLines)
    }
    val oldSplit = splitLines(oldBytes.decodeToString())
    val newSplit = splitLines(newBytes.decodeToString())
    val edits = keyedEdits(oldSplit.lines, newSplit.lines, ws)
    if (edits.all { it is Edit.Keep }) return ""

    val sb = StringBuilder()
    sb.append("--- ").append(oldLabel).append('\n')
    sb.append("+++ ").append(newLabel).append('\n')
    val hunks = groupHunks(edits, contextLines)
    for (h in hunks) {
        sb.append(renderUnifiedHunkHeader(h, collapseUnitRanges = true)).append('\n')
        for (line in h.lines) {
            sb.append(line.prefix).append(line.text).append('\n')
            if (line.isLast) {
                val needsMarker =
                    when (line.side) {
                        com.accucodeai.kash.diff.Side.OLD -> {
                            !oldSplit.hasTrailingNewline
                        }

                        com.accucodeai.kash.diff.Side.NEW -> {
                            !newSplit.hasTrailingNewline
                        }

                        com.accucodeai.kash.diff.Side.BOTH -> {
                            !oldSplit.hasTrailingNewline || !newSplit.hasTrailingNewline
                        }
                    }
                if (needsMarker) sb.append("\\ No newline at end of file\n")
            }
        }
    }
    return sb.toString()
}

/**
 * True iff [oldBytes] and [newBytes] are equal under the whitespace
 * folding rules in [ws]. Used for `--exit-code`/`--quiet` and for deciding
 * whether a file pair counts as changed at all.
 */
public fun whitespaceEqual(
    oldBytes: ByteArray,
    newBytes: ByteArray,
    ws: DiffWhitespace,
): Boolean {
    if (oldBytes.contentEquals(newBytes)) return true
    if (!ws.active) return false
    val oldSplit = splitLines(oldBytes.decodeToString())
    val newSplit = splitLines(newBytes.decodeToString())
    var oldKeys = oldSplit.lines.map { ws.key(it) }
    var newKeys = newSplit.lines.map { ws.key(it) }
    if (ws.ignoreBlankLines) {
        oldKeys = oldKeys.filter { it.isNotEmpty() }
        newKeys = newKeys.filter { it.isNotEmpty() }
    }
    return oldKeys == newKeys
}

/** LCS edit script comparing by folded keys, carrying original text. */
private fun keyedEdits(
    old: List<String>,
    new: List<String>,
    ws: DiffWhitespace,
): List<Edit> {
    val oldKeys = old.map { ws.key(it) }
    val newKeys = new.map { ws.key(it) }
    val n = old.size
    val m = new.size
    val lcs = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            lcs[i][j] =
                if (oldKeys[i] == newKeys[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
        }
    }
    val out = mutableListOf<Edit>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            oldKeys[i] == newKeys[j] -> {
                out += Edit.Keep(old[i])
                i++
                j++
            }

            lcs[i + 1][j] >= lcs[i][j + 1] -> {
                out += Edit.Delete(old[i])
                i++
            }

            else -> {
                out += Edit.Insert(new[j])
                j++
            }
        }
    }
    while (i < n) {
        out += Edit.Delete(old[i])
        i++
    }
    while (j < m) {
        out += Edit.Insert(new[j])
        j++
    }
    return if (ws.ignoreBlankLines) suppressBlankOnlyChanges(out) else out
}

/**
 * `--ignore-blank-lines`: a change run consisting only of blank-line
 * edits is downgraded to keeps so the surrounding context lines up.
 */
private fun suppressBlankOnlyChanges(edits: List<Edit>): List<Edit> {
    val out = mutableListOf<Edit>()
    var i = 0
    while (i < edits.size) {
        if (edits[i] is Edit.Keep) {
            out += edits[i]
            i++
            continue
        }
        val run = mutableListOf<Edit>()
        while (i < edits.size && edits[i] !is Edit.Keep) {
            run += edits[i]
            i++
        }
        if (run.all { it.text.isBlank() }) {
            for (e in run) {
                if (e is Edit.Delete) out += Edit.Keep(e.text)
            }
        } else {
            out += run
        }
    }
    return out
}
