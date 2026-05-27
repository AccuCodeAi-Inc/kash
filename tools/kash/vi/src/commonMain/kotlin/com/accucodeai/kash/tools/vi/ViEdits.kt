package com.accucodeai.kash.tools.vi

/**
 * Mutating edit operations on [ViBuffer]. All operations:
 *  - push the prior buffer onto the undo stack
 *  - set [ViBuffer.dirty]
 *  - return a new buffer
 *
 * Range-based ops (`deleteRange`, `yankRange`) take inclusive `from`/`to`
 * positions plus a [YankType] to record line- vs char-wise on the register.
 */
internal object ViEdits {
    // ---------- Insert mode editing ----------

    fun insertCodepoint(
        buf: ViBuffer,
        cp: Int,
    ): ViBuffer {
        val line = buf.lines[buf.cursorRow]
        val ch = codepointToString(cp)
        val col = buf.cursorCol.coerceAtMost(line.length)
        val newLine = line.substring(0, col) + ch + line.substring(col)
        return buf.copy(
            lines = buf.lines.replaceAt(buf.cursorRow, newLine),
            cursorCol = col + ch.length,
            dirty = true,
            desiredCol = col + ch.length,
            // Any new edit invalidates the redo history.
            redoStack = emptyList(),
        )
    }

    fun splitLine(buf: ViBuffer): ViBuffer {
        val line = buf.lines[buf.cursorRow]
        val col = buf.cursorCol.coerceAtMost(line.length)
        val left = line.substring(0, col)
        val right = line.substring(col)
        val newLines =
            buf.lines.toMutableList().apply {
                set(buf.cursorRow, left)
                add(buf.cursorRow + 1, right)
            }
        return buf.copy(
            lines = newLines,
            cursorRow = buf.cursorRow + 1,
            cursorCol = 0,
            dirty = true,
            desiredCol = 0,
            redoStack = emptyList(),
        )
    }

    /** Insert mode backspace. */
    fun backspaceInsert(buf: ViBuffer): ViBuffer {
        if (buf.cursorCol > 0) {
            val line = buf.lines[buf.cursorRow]
            val nl = line.substring(0, buf.cursorCol - 1) + line.substring(buf.cursorCol)
            return buf.copy(
                lines = buf.lines.replaceAt(buf.cursorRow, nl),
                cursorCol = buf.cursorCol - 1,
                dirty = true,
                desiredCol = buf.cursorCol - 1,
                redoStack = emptyList(),
            )
        }
        if (buf.cursorRow == 0) return buf
        val prevLen = buf.lineLen(buf.cursorRow - 1)
        val joined = buf.lines[buf.cursorRow - 1] + buf.lines[buf.cursorRow]
        val newLines =
            buf.lines.toMutableList().apply {
                removeAt(buf.cursorRow)
                set(buf.cursorRow - 1, joined)
            }
        return buf.copy(
            lines = newLines,
            cursorRow = buf.cursorRow - 1,
            cursorCol = prevLen,
            dirty = true,
            desiredCol = prevLen,
            redoStack = emptyList(),
        )
    }

    // ---------- Normal-mode atomic edits ----------

