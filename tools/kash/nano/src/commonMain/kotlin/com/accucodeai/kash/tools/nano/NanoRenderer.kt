package com.accucodeai.kash.tools.nano

import com.accucodeai.kash.api.terminal.TerminalControl

internal object Ansi {
    const val CLEAR_SCREEN: String = "[2J"
    const val CURSOR_HOME: String = "[H"
    const val CLEAR_TO_EOL: String = "[K"
    const val RESET: String = "[0m"
    const val INVERSE: String = "[7m"
    const val HIDE_CURSOR: String = "[?25l"
    const val SHOW_CURSOR: String = "[?25h"

    /** 1-indexed row, col per CSI convention. */
    fun moveTo(
        row: Int,
        col: Int,
    ): String = "[$row;${col}H"
}

/**
 * Full-screen redraw. Not diff-based — for v1 we repaint every frame.
 * At 60 lines × 120 cols a redraw is ~7 KB of bytes; even at one
 * redraw-per-keystroke the throughput is fine. The status / shortcut bar
 * rows are reserved at the bottom; the editor body takes rows 1..(rows-3).
 */
internal class NanoRenderer(
    private val term: TerminalControl,
) {
    /**
     * Reserved screen rows for the chrome:
     *  - 1 top title bar (filename, modified flag, [HELP])
     *  - 1 status/notice line just above the shortcut bar
     *  - 2 shortcut-hint lines at the bottom
     */
    private val topRows = 1
    private val bottomRows = 3

    suspend fun draw(
        buf: NanoBuffer,
        notice: String,
    ) {
        val size = term.size()
        val cols = size.cols.coerceAtLeast(20)
        val rows = size.rows.coerceAtLeast(topRows + bottomRows + 1)
        val bodyTop = topRows + 1 // 1-indexed first body row
        val bodyHeight = rows - topRows - bottomRows
        val statusRow = rows - bottomRows + 1
        val short1Row = rows - 1
        val short2Row = rows

        // Adjust viewport so the cursor row sits in [topRow, topRow+bodyHeight-1].
        val effectiveTop =
            when {
                buf.cursorRow < buf.topRow -> buf.cursorRow
                buf.cursorRow >= buf.topRow + bodyHeight -> buf.cursorRow - bodyHeight + 1
                else -> buf.topRow
            }

        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.CURSOR_HOME)

        // ----- Title bar (inverse) -----
        val title = titleBar(buf, cols)
        sb
            .append(Ansi.INVERSE)
            .append(title)
            .append(Ansi.RESET)
            .append(Ansi.CLEAR_TO_EOL)
            .append("\r\n")

        // ----- Body -----
        for (i in 0 until bodyHeight) {
            val row = effectiveTop + i
            sb.append(Ansi.moveTo(bodyTop + i, 1))
            sb.append(Ansi.CLEAR_TO_EOL)
            if (row < buf.rowCount) {
                val line = buf.lines[row]
                // Truncate to cols (1-cell per codepoint v1 model).
                sb.append(if (line.length > cols) line.substring(0, cols) else line)
            } else {
                // Empty area — nano-style tilde for past-EOF rows.
                // (We omit the tilde for v1; just blank.)
            }
        }

        // ----- Notice line -----
        sb.append(Ansi.moveTo(statusRow, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        if (notice.isNotEmpty()) {
            val n = if (notice.length > cols) notice.substring(0, cols) else notice
            sb.append(n)
        }

        // ----- Shortcut bars -----
        sb.append(Ansi.moveTo(short1Row, 1)).append(Ansi.CLEAR_TO_EOL)
        sb.append(shortcuts(row1 = true, cols = cols))
        sb.append(Ansi.moveTo(short2Row, 1)).append(Ansi.CLEAR_TO_EOL)
        sb.append(shortcuts(row1 = false, cols = cols))

        // ----- Cursor placement -----
        val cy = bodyTop + (buf.cursorRow - effectiveTop)
        val cx = (buf.cursorCol + 1).coerceAtMost(cols)
        sb.append(Ansi.moveTo(cy, cx))
        sb.append(Ansi.SHOW_CURSOR)

        term.write(sb.toString())
        term.flush()
    }

    private fun titleBar(
        buf: NanoBuffer,
        cols: Int,
    ): String {
        val name = buf.filename ?: "[New Buffer]"
        val dirty = if (buf.dirty) " *Modified*" else ""
        val title = "  kash nano  "
        val center = "File: $name$dirty"
        val padLeft = ((cols - center.length) / 2).coerceAtLeast(0)
        val left = title.padEnd(padLeft + title.length).take(padLeft)
        val combined = (left + center)
        return if (combined.length >= cols) {
            combined.substring(0, cols)
        } else {
            combined.padEnd(cols)
        }
    }

    /**
     * Two-row shortcut bar laid out in fixed-width columns so a row1 cell
     * always lines up directly above the row2 cell in the same column —
     * matches GNU nano's bottom helpline.
     */
    private val helpColumns: List<Pair<Pair<String, String>, Pair<String, String>>> =
        listOf(
            ("^G" to "Help") to ("^A" to "Home"),
            ("^O" to "WriteOut") to ("^E" to "End"),
            ("^W" to "Where Is") to ("^U" to "Paste"),
            ("^K" to "Cut") to ("^L" to "Refresh"),
            ("^X" to "Exit") to ("^S" to "Save"),
        )

    private fun shortcuts(
        row1: Boolean,
        cols: Int,
    ): String {
        val sb = StringBuilder()
        // Track the *visible* width (ANSI escapes don't occupy cells) and
        // stop adding columns once the next one — plus its leading
        // separator — would overflow the line. A shortcut bar wider than
        // `cols` auto-wraps onto an extra row in the terminal, which shoves
        // every row below it down and corrupts nano's fixed-row layout
        // (most visible on narrow / mobile viewports). GNU nano likewise
        // drops trailing shortcuts when the terminal is too narrow.
        var visible = 0
        for (col in helpColumns) {
            val (top, bot) = col
            val topPlain = top.first.length + 1 + top.second.length // "^G Help" → 7
            val botPlain = bot.first.length + 1 + bot.second.length
            val cellWidth = maxOf(topPlain, botPlain)
            val sep = if (visible == 0) 0 else 2
            if (visible + sep + cellWidth > cols) break
            if (sep > 0) sb.append("  ")
            val (myKey, myLabel) = if (row1) top else bot
            val plainLen = myKey.length + 1 + myLabel.length
            val pad = (cellWidth - plainLen).coerceAtLeast(0)
            sb.append("${Ansi.INVERSE}$myKey${Ansi.RESET} $myLabel")
            sb.append(" ".repeat(pad))
            visible += sep + cellWidth
        }
        return sb.toString()
    }
}
