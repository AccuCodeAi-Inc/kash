package com.accucodeai.kash.tools.vi

/**
 * Pure motion functions. Each returns the new (row, col). Motions never mutate
 * the buffer text — operators (d/c/y) compose a motion + a slice to build
 * a range.
 *
 * Word definition: vi distinguishes "word" (`w/b/e`) from "WORD" (`W/B/E`):
 *  - word = run of letters+digits+underscore (or run of punctuation)
 *  - WORD = run of non-whitespace
 *
 * Motion semantics here follow POSIX vi closely; absolute conformance with
 * GNU vim is non-goal — the bar is "muscle memory works".
 */
internal object ViMotions {
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    private fun charClass(c: Char): Int =
        when {
            c.isWhitespace() -> 0
            isWordChar(c) -> 1
            else -> 2
        }

    // ---------- Horizontal ----------

    fun left(
        buf: ViBuffer,
        count: Int = 1,
    ): Pos {
        val c = (buf.cursorCol - count).coerceAtLeast(0)
        return Pos(buf.cursorRow, c)
    }

    fun right(
        buf: ViBuffer,
        count: Int = 1,
    ): Pos {
        val len = buf.lineLen(buf.cursorRow)
        val maxCol = if (buf.mode == ViMode.INSERT) len else (len - 1).coerceAtLeast(0)
        val c = (buf.cursorCol + count).coerceAtMost(maxCol)
        return Pos(buf.cursorRow, c)
    }

    fun up(
        buf: ViBuffer,
        count: Int = 1,
    ): Pos {
        val r = (buf.cursorRow - count).coerceAtLeast(0)
        val want = if (buf.desiredCol > buf.cursorCol) buf.desiredCol else buf.cursorCol
        val maxCol = (buf.lineLen(r) - 1).coerceAtLeast(0)
        return Pos(r, want.coerceAtMost(maxCol))
    }

    fun down(
        buf: ViBuffer,
        count: Int = 1,
    ): Pos {
        val r = (buf.cursorRow + count).coerceAtMost(buf.rowCount - 1)
        val want = if (buf.desiredCol > buf.cursorCol) buf.desiredCol else buf.cursorCol
        val maxCol = (buf.lineLen(r) - 1).coerceAtLeast(0)
        return Pos(r, want.coerceAtMost(maxCol))
    }

    fun lineStart(buf: ViBuffer): Pos = Pos(buf.cursorRow, 0)

    fun lineEnd(buf: ViBuffer): Pos {
        val len = buf.lineLen(buf.cursorRow)
        return Pos(buf.cursorRow, (len - 1).coerceAtLeast(0))
    }

    /** First non-blank on current line (`^`). */
    fun firstNonBlank(buf: ViBuffer): Pos {
        val line = buf.lines[buf.cursorRow]
        val idx =
            line.indexOfFirst { !it.isWhitespace() }.let {
                if (it <
                    0
                ) {
                    (line.length - 1).coerceAtLeast(0)
                } else {
                    it
                }
            }
        return Pos(buf.cursorRow, idx)
    }

    // ---------- Word motions ----------

    fun nextWordStart(
        buf: ViBuffer,
        count: Int = 1,
        big: Boolean = false,
    ): Pos {
        var (r, c) = buf.cursorRow to buf.cursorCol
        repeat(count) {
            val (nr, nc) = advanceWordStart(buf, r, c, big)
            r = nr
            c = nc
        }
        return Pos(r, c)
    }

    private fun advanceWordStart(
        buf: ViBuffer,
        startRow: Int,
        startCol: Int,
        big: Boolean,
    ): Pair<Int, Int> {
        var r = startRow
        var c = startCol
        val curLine = buf.lines[r]
        val startClass =
            if (c <
                curLine.length
            ) {
                (if (big) (if (curLine[c].isWhitespace()) 0 else 1) else charClass(curLine[c]))
            } else {
                0
            }
        // Skip rest of current "word"
        while (c < curLine.length) {
            val cls =
                if (big) (if (curLine[c].isWhitespace()) 0 else 1) else charClass(curLine[c])
            if (cls != startClass) break
            c++
        }
        // Skip whitespace (possibly across lines)
        while (true) {
            val line = buf.lines[r]
            while (c < line.length && line[c].isWhitespace()) c++
            if (c < line.length) return r to c
            // wrap to next line
            if (r >= buf.rowCount - 1) return r to (line.length - 1).coerceAtLeast(0)
            r++
            c = 0
            // Loop continues — next iteration will skip leading whitespace on the new line.
        }
    }

