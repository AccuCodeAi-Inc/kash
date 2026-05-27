package com.accucodeai.kash.ui

import androidx.compose.ui.graphics.Color

/**
 * One cell of the terminal grid: a single codepoint plus visual style.
 * Width-1 in v1 — no wide-glyph / CJK handling yet.
 */
public data class Cell(
    val ch: Char = ' ',
    val style: CellStyle = CellStyle.Default,
) {
    public companion object {
        public val Blank: Cell = Cell()
    }
}

public data class CellStyle(
    val fg: Color? = null,
    val bg: Color? = null,
    val inverse: Boolean = false,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
) {
    public companion object {
        public val Default: CellStyle = CellStyle()
    }
}

/**
 * 2D character grid backing the wasmJs ComposeTerminal.
 *
 * Two buffers (main + alt) make alternate-screen suspend/restore byte-exact:
 * `enterAltScreen()` snapshots the cursor and flips the active reference;
 * `exitAltScreen()` flips back. The main buffer is never touched while on
 * alt, so a full-screen editor (nano) exits leaving the prior `ls` output
 * and prompt position visually identical to before it opened. This is the
 * headline correctness bar of the rewrite.
 *
 * Scrollback is a bounded ring of rows that scrolled off the top of `main`.
 * It is read by the renderer when the user scrolls up; nothing else reads
 * it. We never capture from `alt` — full-screen apps own their viewport.
 *
 * Single-threaded model (wasmJs is single-threaded), so no synchronization.
 */
