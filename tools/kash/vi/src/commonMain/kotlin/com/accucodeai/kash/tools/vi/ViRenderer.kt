package com.accucodeai.kash.tools.vi

import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Full-screen redraw. Mirrors nano's renderer. Reserves the bottom row for
 * status / command-line input. Optional line numbers along the gutter when
 * [ViBuffer.showLineNumbers] is set.
 */
internal class ViRenderer(
    private val term: TerminalControl,
) {
    private val bottomRows = 1

    suspend fun draw(
        buf: ViBuffer,
        notice: String,
        commandLine: String? = null,
    ) {
        val size = term.size()
        val cols = size.cols.coerceAtLeast(20)
        val rows = size.rows.coerceAtLeast(bottomRows + 2)
        val bodyHeight = rows - bottomRows
        val statusRow = rows

        val gutterWidth = if (buf.showLineNumbers) ((buf.rowCount.toString().length) + 1).coerceAtLeast(4) else 0

        val effectiveTop =
            when {
                buf.cursorRow < buf.topRow -> buf.cursorRow
                buf.cursorRow >= buf.topRow + bodyHeight -> buf.cursorRow - bodyHeight + 1
                else -> buf.topRow
            }

        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.CURSOR_HOME)

        // Body
        for (i in 0 until bodyHeight) {
            val row = effectiveTop + i
            sb.append(Ansi.moveTo(i + 1, 1))
            sb.append(Ansi.CLEAR_TO_EOL)
            if (row < buf.rowCount) {
                if (buf.showLineNumbers) {
                    val num = (row + 1).toString().padStart(gutterWidth - 1) + " "
                    sb.append(num)
                }
                val line = buf.lines[row]
                val avail = cols - gutterWidth
                if (avail > 0) {
                    sb.append(if (line.length > avail) line.substring(0, avail) else line)
                }
            } else {
                // vi uses "~" for past-EOF rows
                sb.append("~")
            }
        }

        // Status / command line
        sb.append(Ansi.moveTo(statusRow, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        if (commandLine != null) {
            // ex / search command line
            val truncated =
                if (commandLine.length >
                    cols
                ) {
                    commandLine.substring(commandLine.length - cols)
                } else {
                    commandLine
                }
            sb.append(truncated)
        } else {
            sb.append(Ansi.INVERSE)
            sb.append(statusLine(buf, notice, cols))
            sb.append(Ansi.RESET)
        }

        // Cursor placement (in body)
        if (commandLine == null) {
            val cy = (buf.cursorRow - effectiveTop) + 1
            val cx = (buf.cursorCol + gutterWidth + 1).coerceAtMost(cols)
            sb.append(Ansi.moveTo(cy.coerceAtLeast(1), cx))
        } else {
            sb.append(Ansi.moveTo(statusRow, (commandLine.length + 1).coerceAtMost(cols)))
        }
        sb.append(Ansi.SHOW_CURSOR)
        term.write(sb.toString())
        term.flush()
    }

    private fun statusLine(
        buf: ViBuffer,
        notice: String,
        cols: Int,
    ): String {
        val modeTag =
            when (buf.mode) {
                ViMode.INSERT -> "-- INSERT --"
                ViMode.VISUAL_CHAR -> "-- VISUAL --"
                ViMode.VISUAL_LINE -> "-- VISUAL LINE --"
                ViMode.COMMAND -> ""
                ViMode.NORMAL -> ""
            }
        val name = buf.filename ?: "[New File]"
        val dirty = if (buf.dirty) " [+]" else ""
        val pos = "${buf.cursorRow + 1},${buf.cursorCol + 1}"
        val msg = notice.ifEmpty { modeTag }
        val left = "$msg  \"$name\"$dirty"
        val right = pos
        val pad = (cols - left.length - right.length).coerceAtLeast(1)
        val combined = left + " ".repeat(pad) + right
        return if (combined.length > cols) combined.substring(0, cols) else combined
    }
}