    fun prevWordStart(
        buf: ViBuffer,
        count: Int = 1,
        big: Boolean = false,
    ): Pos {
        var r = buf.cursorRow
        var c = buf.cursorCol
        repeat(count) {
            val (nr, nc) = retreatWordStart(buf, r, c, big)
            r = nr
            c = nc
        }
        return Pos(r, c)
    }

    private fun retreatWordStart(
        buf: ViBuffer,
        startRow: Int,
        startCol: Int,
        big: Boolean,
    ): Pair<Int, Int> {
        var r = startRow
        var c = startCol - 1
        // Skip whitespace backwards
        while (true) {
            if (c < 0) {
                if (r == 0) return 0 to 0
                r--
                c = buf.lines[r].length - 1
                if (c < 0) continue
            }
            val line = buf.lines[r]
            if (c >= 0 && !line[c].isWhitespace()) break
            c--
        }
        val line = buf.lines[r]
        val cls = if (big) 1 else charClass(line[c])
        while (c > 0) {
            val prev = line[c - 1]
            val pCls = if (big) (if (prev.isWhitespace()) 0 else 1) else charClass(prev)
            if (pCls != cls) break
            c--
        }
        return r to c
    }

    fun wordEnd(
        buf: ViBuffer,
        count: Int = 1,
        big: Boolean = false,
    ): Pos {
        var r = buf.cursorRow
        var c = buf.cursorCol
        repeat(count) {
            val (nr, nc) = advanceWordEnd(buf, r, c, big)
            r = nr
            c = nc
        }
        return Pos(r, c)
    }

    private fun advanceWordEnd(
        buf: ViBuffer,
        startRow: Int,
        startCol: Int,
        big: Boolean,
    ): Pair<Int, Int> {
        var r = startRow
        var c = startCol + 1
        // skip whitespace
        while (true) {
            if (r >= buf.rowCount) return buf.rowCount - 1 to (buf.lineLen(buf.rowCount - 1) - 1).coerceAtLeast(0)
            val line = buf.lines[r]
            if (c >= line.length) {
                if (r >= buf.rowCount - 1) return r to (line.length - 1).coerceAtLeast(0)
                r++
                c = 0
                continue
            }
            if (!line[c].isWhitespace()) break
            c++
        }
        val line = buf.lines[r]
        val cls = if (big) 1 else charClass(line[c])
        while (c < line.length - 1) {
            val next = line[c + 1]
            val nCls = if (big) (if (next.isWhitespace()) 0 else 1) else charClass(next)
            if (nCls != cls) break
            c++
        }
        return r to c
    }

    // ---------- f/F/t/T ----------

    fun findCharForward(
        buf: ViBuffer,
        cp: Int,
        till: Boolean,
        count: Int = 1,
    ): Pos? {
        val line = buf.lines[buf.cursorRow]
        var c = buf.cursorCol + 1
        var hits = 0
        val target = codepointToString(cp)
        while (c < line.length) {
            if (line.substring(c, (c + target.length).coerceAtMost(line.length)) == target) {
                hits++
                if (hits == count) {
                    return Pos(buf.cursorRow, if (till) c - 1 else c)
                }
                c += target.length
            } else {
                c++
            }
        }
        return null
    }