public class TerminalGrid(
    initialCols: Int,
    initialRows: Int,
    public val scrollbackCap: Int = 1000,
) {
    public var cols: Int = initialCols.coerceAtLeast(1)
        private set
    public var rows: Int = initialRows.coerceAtLeast(1)
        private set

    public var cursorRow: Int = 0
    public var cursorCol: Int = 0
    public var cursorVisible: Boolean = true
    public var currentStyle: CellStyle = CellStyle.Default

    private var mainBuf: Array<Array<Cell>> = makeBuffer(rows, cols)
    private var altBuf: Array<Array<Cell>> = makeBuffer(rows, cols)
    public var onAlt: Boolean = false
        private set

    public val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    // Wrap flags parallel to [mainBuf] and [scrollback]. `mainBufWrapped[r]`
    // is true when the cursor auto-wrapped off the right edge of row r into
    // row r+1 — i.e. that row continues onto the next. Reflow uses this
    // (instead of the "last column non-blank" heuristic, which falsely
    // splices unrelated lines when a command happens to fill the screen
    // exactly). scrollback rows carry their wrap flag with them when they
    // get pushed off the top of mainBuf via [scrollUp] / [eraseInDisplay].
    private var mainBufWrapped: BooleanArray = BooleanArray(rows)
    private val scrollbackWrapped: ArrayDeque<Boolean> = ArrayDeque()

    /**
     * Total number of rows EVER appended to scrollback (since this grid
     * was created). Monotonic — drops from the front of the ring (when
     * capacity is reached) do NOT decrement this. The renderer uses the
     * delta between snapshots to keep a scrolled-up viewport anchored on
     * the same content even when new output rotates the ring underneath.
     * This is the same trick xterm.js / WezTerm / ghostty use to keep
     * scrollback view positions stable across writes.
     */
    public var scrollbackTotalAdded: Long = 0L
        private set

    // Saved cursor pos for ESC 7 / ESC 8 (DECSC/DECRC) and for the alt
    // screen round-trip. We snapshot on `enterAltScreen()` so an exit
    // restores the pre-entry cursor position even if the alt-mode app
    // never issued an explicit DECSC/DECRC.
    private var savedMainCursorRow: Int = 0
    private var savedMainCursorCol: Int = 0
    private var savedDecCursorRow: Int = 0
    private var savedDecCursorCol: Int = 0
    private var savedDecStyle: CellStyle = CellStyle.Default

    private fun active(): Array<Array<Cell>> = if (onAlt) altBuf else mainBuf

    public fun row(r: Int): Array<Cell> = active()[r.coerceIn(0, rows - 1)]

    public fun activeBuffer(): Array<Array<Cell>> = active()

    public fun resize(
        newCols: Int,
        newRows: Int,
    ) {
        val c = newCols.coerceAtLeast(1)
        val r = newRows.coerceAtLeast(1)
        if (c == cols && r == rows) return

        // Alt-screen: full-screen apps own this buffer and repaint from
        // their own model on the next frame. Just resize; don't reflow.
        // Main buffer / scrollback are still on the old layout for now
        // and get reflowed on the next main-screen resize.
        if (onAlt) {
            altBuf = reshape(altBuf, rows, cols, r, c)
            mainBuf = reshape(mainBuf, rows, cols, r, c)
            mainBufWrapped = mainBufWrapped.copyOf(r)
            for (i in scrollback.indices) {
                val old = scrollback[i]
                scrollback[i] = Array(c) { j -> if (j < old.size) old[j] else Cell.Blank }
            }
            cols = c
            rows = r
            cursorRow = cursorRow.coerceIn(0, rows - 1)
            cursorCol = cursorCol.coerceIn(0, cols - 1)
            return
        }

        // Main-screen reflow. Walk scrollback + visible as one stream,
        // grouping consecutive rows where `wrapped[i] == true` into a
        // single logical line, then re-wrap each line at the new column
        // count. Using the wrap flag (set by [put] on real auto-wrap) is
        // exact — the old "last column non-blank" heuristic merged
        // unrelated lines whenever a command happened to fill the screen
        // exactly. The cursor's logical position (line index + character
        // offset within it) is tracked so it lands on the equivalent cell
        // post-reflow.
        // Determine the last meaningful row of mainBuf — anything below
        // is unwritten padding that should NOT enter the reflow stream.
        // If we included it, those rows would emit one empty logical line
        // each, padding the output and pushing the real content (now
        // wrapped to more rows at narrower cols) entirely into scrollback,
        // leaving mainBuf full of blanks.
        var lastContentRow = -1
        for (r in mainBuf.indices) {
            val row = mainBuf[r]
            var hasContent = false
            for (col in row.indices) {
                if (row[col].ch != ' ') {
                    hasContent = true
                    break
                }
            }
            if (hasContent) lastContentRow = r
        }
        val mainBufLastInclude = maxOf(cursorRow, lastContentRow)

        val combined = ArrayList<Array<Cell>>(scrollback.size + mainBufLastInclude + 1)
        val combinedWrapped = ArrayList<Boolean>(scrollback.size + mainBufLastInclude + 1)
        combined.addAll(scrollback)
        combinedWrapped.addAll(scrollbackWrapped)
        for (i in 0..mainBufLastInclude) {
            combined.add(mainBuf[i])
            combinedWrapped.add(mainBufWrapped.getOrElse(i) { false })
        }
        val cursorCombinedRow = scrollback.size + cursorRow

        data class Logical(
            val cells: MutableList<Cell>,
        )

        val logical = ArrayList<Logical>()
        var current = Logical(ArrayList())
        var cursorLogicalIdx = -1
        var cursorLogicalOffset = -1
        for ((rIdx, row) in combined.withIndex()) {
            val wrappedToNext = combinedWrapped[rIdx]
            // For non-wrapped (terminator) rows, drop trailing spaces —
            // they're padding, not content the user expects to keep. For
            // wrapped rows, every cell is content by definition (the
            // cursor auto-wrapped off the right edge, so cols-1 is the
            // last written character).
            val end =
                if (wrappedToNext) {
                    row.size.coerceAtMost(cols)
                } else {
                    var e = row.size.coerceAtMost(cols)
                    while (e > 0 && row[e - 1].ch == ' ') e--
                    e
                }
            val rowStartOffset = current.cells.size
            for (i in 0 until end) current.cells.add(row[i])
            if (rIdx == cursorCombinedRow) {
                cursorLogicalIdx = logical.size
                cursorLogicalOffset = rowStartOffset + cursorCol.coerceAtMost(row.size)
            }
            if (!wrappedToNext) {
                logical.add(current)
                current = Logical(ArrayList())
            }
        }
        if (current.cells.isNotEmpty() || cursorLogicalIdx == logical.size) {
            logical.add(current)
        }

        // Re-emit each logical line at the new width, recording per-output-
        // row wrap flags as we go.
        val output = ArrayList<Array<Cell>>(combined.size)
        val outputWrapped = ArrayList<Boolean>(combined.size)
        var cursorNewRow = -1
        var cursorNewCol = 0
        for ((idx, line) in logical.withIndex()) {
            val lineStartRow = output.size
            if (line.cells.isEmpty()) {
                output.add(Array(c) { Cell.Blank })
                outputWrapped.add(false)
                if (idx == cursorLogicalIdx) {
                    cursorNewRow = lineStartRow
                    cursorNewCol = 0
                }
                continue
            }
            var i = 0
            while (i < line.cells.size) {
                val take = minOf(c, line.cells.size - i)
                output.add(Array(c) { j -> if (j < take) line.cells[i + j] else Cell.Blank })
                val isLastChunk = (i + take) >= line.cells.size
                outputWrapped.add(!isLastChunk)
                i += take
            }
            if (idx == cursorLogicalIdx) {
                val off = cursorLogicalOffset.coerceAtLeast(0)
                cursorNewRow = lineStartRow + (off / c)
                cursorNewCol = off % c
            }
        }
        if (cursorNewRow < 0) {
            cursorNewRow = (output.size - 1).coerceAtLeast(0)
            cursorNewCol = 0
        }

        // Split: bottom r rows become the new visible; the rest goes to
        // scrollback (capped). If output is shorter than r, pad mainBuf
        // with blank rows at the bottom.
        val newMainStart = (output.size - r).coerceAtLeast(0)
        scrollback.clear()
        scrollbackWrapped.clear()
        for (i in 0 until newMainStart) {
            scrollback.addLast(output[i])
            scrollbackWrapped.addLast(outputWrapped[i])
        }
        while (scrollback.size > scrollbackCap) {
            scrollback.removeFirst()
            scrollbackWrapped.removeFirst()
        }
        mainBuf =
            Array(r) { rowIdx ->
                val srcIdx = newMainStart + rowIdx
                if (srcIdx < output.size) output[srcIdx] else Array(c) { Cell.Blank }
            }
        mainBufWrapped =
            BooleanArray(r) { rowIdx ->
                val srcIdx = newMainStart + rowIdx
                srcIdx < outputWrapped.size && outputWrapped[srcIdx]
            }
        altBuf = reshape(altBuf, rows, cols, r, c)

        cols = c
        rows = r
        cursorRow = (cursorNewRow - newMainStart).coerceIn(0, r - 1)
        cursorCol = cursorNewCol.coerceIn(0, c - 1)
    }

    /** Write a printable character at the cursor and advance. Wraps + scrolls. */
    public fun put(ch: Char) {
        if (cursorCol >= cols) {
            // Auto-wrap: record on the row we're leaving so reflow can
            // tell this row continues onto the next. Skipped on alt.
            if (!onAlt && cursorRow in mainBufWrapped.indices) {
                mainBufWrapped[cursorRow] = true
            }
            cursorCol = 0
            cursorRow++
            if (cursorRow >= rows) {
                scrollUp(1)
                cursorRow = rows - 1
            }
        }
        active()[cursorRow][cursorCol] = Cell(ch, currentStyle)
        cursorCol++
    }

    /** CR — move to column 0. */
    public fun carriageReturn() {
        cursorCol = 0
    }

    /** LF — advance one row; scroll if past bottom. */
    public fun lineFeed() {
        cursorRow++
        if (cursorRow >= rows) {
            scrollUp(1)
            cursorRow = rows - 1
        }
    }

    /** BS — move back one column, clamped at 0. Does not erase. */
    public fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    /** Horizontal tab — advance to next multiple of 8, clamped. */
    public fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = next.coerceAtMost(cols - 1)
    }

    public fun moveTo(
        row: Int,
        col: Int,
    ) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, cols - 1)
    }

    public fun moveBy(
        dRow: Int,
        dCol: Int,
    ) {
        moveTo(cursorRow + dRow, cursorCol + dCol)
    }

    /**
     * Erase in line:
     *  - 0 (default): cursor to EOL
     *  - 1: BOL to cursor (inclusive)
     *  - 2: whole line
     */
    public fun eraseInLine(mode: Int) {
        val line = active()[cursorRow]
        val blank = Cell(' ', currentStyle.copy(inverse = false))
        when (mode) {
            1 -> for (i in 0..cursorCol.coerceAtMost(cols - 1)) line[i] = blank
            2 -> for (i in 0 until cols) line[i] = blank
            else -> for (i in cursorCol until cols) line[i] = blank
        }
    }

    /**
     * Erase in display:
     *  - 0 (default): cursor to end of screen
     *  - 1: start of screen to cursor (inclusive)
     *  - 2: entire screen; on the main screen, the rows being cleared are
     *    first appended to scrollback (matching xterm / iTerm / GNOME
     *    terminal — `clear` and resize-redraws don't lose visible
     *    content). Skipped on alt-screen, which never feeds scrollback.
     *  - 3: same as 2 but also drops the entire scrollback (xterm).
     */
    public fun eraseInDisplay(mode: Int) {
        val buf = active()
        val blank = Cell(' ', currentStyle.copy(inverse = false))
        when (mode) {
            1 -> {
                for (r in 0 until cursorRow) for (c in 0 until cols) buf[r][c] = blank
                val line = buf[cursorRow]
                for (c in 0..cursorCol.coerceAtMost(cols - 1)) line[c] = blank
            }

            2, 3 -> {
                // Preserve cleared rows by promoting them to scrollback.
                // Without this, bash's Ctrl-L (sent on resize) silently
                // discards the last screen of output. Skip purely-blank
                // rows so an already-empty screen doesn't pad scrollback
                // with empties on every clear.
                if (!onAlt) {
                    var added = 0
                    for (r in 0 until rows) {
                        val row = buf[r]
                        var hasContent = false
                        for (c in 0 until cols) {
                            if (row[c].ch != ' ') {
                                hasContent = true
                                break
                            }
                        }
                        if (!hasContent) continue
                        scrollback.addLast(row.copyOf())
                        scrollbackWrapped.addLast(mainBufWrapped[r])
                        added++
                    }
                    while (scrollback.size > scrollbackCap) {
                        scrollback.removeFirst()
                        scrollbackWrapped.removeFirst()
                    }
                    scrollbackTotalAdded += added.toLong()
                    for (r in 0 until rows) mainBufWrapped[r] = false
                }
                for (r in 0 until rows) for (c in 0 until cols) buf[r][c] = blank
                if (mode == 3 && !onAlt) {
                    scrollback.clear()
                    scrollbackWrapped.clear()
                }
            }

            else -> {
                val line = buf[cursorRow]
                for (c in cursorCol until cols) line[c] = blank
                for (r in (cursorRow + 1) until rows) for (c in 0 until cols) buf[r][c] = blank
            }
        }
    }

    /**
     * Scroll the active region up by [n] rows. Lost rows from the top of
     * `main` migrate into scrollback (capped). `alt` discards them.
     */
    public fun scrollUp(n: Int) {
        val buf = active()
        val k = n.coerceAtMost(rows)
        if (k <= 0) return
        if (!onAlt) {
            repeat(k) { i ->
                scrollback.addLast(buf[i])
                scrollbackWrapped.addLast(mainBufWrapped[i])
                while (scrollback.size > scrollbackCap) {
                    scrollback.removeFirst()
                    scrollbackWrapped.removeFirst()
                }
            }
            scrollbackTotalAdded += k.toLong()
        }
        // Shift remaining rows + wrap flags up.
        for (r in 0 until rows - k) buf[r] = buf[r + k]
        if (!onAlt) {
            for (r in 0 until rows - k) mainBufWrapped[r] = mainBufWrapped[r + k]
            for (r in rows - k until rows) mainBufWrapped[r] = false
        }
        // New blank rows at the bottom.
        val blank = Cell(' ', currentStyle.copy(inverse = false))
        for (r in rows - k until rows) buf[r] = Array(cols) { blank }
    }

    /** ESC 7 / DECSC — save cursor + attributes. */
    public fun saveCursor() {
        savedDecCursorRow = cursorRow
        savedDecCursorCol = cursorCol
        savedDecStyle = currentStyle
    }

    /** ESC 8 / DECRC — restore cursor + attributes. */
    public fun restoreCursor() {
        cursorRow = savedDecCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedDecCursorCol.coerceIn(0, cols - 1)
        currentStyle = savedDecStyle
    }

    /**
     * Enter alternate screen. Snapshots the main cursor (so we can restore
     * exactly on exit), switches the active buffer to alt, and clears alt.
     * Idempotent.
     */
    public fun enterAltScreen() {
        if (onAlt) return
        savedMainCursorRow = cursorRow
        savedMainCursorCol = cursorCol
        onAlt = true
        // Clear alt to a known state — apps that enter expect a blank canvas.
        for (r in 0 until rows) for (c in 0 until cols) altBuf[r][c] = Cell.Blank
        cursorRow = 0
        cursorCol = 0
    }

    /**
     * Exit alternate screen. Switches back to main and restores the cursor
     * to its pre-entry position. Main buffer was untouched while on alt,
     * so the visual result is byte-exact restoration. Idempotent.
     */
    public fun exitAltScreen() {
        if (!onAlt) return
        onAlt = false
        cursorRow = savedMainCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedMainCursorCol.coerceIn(0, cols - 1)
    }

    private fun makeBuffer(
        r: Int,
        c: Int,
    ): Array<Array<Cell>> = Array(r) { Array(c) { Cell.Blank } }

    private fun reshape(
        old: Array<Array<Cell>>,
        oldRows: Int,
        oldCols: Int,
        newRows: Int,
        newCols: Int,
    ): Array<Array<Cell>> {
        val out = Array(newRows) { Array(newCols) { Cell.Blank } }
        val rCopy = minOf(oldRows, newRows)
        val cCopy = minOf(oldCols, newCols)
        for (r in 0 until rCopy) for (c in 0 until cCopy) out[r][c] = old[r][c]
        return out
    }
}
