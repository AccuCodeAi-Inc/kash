package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.terminal.TerminalControl

internal object Ansi {
    // ESC byte (0x1B). Kept as a code-derived char so the source stays
    // 7-bit ASCII and survives any tool that mangles literal control bytes
    // when echoing the file contents back.
    private const val ESC: Char = ''
    val CLEAR_SCREEN: String = "$ESC[2J"
    val CURSOR_HOME: String = "$ESC[H"
    val CLEAR_TO_EOL: String = "$ESC[K"
    val RESET: String = "$ESC[0m"
    val INVERSE: String = "$ESC[7m"
    val HIDE_CURSOR: String = "$ESC[?25l"
    val SHOW_CURSOR: String = "$ESC[?25h"

    /** 1-indexed row, col per CSI convention. */
    fun moveTo(
        row: Int,
        col: Int,
    ): String = "$ESC[$row;${col}H"
}

/**
 * Full-screen pager redraw. The body takes rows 1..(rows-1); the bottom row
 * is the status / prompt bar. Matches against [LessBuffer.highlightPattern]
 * are wrapped in `INVERSE`/`RESET` inline.
 *
 * v1: no horizontal scroll. Lines longer than the screen are truncated.
 */
internal class LessRenderer(
    private val term: TerminalControl,
) {
    suspend fun draw(
        buf: LessBuffer,
        statusOverride: String? = null,
    ) {
        val size = term.size()
        val cols = size.cols.coerceAtLeast(20)
        val rows = size.rows.coerceAtLeast(3)
        val bodyHeight = rows - 1

        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.CURSOR_HOME)

        // Body
        val gutterWidth =
            if (buf.showLineNumbers) {
                val maxLine = buf.rowCount.coerceAtLeast(1)
                maxLine.toString().length + 1
            } else {
                0
            }
        val contentCols = (cols - gutterWidth).coerceAtLeast(1)
        for (i in 0 until bodyHeight) {
            val row = buf.top + i
            sb.append(Ansi.moveTo(i + 1, 1))
            sb.append(Ansi.CLEAR_TO_EOL)
            if (row < buf.rowCount) {
                if (buf.showLineNumbers) {
                    val n = (row + 1).toString().padStart(gutterWidth - 1)
                    sb.append(n).append(' ')
                }
                val line = buf.lines[row]
                val visible = if (line.length > contentCols) line.substring(0, contentCols) else line
                sb.append(renderLineWithHighlights(visible, buf.highlightPattern))
            }
        }

        // Status bar
        sb.append(Ansi.moveTo(rows, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        sb.append(Ansi.INVERSE)
        val status = statusOverride ?: defaultStatus(buf, cols)
        val truncated = if (status.length > cols) status.substring(0, cols) else status
        sb.append(truncated)
        sb.append(Ansi.RESET)

        sb.append(Ansi.HIDE_CURSOR)
        term.write(sb.toString())
        term.flush()
    }

    /**
     * Wrap each regex match in INVERSE/RESET so the user can see which
     * occurrences of the most recent search live in the current frame.
     * If [pattern] is null, the line is emitted verbatim.
     */
    private fun renderLineWithHighlights(
        line: String,
        pattern: Regex?,
    ): String {
        if (pattern == null) return line
        val matches = pattern.findAll(line).toList()
        if (matches.isEmpty()) return line
        val out = StringBuilder()
        var cursor = 0
        for (m in matches) {
            if (m.range.first < cursor) continue // overlapping match — skip
            if (m.range.first >= line.length) break
            out.append(line, cursor, m.range.first)
            out.append(Ansi.INVERSE)
            out.append(line, m.range.first, m.range.last + 1)
            out.append(Ansi.RESET)
            cursor = m.range.last + 1
        }
        if (cursor < line.length) out.append(line, cursor, line.length)
        return out.toString()
    }

    private fun defaultStatus(
        buf: LessBuffer,
        cols: Int,
    ): String {
        val first = buf.top + 1
        val last = (buf.top + buf.bodyHeight).coerceAtMost(buf.rowCount)
        val total = buf.rowCount
        val pct = if (total <= 0) 0 else ((last.toLong() * 100L) / total).toInt()
        val name = buf.filename ?: "(stdin)"
        val eof = last >= total
        val tail = if (eof) " (END)" else ""
        val body = "$name  lines $first-$last/$total  $pct%$tail"
        return if (body.length > cols) body.substring(0, cols) else body
    }
}
