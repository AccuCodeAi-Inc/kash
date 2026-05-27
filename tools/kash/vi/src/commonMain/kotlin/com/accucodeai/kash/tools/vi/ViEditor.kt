package com.accucodeai.kash.tools.vi

import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.fs.FileSystem

/**
 * Vi editor entry point. Owns terminal lifecycle (raw + alt screen) and the
 * mode-dispatched key loop.
 *
 * Returns 0 on clean exit, 1 on an unrecoverable I/O error.
 */
internal class ViEditor(
    private val term: TerminalControl,
    private val fs: FileSystem,
    private val cwd: String,
    private val initialFile: String?,
    private val initialLine: Int? = null,
    private val initialSearch: String? = null,
) {
    private val renderer = ViRenderer(term)
    private val minibuffer = ViMinibuffer(term)
    private var notice: String = ""

    /** State held outside ViBuffer for sequencing (waiting on r/f/t/m operand char). */
    private enum class Pending {
        NONE,
        REPLACE_CHAR,
        FIND_F,
        FIND_T,
        FIND_F_BACK,
        FIND_T_BACK,
        SET_MARK,
        JUMP_MARK_LINE,
        JUMP_MARK_POS,
        REGISTER_NAME,
        GOTO_G,
        Z_PREFIX,
    }

    private var pending: Pending = Pending.NONE
    private var pendingRegister: Char? = null

    suspend fun run(): Int {
        var buf = loadInitial() ?: return 1
        initialLine?.let {
            val p = ViMotions.gotoLine(buf, it)
            buf = buf.copy(cursorRow = p.row, cursorCol = p.col, desiredCol = p.col)
        }
        initialSearch?.let {
            val p = ViMotions.searchForward(buf, it, buf.cursorRow, buf.cursorCol - 1)
            if (p != null) {
                buf =
                    buf.copy(
                        cursorRow = p.row,
                        cursorCol = p.col,
                        desiredCol = p.col,
                        lastSearch = LastSearch(it, forward = true),
                    )
            }
        }
        term.enterRawMode()
        term.useAlternateScreen(true)
        try {
            while (true) {
                renderer.draw(buf, notice)
                notice = ""
                val key = term.readKey()
                val next = handle(buf, key) ?: return 0
                buf = next.normalized()
            }
        } finally {
            try {
                term.useAlternateScreen(false)
            } catch (_: Throwable) {
            }
            try {
                term.exitRawMode()
            } catch (_: Throwable) {
            }
            try {
                term.flush()
            } catch (_: Throwable) {
            }
        }
    }

    /** Return null to exit the editor. */
    private suspend fun handle(
        buf: ViBuffer,
        key: Key,
    ): ViBuffer? {
        // Consume pending operand-keys regardless of mode.
        if (pending != Pending.NONE) {
            return handlePending(buf, key)
        }
        return when (buf.mode) {
            ViMode.INSERT -> handleInsert(buf, key)
            ViMode.NORMAL -> handleNormal(buf, key)
            ViMode.VISUAL_CHAR, ViMode.VISUAL_LINE -> handleVisual(buf, key)
            ViMode.COMMAND -> buf // unreachable — command mode is modal via minibuffer
        }
    }

    // ---------- INSERT ----------

    private fun handleInsert(
        buf: ViBuffer,
        key: Key,
    ): ViBuffer =
        when (key) {
            is Key.Char -> {
                ViEdits.insertCodepoint(buf, key.codepoint)
            }

            Key.Named.ENTER -> {
                ViEdits.splitLine(buf)
            }

            Key.Named.BACKSPACE -> {
                ViEdits.backspaceInsert(buf)
            }

            Key.Named.DELETE -> {
                val line = buf.lines[buf.cursorRow]
                if (buf.cursorCol < line.length) {
                    val nl = line.substring(0, buf.cursorCol) + line.substring(buf.cursorCol + 1)
                    buf.copy(lines = buf.lines.replaceAt(buf.cursorRow, nl), dirty = true)
                } else if (buf.cursorRow < buf.rowCount - 1) {
                    val joined = line + buf.lines[buf.cursorRow + 1]
                    val newLines =
                        buf.lines.toMutableList().apply {
                            removeAt(buf.cursorRow + 1)
                            set(buf.cursorRow, joined)
                        }
                    buf.copy(lines = newLines, dirty = true)
                } else {
                    buf
                }
            }

            Key.Named.TAB -> {
                ViEdits.insertCodepoint(buf, '\t'.code)
            }

            Key.Named.ESC -> {
                // exit insert, move cursor left one (classic vi)
                val newCol = (buf.cursorCol - 1).coerceAtLeast(0)
                buf.copy(mode = ViMode.NORMAL, cursorCol = newCol, desiredCol = newCol)
            }

            Key.Named.UP -> {
                val p = ViMotions.up(buf, 1)
                buf.copy(cursorRow = p.row, cursorCol = p.col)
            }

            Key.Named.DOWN -> {
                val p = ViMotions.down(buf, 1)
                buf.copy(cursorRow = p.row, cursorCol = p.col)
            }

            Key.Named.LEFT -> {
                val p = ViMotions.left(buf, 1)
                buf.copy(cursorRow = p.row, cursorCol = p.col)
            }

            Key.Named.RIGHT -> {
                val p = ViMotions.right(buf, 1)
                buf.copy(cursorRow = p.row, cursorCol = p.col)
            }

            is Key.Paste -> {
                key.text.fold(buf) { b, ch ->
                    if (ch == '\n') ViEdits.splitLine(b) else ViEdits.insertCodepoint(b, ch.code)
                }
            }

            is Key.Ctrl -> {
                when (key.letter) {
                    '[' -> buf.copy(mode = ViMode.NORMAL)

                    // Ctrl-[ == ESC
                    'C' -> buf.copy(mode = ViMode.NORMAL)

                    else -> buf
                }
            }

            else -> {
                buf
            }
        }

    // ---------- NORMAL ----------

    private suspend fun handleNormal(
        buf: ViBuffer,
        key: Key,
    ): ViBuffer? {
        // Digits build the count (but 0 with no count is line-start motion).
        if (key is Key.Char && key.codepoint in '1'.code..'9'.code) {
            val d = key.codepoint - '0'.code
            return buf.copy(pendingCount = buf.pendingCount * 10 + d)
        }
        if (key is Key.Char && key.codepoint == '0'.code && buf.pendingCount > 0) {
            return buf.copy(pendingCount = buf.pendingCount * 10)
        }
        val count = if (buf.pendingCount == 0) 1 else buf.pendingCount
        // Reset count + op for next iter unless we explicitly carry them.
        val cleared = buf.copy(pendingCount = 0)

        // Handle pending operator + motion path
        if (buf.pendingOp != null) {
            return handleOperatorMotion(cleared, key, count)
        }

        return when (key) {
            is Key.Char -> {
                handleNormalChar(cleared, key.codepoint, count)
            }

            Key.Named.LEFT -> {
                moveTo(cleared, ViMotions.left(cleared, count))
            }

            Key.Named.RIGHT -> {
                moveTo(cleared, ViMotions.right(cleared, count))
            }

            Key.Named.UP -> {
                moveTo(cleared, ViMotions.up(cleared, count))
            }

            Key.Named.DOWN -> {
                moveTo(cleared, ViMotions.down(cleared, count))
            }

            Key.Named.HOME -> {
                moveTo(cleared, ViMotions.lineStart(cleared))
            }

            Key.Named.END -> {
                moveTo(cleared, ViMotions.lineEnd(cleared))
            }

            Key.Named.PGUP -> {
                pageUp(cleared)
            }

            Key.Named.PGDN -> {
                pageDown(cleared)
            }

            Key.Named.ENTER -> {
                // Enter in normal mode goes to first non-blank of next line
                val p = ViMotions.down(cleared, 1)
                val r = p.row
                val ln = cleared.lines[r]
                val c = ln.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                cleared.copy(cursorRow = r, cursorCol = c, desiredCol = c)
            }

            Key.Named.ESC -> {
                cleared.copy(pendingOp = null, pendingCount = 0)
            }

            is Key.Ctrl -> {
                handleNormalCtrl(cleared, key.letter, count)
            }

            else -> {
                cleared
            }
        }
    }

    private suspend fun handleNormalChar(
        buf: ViBuffer,
        cp: Int,
        count: Int,
    ): ViBuffer? {
        val ch = if (cp <= 0xFFFF) cp.toChar() else null
        return when (ch) {
            'h' -> {
                moveTo(buf, ViMotions.left(buf, count))
            }

            'l' -> {
                moveTo(buf, ViMotions.right(buf, count))
            }

            'j' -> {
                moveDownPreserveCol(buf, count)
            }

            'k' -> {
                moveUpPreserveCol(buf, count)
            }

            ' ' -> {
                moveTo(buf, ViMotions.right(buf, count))
            }

            '0' -> {
                moveTo(buf, ViMotions.lineStart(buf))
            }

            '^' -> {
                moveTo(buf, ViMotions.firstNonBlank(buf))
            }

            '$' -> {
                moveTo(buf, ViMotions.lineEnd(buf))
            }

            'w' -> {
                moveTo(buf, ViMotions.nextWordStart(buf, count, big = false))
            }

            'W' -> {
                moveTo(buf, ViMotions.nextWordStart(buf, count, big = true))
            }

            'b' -> {
                moveTo(buf, ViMotions.prevWordStart(buf, count, big = false))
            }

            'B' -> {
                moveTo(buf, ViMotions.prevWordStart(buf, count, big = true))
            }

            'e' -> {
                moveTo(buf, ViMotions.wordEnd(buf, count, big = false))
            }

            'E' -> {
                moveTo(buf, ViMotions.wordEnd(buf, count, big = true))
            }

            'G' -> {
                val target =
                    if (buf.pendingCount > 0) {
                        buf.pendingCount
                    } else if (count > 1) {
                        count
                    } else {
                        buf.rowCount
                    }
                val p = ViMotions.gotoLine(buf, target)
                jumpTo(buf, p)
            }

            'g' -> {
                pending = Pending.GOTO_G
                buf
            }

            'H' -> {
                // top of visible — approximated as topRow
                val p = ViMotions.gotoLine(buf, buf.topRow + 1)
                moveTo(buf, p)
            }

            'L' -> {
                val size = term.size()
                val bot = (buf.topRow + size.rows - 2).coerceAtMost(buf.rowCount)
                moveTo(buf, ViMotions.gotoLine(buf, bot))
            }

            'M' -> {
                val size = term.size()
                val mid = (buf.topRow + (size.rows - 1) / 2 + 1).coerceAtMost(buf.rowCount)
                moveTo(buf, ViMotions.gotoLine(buf, mid))
            }

            '%' -> {
                ViMotions.matchBracket(buf)?.let { jumpTo(buf, it) } ?: buf
            }

            'i' -> {
                buf.copy(mode = ViMode.INSERT)
            }

            'I' -> {
                val p = ViMotions.firstNonBlank(buf)
                buf.copy(mode = ViMode.INSERT, cursorRow = p.row, cursorCol = p.col, desiredCol = p.col)
            }

            'a' -> {
                val line = buf.lines[buf.cursorRow]
                val nc = if (line.isEmpty()) 0 else (buf.cursorCol + 1).coerceAtMost(line.length)
                buf.copy(mode = ViMode.INSERT, cursorCol = nc, desiredCol = nc)
            }

            'A' -> {
                val line = buf.lines[buf.cursorRow]
                buf.copy(mode = ViMode.INSERT, cursorCol = line.length, desiredCol = line.length)
            }

            'o' -> {
                ViEdits.openBelow(buf)
            }

            'O' -> {
                ViEdits.openAbove(buf)
            }

            'x' -> {
                ViEdits.deleteCharAtCursor(buf, count)
            }

            'X' -> {
                ViEdits.deleteCharBefore(buf, count)
            }

            'D' -> {
                ViEdits.deleteToEol(buf)
            }

            'C' -> {
                ViEdits.changeToEol(buf)
            }

            'Y' -> {
                ViEdits.yankLine(buf, count)
            }

            's' -> {
                ViEdits.substituteChar(buf, count)
            }

            'S' -> {
                ViEdits.changeLines(buf, count)
            }

            'r' -> {
                pending = Pending.REPLACE_CHAR
                buf
            }

            '~' -> {
                ViEdits.toggleCase(buf, count)
            }

            'p' -> {
                ViEdits.pasteAfter(buf, pendingRegister, count).also { pendingRegister = null }
            }

            'P' -> {
                ViEdits.pasteBefore(buf, pendingRegister, count).also { pendingRegister = null }
            }

            'd' -> {
                buf.copy(pendingOp = PendingOp.D, pendingCount = count)
            }

            'c' -> {
                buf.copy(pendingOp = PendingOp.C, pendingCount = count)
            }

            'y' -> {
                buf.copy(pendingOp = PendingOp.Y, pendingCount = count)
            }

            'u' -> {
                buf.undo()
            }

            'f' -> {
                pending = Pending.FIND_F
                buf
            }

            'F' -> {
                pending = Pending.FIND_F_BACK
                buf
            }

            't' -> {
                pending = Pending.FIND_T
                buf
            }

            'T' -> {
                pending = Pending.FIND_T_BACK
                buf
            }

            ';' -> {
                repeatFind(buf, reverse = false, count)
            }

            ',' -> {
                repeatFind(buf, reverse = true, count)
            }

            'm' -> {
                pending = Pending.SET_MARK
                buf
            }

            '\'' -> {
                pending = Pending.JUMP_MARK_LINE
                buf
            }

            '`' -> {
                pending = Pending.JUMP_MARK_POS
                buf
            }

            '"' -> {
                pending = Pending.REGISTER_NAME
                buf
            }

            'v' -> {
                buf.copy(mode = ViMode.VISUAL_CHAR, visualAnchor = Pos(buf.cursorRow, buf.cursorCol))
            }

            'V' -> {
                buf.copy(mode = ViMode.VISUAL_LINE, visualAnchor = Pos(buf.cursorRow, buf.cursorCol))
            }

            '/' -> {
                handleSearch(buf, forward = true)
            }

            '?' -> {
                handleSearch(buf, forward = false)
            }

            'n' -> {
                repeatSearch(buf, reverse = false)
            }

            'N' -> {
                repeatSearch(buf, reverse = true)
            }

            ':' -> {
                return handleEx(buf)
            }

            'Z' -> {
                pending = Pending.Z_PREFIX
                buf
            }

            else -> {
                buf
            }
        }
    }

    private suspend fun handleNormalCtrl(
        buf: ViBuffer,
        letter: Char,
        count: Int,
    ): ViBuffer? =
        when (letter) {
            'D' -> {
                val h = (term.size().rows / 2).coerceAtLeast(1)
                moveDownPreserveCol(buf, h)
            }

            'U' -> {
                val h = (term.size().rows / 2).coerceAtLeast(1)
                moveUpPreserveCol(buf, h)
            }

            'F' -> {
                val h = (term.size().rows - 2).coerceAtLeast(1)
                moveDownPreserveCol(buf, h)
            }

            'B' -> {
                val h = (term.size().rows - 2).coerceAtLeast(1)
                moveUpPreserveCol(buf, h)
            }

            'R' -> {
                buf.redo()
            }

            'C' -> {
                buf.copy(pendingOp = null, pendingCount = 0)
            }

            'L' -> {
                buf
            }

            // forced redraw
            else -> {
                buf
            }
        }

    // ---------- Operator + Motion ----------

    private suspend fun handleOperatorMotion(
        buf: ViBuffer,
        key: Key,
        count: Int,
    ): ViBuffer? {
        val op = buf.pendingOp!!
        val cleared = buf.copy(pendingOp = null, pendingCount = 0)
        if (key is Key.Char) {
            val ch = key.codepoint.toChar()
            // Doubled form: dd / cc / yy
            val opChar =
                when (op) {
                    PendingOp.D -> 'd'
                    PendingOp.C -> 'c'
                    PendingOp.Y -> 'y'
                }
            if (ch == opChar) {
                return applyLineOp(cleared, op, count)
            }
            // Compute motion target
            val target: Pos? =
                when (ch) {
                    'h' -> {
                        ViMotions.left(cleared, count)
                    }

                    'l' -> {
                        ViMotions.right(cleared, count)
                    }

                    'w' -> {
                        if (op == PendingOp.C) {
                            // POSIX vi: `cw` and `cW` act like `ce`/`cE` — change to end of current word only.
                            ViMotions.wordEnd(cleared, count, big = false)
                        } else {
                            // For d/y, treat `w` as up to (but excluding) next word start. At end-of-line
                            // with no next word, extend to end of line.
                            val tgt = ViMotions.nextWordStart(cleared, count, big = false)
                            val curEnd = ViMotions.lineEnd(cleared)
                            if (tgt.row == cleared.cursorRow && tgt.col >= curEnd.col) {
                                curEnd
                            } else if (tgt.row != cleared.cursorRow) {
                                // motion crossed a line — bound to end of current line for char-range
                                Pos(cleared.cursorRow, (cleared.lineLen(cleared.cursorRow) - 1).coerceAtLeast(0))
                            } else {
                                Pos(tgt.row, (tgt.col - 1).coerceAtLeast(0))
                            }
                        }
                    }

                    'W' -> {
                        if (op == PendingOp.C) {
                            ViMotions.wordEnd(cleared, count, big = true)
                        } else {
                            val tgt = ViMotions.nextWordStart(cleared, count, big = true)
                            val curEnd = ViMotions.lineEnd(cleared)
                            if (tgt.row == cleared.cursorRow && tgt.col >= curEnd.col) {
                                curEnd
                            } else if (tgt.row !=
                                cleared.cursorRow
                            ) {
                                Pos(cleared.cursorRow, (cleared.lineLen(cleared.cursorRow) - 1).coerceAtLeast(0))
                            } else {
                                Pos(tgt.row, (tgt.col - 1).coerceAtLeast(0))
                            }
                        }
                    }

                    'b' -> {
                        ViMotions.prevWordStart(cleared, count, big = false)
                    }

                    'B' -> {
                        ViMotions.prevWordStart(cleared, count, big = true)
                    }

                    'e' -> {
                        ViMotions.wordEnd(cleared, count, big = false)
                    }

                    'E' -> {
                        ViMotions.wordEnd(cleared, count, big = true)
                    }

                    '0' -> {
                        ViMotions.lineStart(cleared)
                    }

                    '^' -> {
                        ViMotions.firstNonBlank(cleared)
                    }

                    '$' -> {
                        ViMotions.lineEnd(cleared)
                    }

                    'G' -> {
                        val target = if (count > 1) count else cleared.rowCount
                        ViMotions.gotoLine(cleared, target)
                    }

                    '%' -> {
                        ViMotions.matchBracket(cleared)
                    }

                    else -> {
                        null
                    }
                }
            if (target == null) return cleared // unknown motion → cancel op
            val from = Pos(cleared.cursorRow, cleared.cursorCol)
            // If the motion crosses rows and the operator is line-wise-favored,
            // we still treat the range char-wise (POSIX vi behaviour for d/c/y + motion).
            return applyRangeOp(cleared, op, from, target)
        }
        return cleared
    }

    private fun applyLineOp(
        buf: ViBuffer,
        op: PendingOp,
        count: Int,
    ): ViBuffer =
        when (op) {
            PendingOp.D -> ViEdits.deleteLines(buf, count, pendingRegister).also { pendingRegister = null }
            PendingOp.C -> ViEdits.changeLines(buf, count, pendingRegister).also { pendingRegister = null }
            PendingOp.Y -> ViEdits.yankLine(buf, count, pendingRegister).also { pendingRegister = null }
        }

    private fun applyRangeOp(
        buf: ViBuffer,
        op: PendingOp,
        from: Pos,
        to: Pos,
    ): ViBuffer {
        val reg = pendingRegister
        pendingRegister = null
        return when (op) {
            PendingOp.D -> {
                ViEdits.deleteCharRange(buf, from, to, reg)
            }

            PendingOp.Y -> {
                ViEdits.yankCharRange(buf, from, to, reg)
            }

            PendingOp.C -> {
                // After the delete, place the cursor at the start of the removed range so the
                // subsequent insertions go in the right place. deleteCharRange's NORMAL-mode
                // clamp can push the cursor one cell left when it lands past EOL.
                val deleted = ViEdits.deleteCharRange(buf, from, to, reg)
                val targetCol = minOf(from.col, to.col).coerceAtMost(deleted.lineLen(deleted.cursorRow))
                deleted.copy(mode = ViMode.INSERT, cursorCol = targetCol, desiredCol = targetCol)
            }
        }
    }

    // ---------- VISUAL ----------

    private suspend fun handleVisual(
        buf: ViBuffer,
        key: Key,
    ): ViBuffer? {
        if (key == Key.Named.ESC || (key is Key.Ctrl && key.letter == 'C')) {
            return buf.copy(mode = ViMode.NORMAL, visualAnchor = null)
        }
        if (key is Key.Char) {
            val ch = key.codepoint.toChar()
            val anchor = buf.visualAnchor ?: Pos(buf.cursorRow, buf.cursorCol)
            // Apply operators
            when (ch) {
                'd', 'x' -> {
                    return doVisualOp(buf, anchor, PendingOp.D)
                }

                'c', 's' -> {
                    return doVisualOp(buf, anchor, PendingOp.C)
                }

                'y' -> {
                    return doVisualOp(buf, anchor, PendingOp.Y)
                }

                '~' -> {
                    val (lo, hi) =
                        if (anchor <=
                            Pos(buf.cursorRow, buf.cursorCol)
                        ) {
                            anchor to Pos(buf.cursorRow, buf.cursorCol)
                        } else {
                            Pos(buf.cursorRow, buf.cursorCol) to anchor
                        }
                    if (buf.mode == ViMode.VISUAL_LINE) {
                        // toggle case across whole lines
                        val b = buf.pushUndo()
                        val newLines = b.lines.toMutableList()
                        for (r in lo.row..hi.row) {
                            val l = newLines[r]
                            newLines[r] =
                                buildString {
                                    for (c in l) {
                                        append(
                                            when {
                                                c.isUpperCase() -> c.lowercaseChar()
                                                c.isLowerCase() -> c.uppercaseChar()
                                                else -> c
                                            },
                                        )
                                    }
                                }
                        }
                        return b.copy(lines = newLines, mode = ViMode.NORMAL, visualAnchor = null, dirty = true)
                    } else {
                        val b = buf.pushUndo()
                        val newLines = b.lines.toMutableList()
                        for (r in lo.row..hi.row) {
                            val l = newLines[r]
                            val startC = if (r == lo.row) lo.col else 0
                            val endC = if (r == hi.row) (hi.col + 1).coerceAtMost(l.length) else l.length
                            val sb = StringBuilder(l)
                            for (c in startC until endC) {
                                val cc = sb[c]
                                sb[c] =
                                    when {
                                        cc.isUpperCase() -> cc.lowercaseChar()
                                        cc.isLowerCase() -> cc.uppercaseChar()
                                        else -> cc
                                    }
                            }
                            newLines[r] = sb.toString()
                        }
                        return b.copy(lines = newLines, mode = ViMode.NORMAL, visualAnchor = null, dirty = true)
                    }
                }

                'v' -> {
                    return if (buf.mode == ViMode.VISUAL_CHAR) {
                        buf.copy(mode = ViMode.NORMAL, visualAnchor = null)
                    } else {
                        buf.copy(mode = ViMode.VISUAL_CHAR)
                    }
                }

                'V' -> {
                    return if (buf.mode == ViMode.VISUAL_LINE) {
                        buf.copy(mode = ViMode.NORMAL, visualAnchor = null)
                    } else {
                        buf.copy(mode = ViMode.VISUAL_LINE)
                    }
                }

                else -> {}
            }
            // Otherwise treat as a motion-in-normal-mode
        }
        // Use normal-mode motion handler, but stay in visual mode after
        val saved = buf.mode
        val asNormal = buf.copy(mode = ViMode.NORMAL)
        val next = handleNormal(asNormal, key) ?: return null
        return next.copy(mode = saved, visualAnchor = buf.visualAnchor)
    }

    private fun doVisualOp(
        buf: ViBuffer,
        anchor: Pos,
        op: PendingOp,
    ): ViBuffer {
        val cur = Pos(buf.cursorRow, buf.cursorCol)
        val (lo, hi) = if (anchor <= cur) anchor to cur else cur to anchor
        val reg = pendingRegister
        pendingRegister = null
        val cleared = buf.copy(mode = ViMode.NORMAL, visualAnchor = null)
        return when (buf.mode) {
            ViMode.VISUAL_LINE -> {
                when (op) {
                    PendingOp.D -> {
                        ViEdits.deleteLineRange(cleared, lo.row, hi.row, reg)
                    }

                    PendingOp.Y -> {
                        ViEdits.yankLineRange(cleared, lo.row, hi.row, reg)
                    }

                    PendingOp.C -> {
                        val deleted = ViEdits.deleteLineRange(cleared, lo.row, hi.row, reg)
                        // After deletion all lines are gone — open one and insert
                        val withEmpty =
                            deleted.copy(
                                lines = deleted.lines.toMutableList().apply { add(lo.row, "") },
                                cursorRow = lo.row,
                                cursorCol = 0,
                                mode = ViMode.INSERT,
                            )
                        withEmpty
                    }
                }
            }

            else -> {
                when (op) {
                    PendingOp.D -> {
                        ViEdits.deleteCharRange(cleared, lo, hi, reg)
                    }

                    PendingOp.Y -> {
                        ViEdits.yankCharRange(cleared, lo, hi, reg)
                    }

                    PendingOp.C -> {
                        val deleted = ViEdits.deleteCharRange(cleared, lo, hi, reg)
                        val tCol = lo.col.coerceAtMost(deleted.lineLen(deleted.cursorRow))
                        deleted.copy(mode = ViMode.INSERT, cursorCol = tCol, desiredCol = tCol)
                    }
                }
            }
        }
    }

    // ---------- Pending operand-key consumers ----------

    private suspend fun handlePending(
        buf: ViBuffer,
        key: Key,
    ): ViBuffer? {
        val p = pending
        pending = Pending.NONE
        if (key == Key.Named.ESC) return buf
        when (p) {
            Pending.REPLACE_CHAR -> {
                if (key is Key.Char) return ViEdits.replaceChar(buf, key.codepoint)
                return buf
            }

            Pending.FIND_F, Pending.FIND_T, Pending.FIND_F_BACK, Pending.FIND_T_BACK -> {
                if (key !is Key.Char) return buf
                val till = (p == Pending.FIND_T || p == Pending.FIND_T_BACK)
                val forward = (p == Pending.FIND_F || p == Pending.FIND_T)
                val pos =
                    if (forward) {
                        ViMotions.findCharForward(buf, key.codepoint, till)
                    } else {
                        ViMotions.findCharBackward(buf, key.codepoint, till)
                    }
                val lf = LastFind(key.codepoint, forward, till)
                return if (pos == null) {
                    buf.copy(lastFind = lf)
                } else {
                    buf.copy(cursorRow = pos.row, cursorCol = pos.col, desiredCol = pos.col, lastFind = lf)
                }
            }

            Pending.SET_MARK -> {
                if (key is Key.Char) {
                    val ch = key.codepoint.toChar()
                    if (ch in 'a'..'z') {
                        return buf.copy(marks = buf.marks + (ch to Mark(buf.cursorRow, buf.cursorCol)))
                    }
                }
                return buf
            }

            Pending.JUMP_MARK_LINE, Pending.JUMP_MARK_POS -> {
                if (key is Key.Char) {
                    val ch = key.codepoint.toChar()
                    // `''` / ` ` ` — jump back to position before the last big motion.
                    if (ch == '\'' || ch == '`') {
                        val back = buf.lastJumpPos ?: return buf
                        val here = Pos(buf.cursorRow, buf.cursorCol)
                        val row = back.row.coerceIn(0, buf.rowCount - 1)
                        val col =
                            if (p == Pending.JUMP_MARK_POS) {
                                back.col.coerceAtMost(buf.lineLen(row))
                            } else {
                                val ln = buf.lines.getOrNull(row) ?: return buf
                                ln.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                            }
                        return buf.copy(cursorRow = row, cursorCol = col, desiredCol = col, lastJumpPos = here)
                    }
                    val mark = buf.marks[ch] ?: return buf
                    val col =
                        if (p == Pending.JUMP_MARK_POS) {
                            mark.col
                        } else {
                            val ln = buf.lines.getOrNull(mark.row) ?: return buf
                            ln.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
                        }
                    val row = mark.row.coerceIn(0, buf.rowCount - 1)
                    val here = Pos(buf.cursorRow, buf.cursorCol)
                    return buf.copy(
                        cursorRow = row,
                        cursorCol = col.coerceAtMost(buf.lineLen(row)),
                        desiredCol = col,
                        lastJumpPos = here,
                    )
                }
                return buf
            }

            Pending.Z_PREFIX -> {
                if (key is Key.Char) {
                    when (key.codepoint.toChar()) {
                        'Z' -> {
                            // Save (if dirty / known filename) then quit.
                            val saved = if (buf.dirty || buf.filename != null) doSave(buf, null) else buf
                            if (saved.dirty) {
                                // Save failed (e.g. no filename) — stay in editor.
                                return saved
                            }
                            return null
                        }

                        'Q' -> {
                            // Force-quit, discard changes.
                            return null
                        }

                        else -> {
                            return buf
                        }
                    }
                }
                return buf
            }

            Pending.REGISTER_NAME -> {
                if (key is Key.Char) {
                    val ch = key.codepoint.toChar()
                    if (ch in 'a'..'z' || ch == '"') {
                        pendingRegister = ch
                    }
                }
                return buf
            }

            Pending.GOTO_G -> {
                if (key is Key.Char) {
                    val ch = key.codepoint.toChar()
                    if (ch == 'g') {
                        // gg → first line first non-blank
                        return jumpTo(buf, ViMotions.docStart(buf))
                    }
                }
                return buf
            }

            Pending.NONE -> {
                return buf
            }
        }
    }

    // ---------- Helpers ----------

    private fun moveTo(
        buf: ViBuffer,
        p: Pos,
    ): ViBuffer = buf.copy(cursorRow = p.row, cursorCol = p.col, desiredCol = p.col)

    /** Like [moveTo] but records the current cursor as `lastJumpPos` so `''`/` ` ` can return to it. */
    private fun jumpTo(
        buf: ViBuffer,
        p: Pos,
    ): ViBuffer {
        val here = Pos(buf.cursorRow, buf.cursorCol)
        return buf.copy(cursorRow = p.row, cursorCol = p.col, desiredCol = p.col, lastJumpPos = here)
    }

    private fun moveUpPreserveCol(
        buf: ViBuffer,
        count: Int,
    ): ViBuffer {
        val p = ViMotions.up(buf, count)
        return buf.copy(cursorRow = p.row, cursorCol = p.col)
    }

    private fun moveDownPreserveCol(
        buf: ViBuffer,
        count: Int,
    ): ViBuffer {
        val p = ViMotions.down(buf, count)
        return buf.copy(cursorRow = p.row, cursorCol = p.col)
    }

    private fun pageUp(buf: ViBuffer): ViBuffer {
        val h = (term.size().rows - 2).coerceAtLeast(1)
        return moveUpPreserveCol(buf, h)
    }

    private fun pageDown(buf: ViBuffer): ViBuffer {
        val h = (term.size().rows - 2).coerceAtLeast(1)
        return moveDownPreserveCol(buf, h)
    }

    private fun repeatFind(
        buf: ViBuffer,
        reverse: Boolean,
        count: Int,
    ): ViBuffer {
        val lf = buf.lastFind ?: return buf
        val forward = if (reverse) !lf.forward else lf.forward
        val pos =
            if (forward) {
                ViMotions.findCharForward(buf, lf.char, lf.till, count)
            } else {
                ViMotions.findCharBackward(buf, lf.char, lf.till, count)
            }
        return pos?.let { buf.copy(cursorRow = it.row, cursorCol = it.col, desiredCol = it.col) } ?: buf
    }

    private suspend fun handleSearch(
        buf: ViBuffer,
        forward: Boolean,
    ): ViBuffer {
        val prompt = if (forward) "/" else "?"
        val q = minibuffer.readLine(prompt) ?: return buf
        if (q.isEmpty()) return buf
        val pos =
            if (forward) {
                ViMotions.searchForward(buf, q, buf.cursorRow, buf.cursorCol)
            } else {
                ViMotions.searchBackward(buf, q, buf.cursorRow, buf.cursorCol)
            }
        return if (pos == null) {
            notice = "Pattern not found: $q"
            buf.copy(lastSearch = LastSearch(q, forward))
        } else {
            val here = Pos(buf.cursorRow, buf.cursorCol)
            buf.copy(
                cursorRow = pos.row,
                cursorCol = pos.col,
                desiredCol = pos.col,
                lastSearch = LastSearch(q, forward),
                lastJumpPos = here,
            )
        }
    }

    private fun repeatSearch(
        buf: ViBuffer,
        reverse: Boolean,
    ): ViBuffer {
        val ls = buf.lastSearch ?: return buf
        val forward = if (reverse) !ls.forward else ls.forward
        val pos =
            if (forward) {
                ViMotions.searchForward(buf, ls.pattern, buf.cursorRow, buf.cursorCol)
            } else {
                ViMotions.searchBackward(buf, ls.pattern, buf.cursorRow, buf.cursorCol)
            }
        return if (pos == null) {
            notice = "Pattern not found: ${ls.pattern}"
            buf
        } else {
            val here = Pos(buf.cursorRow, buf.cursorCol)
            buf.copy(cursorRow = pos.row, cursorCol = pos.col, desiredCol = pos.col, lastJumpPos = here)
        }
    }

    private suspend fun handleEx(buf: ViBuffer): ViBuffer? {
        val raw = minibuffer.readLine(":") ?: return buf
        if (raw.isEmpty()) return buf
        val parsed = ViExCommands.parse(raw)
        val result = ViExCommands.execute(parsed, buf)
        var b = result.buf
        if (result.openFile != null) {
            val loaded = loadFile(result.openFile)
            if (loaded != null) b = loaded
        }
        if (result.saveRequested) {
            b = doSave(b, result.saveAs)
            if (result.quit && b.dirty && !result.forceQuit) return b
        }
        if (result.quit) return null
        if (result.message.isNotEmpty()) notice = result.message
        return b
    }

    // ---------- File I/O ----------

    private fun resolve(path: String): String =
        if (path.startsWith("/")) {
            path
        } else if (cwd.endsWith("/")) {
            "$cwd$path"
        } else {
            "$cwd/$path"
        }

    private suspend fun loadInitial(): ViBuffer? {
        val name = initialFile ?: return ViBuffer.empty(null)
        return loadFile(name) ?: ViBuffer.empty(name)
    }

    private suspend fun loadFile(name: String): ViBuffer? {
        val resolved = resolve(name)
        return try {
            if (!fs.exists(resolved)) {
                notice = "\"$name\" [New File]"
                ViBuffer.empty(name)
            } else if (fs.isDirectory(resolved)) {
                notice = "\"$name\" is a directory"
                ViBuffer.empty(null)
            } else {
                val bytes = fs.readBytes(resolved)
                val text = bytes.decodeToString()
                ViBuffer.fromText(text, name).also {
                    notice = "\"$name\" ${it.lines.size}L, ${bytes.size}B"
                }
            }
        } catch (t: Throwable) {
            notice = "Error reading $name: ${t.message}"
            null
        }
    }

    private suspend fun doSave(
        buf: ViBuffer,
        saveAs: String?,
    ): ViBuffer {
        val path = saveAs ?: buf.filename
        if (path.isNullOrEmpty()) {
            notice = "No file name"
            return buf
        }
        val resolved = resolve(path)
        return try {
            val joined = buf.lines.joinToString("\n") + if (buf.trailingNewline) "\n" else ""
            fs.writeBytes(resolved, joined.encodeToByteArray())
            notice = "\"$path\" ${buf.lines.size}L written"
            buf.copy(filename = path, dirty = false)
        } catch (t: Throwable) {
            notice = "Could not write $path: ${t.message}"
            buf
        }
    }
}
