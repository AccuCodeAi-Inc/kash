package com.accucodeai.kash.tools.diff

import com.accucodeai.kash.diff.Edit
import com.accucodeai.kash.diff.Hunk
import com.accucodeai.kash.diff.Side
import com.accucodeai.kash.diff.groupHunks
import com.accucodeai.kash.diff.renderUnifiedHunkHeader

/**
 * A maximal run of adjacent deletes/inserts with no intervening kept
 * line. Ranges are 0-based, end exclusive.
 */
internal class ChangeBlock(
    val oldStart: Int,
    val oldEnd: Int,
    val newStart: Int,
    val newEnd: Int,
    val deleted: List<String>,
    val inserted: List<String>,
)

/** Collapse an edit script into [ChangeBlock]s separated by kept lines. */
internal fun changeBlocks(edits: List<Edit>): List<ChangeBlock> {
    val out = mutableListOf<ChangeBlock>()
    var oldIdx = 0
    var newIdx = 0
    var i = 0
    while (i < edits.size) {
        if (edits[i] is Edit.Keep) {
            oldIdx++
            newIdx++
            i++
            continue
        }
        val oStart = oldIdx
        val nStart = newIdx
        val del = mutableListOf<String>()
        val ins = mutableListOf<String>()
        while (i < edits.size && edits[i] !is Edit.Keep) {
            when (val c = edits[i]) {
                is Edit.Delete -> {
                    del += c.text
                    oldIdx++
                }

                is Edit.Insert -> {
                    ins += c.text
                    newIdx++
                }

                is Edit.Keep -> {}
            }
            i++
        }
        out += ChangeBlock(oStart, oldIdx, nStart, newIdx, del, ins)
    }
    return out
}

private fun normalRange(
    start: Int,
    end: Int,
): String =
    if (end - start <= 1) {
        "${start + 1}"
    } else {
        "${start + 1},$end"
    }

/**
 * Normal (default) diff: `LcR` / `LdR` / `LaR` commands with `<` (old) and
 * `>` (new) bodies separated by `---` for changes.
 */
internal fun renderNormal(edits: List<Edit>): String {
    val sb = StringBuilder()
    for (b in changeBlocks(edits)) {
        val delN = b.oldEnd - b.oldStart
        val insN = b.newEnd - b.newStart
        when {
            delN > 0 && insN > 0 -> {
                sb
                    .append(
                        normalRange(b.oldStart, b.oldEnd),
                    ).append('c')
                    .append(normalRange(b.newStart, b.newEnd))
                    .append('\n')
                for (l in b.deleted) sb.append("< ").append(l).append('\n')
                sb.append("---\n")
                for (l in b.inserted) sb.append("> ").append(l).append('\n')
            }

            delN > 0 -> {
                sb
                    .append(normalRange(b.oldStart, b.oldEnd))
                    .append('d')
                    .append(b.newStart)
                    .append('\n')
                for (l in b.deleted) sb.append("< ").append(l).append('\n')
            }

            else -> {
                sb
                    .append(b.oldStart)
                    .append('a')
                    .append(normalRange(b.newStart, b.newEnd))
                    .append('\n')
                for (l in b.inserted) sb.append("> ").append(l).append('\n')
            }
        }
    }
    return sb.toString()
}

/**
 * Ed-script diff (`-e`): commands emitted bottom-up so applying them with
 * `ed` left-to-right keeps line numbers valid.
 */
internal fun renderEd(edits: List<Edit>): String {
    val sb = StringBuilder()
    for (b in changeBlocks(edits).asReversed()) {
        val delN = b.oldEnd - b.oldStart
        val insN = b.newEnd - b.newStart
        when {
            delN > 0 && insN > 0 -> {
                sb.append(normalRange(b.oldStart, b.oldEnd)).append("c\n")
                for (l in b.inserted) sb.append(l).append('\n')
                sb.append(".\n")
            }

            delN > 0 -> {
                sb.append(normalRange(b.oldStart, b.oldEnd)).append("d\n")
            }

            else -> {
                sb.append(b.oldStart).append("a\n")
                for (l in b.inserted) sb.append(l).append('\n')
                sb.append(".\n")
            }
        }
    }
    return sb.toString()
}

/**
 * Unified diff (`-u`/`-U N`). Reuses the shared hunk grouper/header.
 * [oldTrailingNl]/[newTrailingNl] drive the `\ No newline` marker.
 */
internal fun renderUnified(
    edits: List<Edit>,
    oldLabel: String,
    newLabel: String,
    context: Int,
    oldTrailingNl: Boolean,
    newTrailingNl: Boolean,
): String {
    if (edits.all { it is Edit.Keep }) return ""
    val sb = StringBuilder()
    sb.append("--- ").append(oldLabel).append('\n')
    sb.append("+++ ").append(newLabel).append('\n')
    for (h in groupHunks(edits, context)) {
        sb.append(renderUnifiedHunkHeader(h)).append('\n')
        for (line in h.lines) {
            sb.append(line.prefix).append(line.text).append('\n')
            if (line.isLast) {
                val needsMarker =
                    when (line.side) {
                        Side.OLD -> !oldTrailingNl
                        Side.NEW -> !newTrailingNl
                        Side.BOTH -> !oldTrailingNl || !newTrailingNl
                    }
                if (needsMarker) sb.append("\\ No newline at end of file\n")
            }
        }
    }
    return sb.toString()
}

/**
 * Context diff (`-c`/`-C N`): `*** old ***` / `--- new ---` file headers,
 * then per-hunk `*** a,b ****` / `--- c,d ----` sections with `! `/`- `/`+ `
 * bodies.
 */
internal fun renderContext(
    edits: List<Edit>,
    oldLabel: String,
    newLabel: String,
    context: Int,
): String {
    if (edits.all { it is Edit.Keep }) return ""
    val sb = StringBuilder()
    sb.append("*** ").append(oldLabel).append('\n')
    sb.append("--- ").append(newLabel).append('\n')
    for (h in groupHunks(edits, context)) {
        sb.append("***************\n")
        sb.append("*** ").append(contextRange(h.oldStart, h.oldCount)).append(" ****\n")
        if (h.lines.any { it.side == Side.OLD || it.side == Side.BOTH }) {
            renderContextSide(sb, h, oldSide = true)
        }
        sb.append("--- ").append(contextRange(h.newStart, h.newCount)).append(" ----\n")
        if (h.lines.any { it.side == Side.NEW || it.side == Side.BOTH }) {
            renderContextSide(sb, h, oldSide = false)
        }
    }
    return sb.toString()
}

private fun contextRange(
    start: Int,
    count: Int,
): String {
    if (count == 0) return "$start"
    val s = start + 1
    return if (count == 1) "$s" else "$s,${s + count - 1}"
}

private fun renderContextSide(
    sb: StringBuilder,
    h: Hunk,
    oldSide: Boolean,
) {
    val hasDel = h.lines.any { it.side == Side.OLD }
    val hasIns = h.lines.any { it.side == Side.NEW }
    val changed = hasDel && hasIns
    for (line in h.lines) {
        when (line.side) {
            Side.BOTH -> {
                sb.append("  ").append(line.text).append('\n')
            }

            Side.OLD -> {
                if (oldSide) {
                    sb.append(if (changed) "! " else "- ").append(line.text).append('\n')
                }
            }

            Side.NEW -> {
                if (!oldSide) {
                    sb.append(if (changed) "! " else "+ ").append(line.text).append('\n')
                }
            }
        }
    }
}