    /** `x` delete char under cursor. */
    fun deleteCharAtCursor(
        buf: ViBuffer,
        count: Int = 1,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        if (line.isEmpty()) return buf
        val end = (b.cursorCol + count).coerceAtMost(line.length)
        val cut = line.substring(b.cursorCol, end)
        val nl = line.substring(0, b.cursorCol) + line.substring(end)
        val newRegs = b.registers + (UNNAMED to Register(cut, YankType.CHAR))
        val newCol = b.cursorCol.coerceAtMost((nl.length - 1).coerceAtLeast(0))
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, nl),
            cursorCol = newCol,
            dirty = true,
            registers = newRegs,
            desiredCol = newCol,
        )
    }

    /** `X` delete char before cursor. */
    fun deleteCharBefore(
        buf: ViBuffer,
        count: Int = 1,
    ): ViBuffer {
        if (buf.cursorCol == 0) return buf
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        val start = (b.cursorCol - count).coerceAtLeast(0)
        val cut = line.substring(start, b.cursorCol)
        val nl = line.substring(0, start) + line.substring(b.cursorCol)
        val newRegs = b.registers + (UNNAMED to Register(cut, YankType.CHAR))
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, nl),
            cursorCol = start,
            dirty = true,
            registers = newRegs,
            desiredCol = start,
        )
    }

    /** `r<c>` replace char under cursor with another. */
    fun replaceChar(
        buf: ViBuffer,
        cp: Int,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        if (line.isEmpty()) return buf
        val ch = codepointToString(cp)
        val nl = line.substring(0, b.cursorCol) + ch + line.substring(b.cursorCol + 1)
        return b.copy(lines = b.lines.replaceAt(b.cursorRow, nl), dirty = true)
    }

    /** `~` toggle case of char under cursor, advance cursor. */
    fun toggleCase(
        buf: ViBuffer,
        count: Int = 1,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        if (line.isEmpty()) return buf
        val end = (b.cursorCol + count).coerceAtMost(line.length)
        val sb = StringBuilder(line.substring(0, b.cursorCol))
        for (i in b.cursorCol until end) {
            val ch = line[i]
            sb.append(
                when {
                    ch.isUpperCase() -> ch.lowercaseChar()
                    ch.isLowerCase() -> ch.uppercaseChar()
                    else -> ch
                },
            )
        }
        sb.append(line.substring(end))
        val newCol = end.coerceAtMost((sb.length - 1).coerceAtLeast(0))
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, sb.toString()),
            cursorCol = newCol,
            dirty = true,
            desiredCol = newCol,
        )
    }

    /** `o` open new line below — switches to INSERT. */
    fun openBelow(buf: ViBuffer): ViBuffer {
        val b = buf.pushUndo()
        val newLines = b.lines.toMutableList().apply { add(b.cursorRow + 1, "") }
        return b.copy(
            lines = newLines,
            cursorRow = b.cursorRow + 1,
            cursorCol = 0,
            mode = ViMode.INSERT,
            dirty = true,
            desiredCol = 0,
        )
    }

    /** `O` open new line above — switches to INSERT. */
    fun openAbove(buf: ViBuffer): ViBuffer {
        val b = buf.pushUndo()
        val newLines = b.lines.toMutableList().apply { add(b.cursorRow, "") }
        return b.copy(
            lines = newLines,
            cursorRow = b.cursorRow,
            cursorCol = 0,
            mode = ViMode.INSERT,
            dirty = true,
            desiredCol = 0,
        )
    }

    /** `D` delete to EOL — char-wise yank. */
    fun deleteToEol(
        buf: ViBuffer,
        regName: Char? = null,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        val cut = line.substring(b.cursorCol)
        val nl = line.substring(0, b.cursorCol)
        val newCol = (b.cursorCol - 1).coerceAtLeast(0)
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, nl),
            cursorCol = newCol,
            dirty = true,
            registers = b.registers.withYank(regName, cut, YankType.CHAR),
            desiredCol = newCol,
        )
    }

    /** `Y` yank current line — no edit; line-wise yank. */
    fun yankLine(
        buf: ViBuffer,
        count: Int = 1,
        regName: Char? = null,
    ): ViBuffer {
        val end = (buf.cursorRow + count).coerceAtMost(buf.rowCount)
        val cut = buf.lines.subList(buf.cursorRow, end).joinToString("\n")
        return buf.copy(registers = buf.registers.withYank(regName, cut, YankType.LINE))
    }

    /** `dd` delete `count` lines — line-wise yank. */
    fun deleteLines(
        buf: ViBuffer,
        count: Int = 1,
        regName: Char? = null,
    ): ViBuffer {
        val b = buf.pushUndo()
        val end = (b.cursorRow + count).coerceAtMost(b.rowCount)
        val cut = b.lines.subList(b.cursorRow, end).joinToString("\n")
        val newLines =
            b.lines.toMutableList().apply {
                for (i in 0 until (end - b.cursorRow)) removeAt(b.cursorRow)
            }
        val finalLines = if (newLines.isEmpty()) listOf("") else newLines
        val newRow = b.cursorRow.coerceAtMost(finalLines.size - 1)
        return b.copy(
            lines = finalLines,
            cursorRow = newRow,
            cursorCol = 0,
            dirty = true,
            registers = b.registers.withYank(regName, cut, YankType.LINE),
            desiredCol = 0,
        )
    }

    /** `cc` / `S` change `count` lines — replaces with one empty line, INSERT. */
    fun changeLines(
        buf: ViBuffer,
        count: Int = 1,
        regName: Char? = null,
    ): ViBuffer {
        val b = buf.pushUndo()
        val end = (b.cursorRow + count).coerceAtMost(b.rowCount)
        val cut = b.lines.subList(b.cursorRow, end).joinToString("\n")
        val newLines =
            b.lines.toMutableList().apply {
                for (i in 0 until (end - b.cursorRow)) removeAt(b.cursorRow)
                add(b.cursorRow, "")
            }
        return b.copy(
            lines = newLines,
            cursorRow = b.cursorRow,
            cursorCol = 0,
            mode = ViMode.INSERT,
            dirty = true,
            registers = b.registers.withYank(regName, cut, YankType.LINE),
            desiredCol = 0,
        )
    }

    /** `C` change to EOL — INSERT. */
    fun changeToEol(
        buf: ViBuffer,
        regName: Char? = null,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        val cut = line.substring(b.cursorCol)
        val nl = line.substring(0, b.cursorCol)
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, nl),
            mode = ViMode.INSERT,
            dirty = true,
            registers = b.registers.withYank(regName, cut, YankType.CHAR),
        )
    }

    /** `s` substitute char — like `xi`. */
    fun substituteChar(
        buf: ViBuffer,
        count: Int = 1,
        regName: Char? = null,
    ): ViBuffer {
        val b = buf.pushUndo()
        val line = b.lines[b.cursorRow]
        if (line.isEmpty()) return b.copy(mode = ViMode.INSERT)
        val end = (b.cursorCol + count).coerceAtMost(line.length)
        val cut = line.substring(b.cursorCol, end)
        val nl = line.substring(0, b.cursorCol) + line.substring(end)
        return b.copy(
            lines = b.lines.replaceAt(b.cursorRow, nl),
            mode = ViMode.INSERT,
            dirty = true,
            registers = b.registers.withYank(regName, cut, YankType.CHAR),
        )
    }

    // ---------- Range ops (used by operator + motion) ----------

    /** Delete inclusive char-range from..to. If from > to, swap. */
    fun deleteCharRange(
        buf: ViBuffer,
        fromIn: Pos,
        toIn: Pos,
        regName: Char? = null,
    ): ViBuffer {
        val (from, to) = if (fromIn <= toIn) fromIn to toIn else toIn to fromIn
        val b = buf.pushUndo()
        return if (from.row == to.row) {
            val line = b.lines[from.row]
            val endExclusive = (to.col + 1).coerceAtMost(line.length)
            val cut = line.substring(from.col, endExclusive)
            val nl = line.substring(0, from.col) + line.substring(endExclusive)
            val newCol = from.col.coerceAtMost((nl.length - 1).coerceAtLeast(0))
            b.copy(
                lines = b.lines.replaceAt(from.row, nl),
                cursorRow = from.row,
                cursorCol = newCol,
                dirty = true,
                registers = b.registers.withYank(regName, cut, YankType.CHAR),
                desiredCol = newCol,
            )
        } else {
            val first = b.lines[from.row]
            val last = b.lines[to.row]
            val endExclusive = (to.col + 1).coerceAtMost(last.length)
            val cutSb = StringBuilder()
            cutSb.append(first.substring(from.col))
            for (r in (from.row + 1) until to.row) {
                cutSb.append("\n").append(b.lines[r])
            }
            cutSb.append("\n").append(last.substring(0, endExclusive))
            val joined = first.substring(0, from.col) + last.substring(endExclusive)
            val newLines =
                b.lines.toMutableList().apply {
                    // remove rows from+1..to
                    for (r in to.row downTo from.row + 1) removeAt(r)
                    set(from.row, joined)
                }
            val newCol = from.col.coerceAtMost((joined.length - 1).coerceAtLeast(0))
            b.copy(
                lines = newLines,
                cursorRow = from.row,
                cursorCol = newCol,
                dirty = true,
                registers = b.registers.withYank(regName, cutSb.toString(), YankType.CHAR),
                desiredCol = newCol,
            )
        }
    }

    /** Yank inclusive char-range (no edit, no undo push). */
    fun yankCharRange(
        buf: ViBuffer,
        fromIn: Pos,
        toIn: Pos,
        regName: Char? = null,
    ): ViBuffer {
        val (from, to) = if (fromIn <= toIn) fromIn to toIn else toIn to fromIn
        val cut =
            if (from.row == to.row) {
                buf.lines[from.row].substring(from.col, (to.col + 1).coerceAtMost(buf.lines[from.row].length))
            } else {
                val first = buf.lines[from.row].substring(from.col)
                val mid = buf.lines.subList(from.row + 1, to.row).joinToString("\n")
                val last = buf.lines[to.row].substring(0, (to.col + 1).coerceAtMost(buf.lines[to.row].length))
                buildString {
                    append(first)
                    append("\n")
                    if (mid.isNotEmpty()) {
                        append(mid)
                        append("\n")
                    }
                    append(last)
                }
            }
        return buf.copy(registers = buf.registers.withYank(regName, cut, YankType.CHAR))
    }

    /** Delete inclusive line range (`dd` with motion, or visual line). */
    fun deleteLineRange(
        buf: ViBuffer,
        fromRow: Int,
        toRow: Int,
        regName: Char? = null,
    ): ViBuffer {
        val lo = minOf(fromRow, toRow).coerceAtLeast(0)
        val hi = maxOf(fromRow, toRow).coerceAtMost(buf.rowCount - 1)
        return deleteLines(buf.copy(cursorRow = lo), count = hi - lo + 1, regName = regName)
    }

    fun yankLineRange(
        buf: ViBuffer,
        fromRow: Int,
        toRow: Int,
        regName: Char? = null,
    ): ViBuffer {
        val lo = minOf(fromRow, toRow).coerceAtLeast(0)
        val hi = maxOf(fromRow, toRow).coerceAtMost(buf.rowCount - 1)
        val cut = buf.lines.subList(lo, hi + 1).joinToString("\n")
        return buf.copy(registers = buf.registers.withYank(regName, cut, YankType.LINE))
    }

    // ---------- Paste ----------

    /** `p` paste after cursor / below current line. */
    fun pasteAfter(
        buf: ViBuffer,
        regName: Char? = null,
        count: Int = 1,
    ): ViBuffer {
        val reg = buf.registers[regName ?: UNNAMED] ?: return buf
        val b = buf.pushUndo()
        val payload =
            if (count == 1) {
                reg.text
            } else {
                if (reg.type == YankType.LINE) {
                    (1..count).joinToString("\n") { reg.text }
                } else {
                    reg.text.repeat(count)
                }
            }
        return when (reg.type) {
            YankType.LINE -> {
                val insertAt = b.cursorRow + 1
                val newLines = b.lines.toMutableList()
                val toInsert = payload.split("\n")
                newLines.addAll(insertAt, toInsert)
                b.copy(
                    lines = newLines,
                    cursorRow = insertAt,
                    cursorCol = 0,
                    dirty = true,
                    desiredCol = 0,
                )
            }

            YankType.CHAR -> {
                val line = b.lines[b.cursorRow]
                if (line.isEmpty() || !payload.contains('\n')) {
                    val col = if (line.isEmpty()) 0 else (b.cursorCol + 1).coerceAtMost(line.length)
                    val nl = line.substring(0, col) + payload + line.substring(col)
                    val newCol = col + payload.length - 1
                    b.copy(
                        lines = b.lines.replaceAt(b.cursorRow, nl),
                        cursorCol = newCol.coerceAtLeast(0),
                        dirty = true,
                        desiredCol = newCol.coerceAtLeast(0),
                    )
                } else {
                    val parts = payload.split("\n")
                    val col = (b.cursorCol + 1).coerceAtMost(line.length)
                    val newLines = b.lines.toMutableList()
                    newLines[b.cursorRow] = line.substring(0, col) + parts.first()
                    for (i in 1 until parts.size - 1) {
                        newLines.add(b.cursorRow + i, parts[i])
                    }
                    newLines.add(b.cursorRow + parts.size - 1, parts.last() + line.substring(col))
                    b.copy(lines = newLines, dirty = true)
                }
            }
        }
    }

    /** `P` paste before cursor / above current line. */
    fun pasteBefore(
        buf: ViBuffer,
        regName: Char? = null,
        count: Int = 1,
    ): ViBuffer {
        val reg = buf.registers[regName ?: UNNAMED] ?: return buf
        val b = buf.pushUndo()
        val payload =
            if (count == 1) {
                reg.text
            } else if (reg.type == YankType.LINE) {
                (1..count).joinToString("\n") { reg.text }
            } else {
                reg.text.repeat(count)
            }
        return when (reg.type) {
            YankType.LINE -> {
                val insertAt = b.cursorRow
                val newLines = b.lines.toMutableList()
                val toInsert = payload.split("\n")
                newLines.addAll(insertAt, toInsert)
                b.copy(
                    lines = newLines,
                    cursorRow = insertAt,
                    cursorCol = 0,
                    dirty = true,
                    desiredCol = 0,
                )
            }

            YankType.CHAR -> {
                val line = b.lines[b.cursorRow]
                if (!payload.contains('\n')) {
                    val col = b.cursorCol.coerceAtMost(line.length)
                    val nl = line.substring(0, col) + payload + line.substring(col)
                    val newCol = (col + payload.length - 1).coerceAtLeast(0)
                    b.copy(
                        lines = b.lines.replaceAt(b.cursorRow, nl),
                        cursorCol = newCol,
                        dirty = true,
                        desiredCol = newCol,
                    )
                } else {
                    val parts = payload.split("\n")
                    val col = b.cursorCol.coerceAtMost(line.length)
                    val newLines = b.lines.toMutableList()
                    newLines[b.cursorRow] = line.substring(0, col) + parts.first()
                    for (i in 1 until parts.size - 1) newLines.add(b.cursorRow + i, parts[i])
                    newLines.add(b.cursorRow + parts.size - 1, parts.last() + line.substring(col))
                    b.copy(lines = newLines, dirty = true)
                }
            }
        }
    }

    // ---------- Helpers ----------

    private fun Map<Char, Register>.withYank(
        regName: Char?,
        text: String,
        type: YankType,
    ): Map<Char, Register> {
        val withUnnamed = this + (UNNAMED to Register(text, type))
        return if (regName == null) withUnnamed else withUnnamed + (regName to Register(text, type))
    }

    const val UNNAMED: Char = '"'
}
