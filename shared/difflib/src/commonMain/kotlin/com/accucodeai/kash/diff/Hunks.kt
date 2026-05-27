package com.accucodeai.kash.diff

/** Which side(s) of the diff a hunk line belongs to. */
public enum class Side { OLD, NEW, BOTH }

/**
 * One rendered line inside a hunk. [prefix] is the unified-diff marker
 * (` `, `-`, `+`). [side] tracks which input the line came from, and
 * [isLast] marks the trailing line on each side so callers can emit the
 * `\ No newline at end of file` marker correctly.
 */
public class HunkLine(
    public val prefix: Char,
    public val text: String,
    public val side: Side,
    public var isLast: Boolean = false,
)

/**
 * A contiguous block of edits plus surrounding context. [oldStart] /
 * [newStart] are 0-based line indices into the respective inputs;
 * [oldCount] / [newCount] are the line spans the hunk covers.
 */
public class Hunk(
    public val oldStart: Int,
    public val newStart: Int,
    public val oldCount: Int,
    public val newCount: Int,
    public val lines: List<HunkLine>,
)

/**
 * Group an edit script into hunks, expanding each change run by up to
 * [context] unchanged lines on either side. Adjacent change runs separated
 * by `<= 2*context` keeps merge into a single hunk, matching diff/git.
 */
public fun groupHunks(
    edits: List<Edit>,
    context: Int,
): List<Hunk> {
    data class Indexed(
        val edit: Edit,
        val oldIdx: Int,
        val newIdx: Int,
    )
    val list = mutableListOf<Indexed>()
    var o = 0
    var nl = 0
    for (e in edits) {
        list += Indexed(e, o, nl)
        when (e) {
            is Edit.Keep -> {
                o++
                nl++
            }

            is Edit.Delete -> {
                o++
            }

            is Edit.Insert -> {
                nl++
            }
        }
    }

    val out = mutableListOf<Hunk>()
    var i = 0
    while (i < list.size) {
        if (list[i].edit is Edit.Keep) {
            i++
            continue
        }
        // Found a change. Expand left by up to `context` keeps.
        var left = i
        var ctx = 0
        while (left > 0 && list[left - 1].edit is Edit.Keep && ctx < context) {
            left--
            ctx++
        }
        // Walk right, merging change runs separated by <= 2*context keeps.
        var right = i
        while (right < list.size) {
            val cur = list[right].edit
            if (cur !is Edit.Keep) {
                right++
                continue
            }
            var k = 0
            var probe = right
            while (probe < list.size && list[probe].edit is Edit.Keep) {
                probe++
                k++
            }
            if (probe >= list.size || k > 2 * context) break
            right = probe
        }
        // Expand right by up to `context` keeps.
        var rightEnd = right
        ctx = 0
        while (rightEnd < list.size && list[rightEnd].edit is Edit.Keep && ctx < context) {
            rightEnd++
            ctx++
        }

        val slice = list.subList(left, rightEnd)
        val hunkLines = mutableListOf<HunkLine>()
        var oldCount = 0
        var newCount = 0
        for (s in slice) {
            when (s.edit) {
                is Edit.Keep -> {
                    hunkLines += HunkLine(' ', s.edit.text, Side.BOTH)
                    oldCount++
                    newCount++
                }

                is Edit.Delete -> {
                    hunkLines += HunkLine('-', s.edit.text, Side.OLD)
                    oldCount++
                }

                is Edit.Insert -> {
                    hunkLines += HunkLine('+', s.edit.text, Side.NEW)
                    newCount++
                }
            }
        }
        // Mark the trailing line on each side for the "no newline" marker.
        for (idx in hunkLines.indices.reversed()) {
            val ln = hunkLines[idx]
            if (ln.side == Side.OLD || ln.side == Side.BOTH) {
                ln.isLast = true
                break
            }
        }
        if (hunkLines.lastOrNull()?.side == Side.NEW) hunkLines.last().isLast = true

        out +=
            Hunk(
                oldStart = slice.first().oldIdx,
                newStart = slice.first().newIdx,
                oldCount = oldCount,
                newCount = newCount,
                lines = hunkLines,
            )
        i = rightEnd
    }
    return out
}

/**
 * Render a unified diff for [oldText] vs [newText]. Emits the `--- ` /
 * `+++ ` header (labelled by [oldLabel] / [newLabel]) followed by
 * `@@`-delimited hunks with [contextLines] lines of context. Returns an
 * empty string when the inputs are identical.
 *
 * "No newline at end of file" is signalled with the conventional
 * `\ No newline at end of file` marker.
 */
public fun renderUnifiedDiff(
    oldText: String,
    newText: String,
    oldLabel: String,
    newLabel: String,
    contextLines: Int = 3,
    collapseUnitRanges: Boolean = true,
): String {
    val oldSplit = splitLines(oldText)
    val newSplit = splitLines(newText)

    val edits = lcsEdits(oldSplit.lines, newSplit.lines)
    if (edits.all { it is Edit.Keep }) return ""

    val sb = StringBuilder()
    sb.append("--- ").append(oldLabel).append('\n')
    sb.append("+++ ").append(newLabel).append('\n')

    val hunks = groupHunks(edits, contextLines)
    for (h in hunks) {
        sb.append(renderUnifiedHunkHeader(h, collapseUnitRanges)).append('\n')
        for (line in h.lines) {
            sb.append(line.prefix).append(line.text).append('\n')
            if (line.isLast) {
                val needsMarker =
                    when (line.side) {
                        Side.OLD -> !oldSplit.hasTrailingNewline
                        Side.NEW -> !newSplit.hasTrailingNewline
                        Side.BOTH -> !oldSplit.hasTrailingNewline || !newSplit.hasTrailingNewline
                    }
                if (needsMarker) sb.append("\\ No newline at end of file\n")
            }
        }
    }
    return sb.toString()
}

/**
 * The `@@ -a,b +c,d @@` line for [h]. Counts of zero collapse the start
 * to the line *before* the change (POSIX/git convention for pure
 * insertions/deletions); otherwise starts are 1-based. When
 * [collapseUnitRanges] is set, a count of 1 is rendered as a bare start
 * (`@@ -1 +1 @@`), matching real diff/git; otherwise the `,1` is kept.
 */
public fun renderUnifiedHunkHeader(
    h: Hunk,
    collapseUnitRanges: Boolean = true,
): String {
    val oldStart = if (h.oldCount == 0) h.oldStart else h.oldStart + 1
    val newStart = if (h.newCount == 0) h.newStart else h.newStart + 1
    val oldRange = if (collapseUnitRanges && h.oldCount == 1) "$oldStart" else "$oldStart,${h.oldCount}"
    val newRange = if (collapseUnitRanges && h.newCount == 1) "$newStart" else "$newStart,${h.newCount}"
    return "@@ -$oldRange +$newRange @@"
}