    fun findCharBackward(
        buf: ViBuffer,
        cp: Int,
        till: Boolean,
        count: Int = 1,
    ): Pos? {
        val line = buf.lines[buf.cursorRow]
        var c = buf.cursorCol - 1
        var hits = 0
        val target = codepointToString(cp)
        while (c >= 0) {
            if (c + target.length <= line.length && line.substring(c, c + target.length) == target) {
                hits++
                if (hits == count) {
                    return Pos(buf.cursorRow, if (till) c + 1 else c)
                }
            }
            c--
        }
        return null
    }

    // ---------- Document positions ----------

    fun docStart(buf: ViBuffer): Pos {
        val line = buf.lines[0]
        val idx = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        return Pos(0, idx)
    }

    fun docEnd(buf: ViBuffer): Pos {
        val r = buf.rowCount - 1
        val line = buf.lines[r]
        val idx = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        return Pos(r, idx)
    }

    fun gotoLine(
        buf: ViBuffer,
        line: Int,
    ): Pos {
        val r = (line - 1).coerceIn(0, buf.rowCount - 1)
        val ln = buf.lines[r]
        val idx = ln.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        return Pos(r, idx)
    }

    // ---------- Bracket match (%) ----------
    private val openers = "([{"
    private val closers = ")]}"

    fun matchBracket(buf: ViBuffer): Pos? {
        val line = buf.lines[buf.cursorRow]
        // Find a bracket at or after cursorCol on the current line
        var c = buf.cursorCol
        while (c < line.length && line[c] !in "()[]{}") c++
        if (c >= line.length) return null
        val ch = line[c]
        val (target, dir) =
            when (ch) {
                '(' -> ')' to 1
                '[' -> ']' to 1
                '{' -> '}' to 1
                ')' -> '(' to -1
                ']' -> '[' to -1
                '}' -> '{' to -1
                else -> return null
            }
        var depth = 1
        var r = buf.cursorRow
        var col = c
        while (true) {
            col += dir
            if (col < 0 || col >= buf.lines[r].length) {
                r += dir
                if (r < 0 || r >= buf.rowCount) return null
                col = if (dir > 0) 0 else buf.lines[r].length - 1
                if (col < 0) continue
            }
            val cur = buf.lines[r][col]
            if (cur == ch) {
                depth++
            } else if (cur == target) {
                depth--
                if (depth == 0) return Pos(r, col)
            }
        }
    }

    // ---------- Search via regex ----------

    /**
     * Find next match starting strictly after (row,col) — wraps to top on miss.
     * Pattern is a regex. Returns the match position (start) or null.
     */
    fun searchForward(
        buf: ViBuffer,
        pattern: String,
        startRow: Int,
        startCol: Int,
    ): Pos? {
        val rx = compile(pattern) ?: return null
        // After current pos
        run {
            val line = buf.lines[startRow]
            val m = rx.find(line, (startCol + 1).coerceAtMost(line.length))
            if (m != null) return Pos(startRow, m.range.first)
        }
        for (r in (startRow + 1) until buf.rowCount) {
            val m = rx.find(buf.lines[r])
            if (m != null) return Pos(r, m.range.first)
        }
        for (r in 0..startRow) {
            val m = rx.find(buf.lines[r])
            if (m != null) return Pos(r, m.range.first)
        }
        return null
    }

    fun searchBackward(
        buf: ViBuffer,
        pattern: String,
        startRow: Int,
        startCol: Int,
    ): Pos? {
        val rx = compile(pattern) ?: return null
        // Current line up to startCol
        run {
            val line = buf.lines[startRow]
            val end = startCol.coerceAtMost(line.length)
            val matches = rx.findAll(line.substring(0, end))
            val last = matches.lastOrNull()
            if (last != null) return Pos(startRow, last.range.first)
        }
        for (r in (startRow - 1) downTo 0) {
            val last = rx.findAll(buf.lines[r]).lastOrNull()
            if (last != null) return Pos(r, last.range.first)
        }
        for (r in (buf.rowCount - 1) downTo startRow) {
            val last = rx.findAll(buf.lines[r]).lastOrNull()
            if (last != null) return Pos(r, last.range.first)
        }
        return null
    }

    private fun compile(pat: String): Regex? = runCatching { Regex(pat) }.getOrNull()
}
