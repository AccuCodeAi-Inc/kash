package com.accucodeai.kash.tools.nano

/**
 * The editor's in-memory text. Lines are stored as plain Kotlin strings;
 * edits return a new [NanoBuffer] (immutable copy-on-write) so the editor's
 * single-step undo is trivial — snapshot before the edit, swap back on undo.
 *
 * `cursor` is `(line, col)` in codepoint *units* — v1 treats one codepoint
 * as one cell. CJK / emoji render off-by-N. `topRow` is the viewport's
 * first visible row.
 *
 * `dirty` flips on the first mutation that diverges from disk and only
 * resets on save.
 */
internal data class NanoBuffer(
    val lines: List<String>,
    val cursorRow: Int,
    val cursorCol: Int,
    val topRow: Int,
    val dirty: Boolean,
    val filename: String?,
) {
    val rowCount: Int get() = lines.size

    fun lineLen(row: Int): Int = lines.getOrNull(row)?.length ?: 0

    /** Clamp the cursor to a legal position. Called after any structural edit. */
    fun normalized(): NanoBuffer {
        val r = cursorRow.coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        val c = cursorCol.coerceIn(0, lineLen(r))
        return if (r == cursorRow && c == cursorCol) this else copy(cursorRow = r, cursorCol = c)
    }

    // ---------- Cursor movement ----------

    fun moveLeft(): NanoBuffer =
        when {
            cursorCol > 0 -> copy(cursorCol = cursorCol - 1)
            cursorRow > 0 -> copy(cursorRow = cursorRow - 1, cursorCol = lineLen(cursorRow - 1))
            else -> this
        }

    fun moveRight(): NanoBuffer =
        when {
            cursorCol < lineLen(cursorRow) -> copy(cursorCol = cursorCol + 1)
            cursorRow < rowCount - 1 -> copy(cursorRow = cursorRow + 1, cursorCol = 0)
            else -> this
        }

    fun moveUp(): NanoBuffer =
        if (cursorRow == 0) this else copy(cursorRow = cursorRow - 1, cursorCol = cursorCol).normalized()

    fun moveDown(): NanoBuffer =
        if (cursorRow >= rowCount - 1) {
            this
        } else {
            copy(cursorRow = cursorRow + 1, cursorCol = cursorCol).normalized()
        }

    fun moveLineHome(): NanoBuffer = copy(cursorCol = 0)

    fun moveLineEnd(): NanoBuffer = copy(cursorCol = lineLen(cursorRow))

    // ---------- Editing ----------

    /**
     * Insert a printable codepoint at the cursor.
     */
    fun insertCodepoint(cp: Int): NanoBuffer {
        val line = lines[cursorRow]
        val ch = codepointToString(cp)
        val newLine = line.substring(0, cursorCol) + ch + line.substring(cursorCol)
        return copy(
            lines = lines.replaceAt(cursorRow, newLine),
            cursorCol = cursorCol + ch.length,
            dirty = true,
        )
    }

    /** Split the current line at the cursor (Enter). */
    fun splitLine(): NanoBuffer {
        val line = lines[cursorRow]
        val left = line.substring(0, cursorCol)
        val right = line.substring(cursorCol)
        val newLines = lines.toMutableList()
        newLines[cursorRow] = left
        newLines.add(cursorRow + 1, right)
        return copy(
            lines = newLines,
            cursorRow = cursorRow + 1,
            cursorCol = 0,
            dirty = true,
        )
    }

    /** Backspace: delete the char before the cursor; at col 0, join with prior. */
    fun backspace(): NanoBuffer {
        if (cursorCol > 0) {
            val line = lines[cursorRow]
            val newLine = line.substring(0, cursorCol - 1) + line.substring(cursorCol)
            return copy(
                lines = lines.replaceAt(cursorRow, newLine),
                cursorCol = cursorCol - 1,
                dirty = true,
            )
        }
        if (cursorRow == 0) return this
        val prevLen = lineLen(cursorRow - 1)
        val joined = lines[cursorRow - 1] + lines[cursorRow]
        val newLines =
            lines.toMutableList().apply {
                removeAt(cursorRow)
                set(cursorRow - 1, joined)
            }
        return copy(
            lines = newLines,
            cursorRow = cursorRow - 1,
            cursorCol = prevLen,
            dirty = true,
        )
    }

    /** Delete: remove the char at the cursor; at EOL, join with next. */
    fun deleteForward(): NanoBuffer {
        val line = lines[cursorRow]
        if (cursorCol < line.length) {
            val newLine = line.substring(0, cursorCol) + line.substring(cursorCol + 1)
            return copy(lines = lines.replaceAt(cursorRow, newLine), dirty = true)
        }
        if (cursorRow >= rowCount - 1) return this
        val joined = line + lines[cursorRow + 1]
        val newLines =
            lines.toMutableList().apply {
                removeAt(cursorRow + 1)
                set(cursorRow, joined)
            }
        return copy(lines = newLines, dirty = true)
    }

    /** Cut the current line; return new buffer plus the cut text. */
    fun cutLine(): Pair<NanoBuffer, String> {
        if (rowCount == 0) return this to ""
        val cut = lines[cursorRow]
        val newLines =
            if (rowCount == 1) {
                listOf("")
            } else {
                lines.toMutableList().apply { removeAt(cursorRow) }
            }
        return copy(
            lines = newLines,
            cursorRow = cursorRow.coerceAtMost(newLines.size - 1),
            cursorCol = 0,
            dirty = true,
        ).normalized() to cut
    }

    /** Insert a previously-cut line above the cursor row. */
    fun pasteLineAbove(line: String): NanoBuffer {
        val newLines = lines.toMutableList().apply { add(cursorRow, line) }
        return copy(
            lines = newLines,
            cursorRow = cursorRow + 1,
            cursorCol = 0,
            dirty = true,
        )
    }

    // ---------- Search ----------

    /**
     * Find the next occurrence of [needle] starting from just after the
     * cursor; wraps to the start. Returns the new cursor position, or null
     * if no match.
     */
    fun findNext(needle: String): Pair<Int, Int>? {
        if (needle.isEmpty() || rowCount == 0) return null
        // Search rest of current line first
        val cur = lines[cursorRow]
        val fromIdx = (cursorCol + 1).coerceAtMost(cur.length)
        cur.indexOf(needle, fromIdx).takeIf { it >= 0 }?.let { return cursorRow to it }
        // Search subsequent lines
        for (r in (cursorRow + 1) until rowCount) {
            lines[r].indexOf(needle).takeIf { it >= 0 }?.let { return r to it }
        }
        // Wrap to top through current row
        for (r in 0..cursorRow) {
            lines[r].indexOf(needle).takeIf { it >= 0 }?.let { return r to it }
        }
        return null
    }

    companion object {
        fun fromText(
            text: String,
            filename: String?,
        ): NanoBuffer {
            // Preserve trailing newline semantics by splitting without a
            // final empty if the file ends in \n — but always ensure at
            // least one line so the cursor has somewhere to live.
            val raw = if (text.isEmpty()) listOf("") else text.split('\n')
            val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
            return NanoBuffer(
                lines = lines.ifEmpty { listOf("") },
                cursorRow = 0,
                cursorCol = 0,
                topRow = 0,
                dirty = false,
                filename = filename,
            )
        }

        fun empty(filename: String?): NanoBuffer =
            NanoBuffer(
                lines = listOf(""),
                cursorRow = 0,
                cursorCol = 0,
                topRow = 0,
                dirty = false,
                filename = filename,
            )
    }
}

private fun List<String>.replaceAt(
    i: Int,
    s: String,
): List<String> = toMutableList().also { it[i] = s }
