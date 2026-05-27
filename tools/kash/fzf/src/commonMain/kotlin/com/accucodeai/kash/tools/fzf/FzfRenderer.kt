package com.accucodeai.kash.tools.fzf

import com.accucodeai.kash.api.terminal.TerminalControl

internal object Ansi {
    private const val ESC: String = ""
    val CLEAR_SCREEN: String = "$ESC[2J"
    val CURSOR_HOME: String = "$ESC[H"
    val CLEAR_TO_EOL: String = "$ESC[K"
    val RESET: String = "$ESC[0m"
    val INVERSE: String = "$ESC[7m"
    val BOLD_GREEN: String = "$ESC[1;32m"
    val DIM: String = "$ESC[2m"
    val HIDE_CURSOR: String = "$ESC[?25l"
    val SHOW_CURSOR: String = "$ESC[?25h"

    fun moveTo(
        row: Int,
        col: Int,
    ): String = "$ESC[$row;${col}H"
}

/**
 * Full-screen redraw, fzf-style top-down layout:
 *
 *  - row 1: prompt + query + cursor
 *  - row 2: status `  N/M  (selected K)`
 *  - rows 3..rows: filtered candidates, current row in inverse video,
 *    matched query positions in bold green
 *
 * We repaint everything every frame. The candidate list count is bounded by
 * terminal height, so this is small.
 */
internal class FzfRenderer(
    private val term: TerminalControl,
    private val prompt: String,
    private val multi: Boolean,
) {
    suspend fun draw(state: FzfState) {
        val size = term.size()
        val cols = size.cols.coerceAtLeast(20)
        val rows = size.rows.coerceAtLeast(4)
        val listRows = rows - 2

        val (top, _) = computeScroll(state, listRows)

        val sb = StringBuilder()
        sb.append(Ansi.HIDE_CURSOR)
        sb.append(Ansi.CURSOR_HOME)
        sb.append(Ansi.CLEAR_SCREEN)

        // Row 1: prompt + query
        sb.append(Ansi.moveTo(1, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        val promptLine = prompt + state.query
        sb.append(promptLine.take(cols))

        // Row 2: status line
        sb.append(Ansi.moveTo(2, 1))
        sb.append(Ansi.CLEAR_TO_EOL)
        val status = buildStatus(state)
        sb.append(Ansi.DIM).append(status.take(cols)).append(Ansi.RESET)

        // Rows 3..rows: list
        for (r in 0 until listRows) {
            val idx = top + r
            sb.append(Ansi.moveTo(3 + r, 1))
            sb.append(Ansi.CLEAR_TO_EOL)
            if (idx >= state.filtered.size) continue
            val ranked = state.filtered[idx]
            val isCursor = idx == state.cursor
            val isSelected = multi && ranked.index in state.selected
            renderRow(sb, ranked, isCursor, isSelected, cols)
        }

        // Place the terminal cursor at the prompt position so input feels live.
        val cursorCol = (prompt.length + state.query.length + 1).coerceAtMost(cols)
        sb.append(Ansi.moveTo(1, cursorCol))
        sb.append(Ansi.SHOW_CURSOR)

        term.write(sb.toString())
        term.flush()
    }

    private fun buildStatus(state: FzfState): String {
        val sb = StringBuilder()
        sb.append("  ")
        sb.append(state.filteredCount).append('/').append(state.totalCount)
        if (multi) {
            sb.append("  (").append(state.selectedCount).append(" selected)")
        }
        return sb.toString()
    }

    private fun renderRow(
        out: StringBuilder,
        ranked: FzfMatcher.Ranked,
        isCursor: Boolean,
        isSelected: Boolean,
        cols: Int,
    ) {
        // Marker column: ">" for cursor, " " otherwise. Then "*" for selected
        // in multi mode, else " ". Then the candidate text with highlights.
        val markerLen = if (multi) 3 else 2
        val maxText = (cols - markerLen).coerceAtLeast(1)

        if (isCursor) out.append(Ansi.INVERSE)
        out.append(if (isCursor) ">" else " ")
        if (multi) {
            out.append(if (isSelected) "*" else " ")
        }
        out.append(' ')

        val text = ranked.text
        val cps = text.toCodePoints()
        val take = minOf(cps.size, maxText)
        val positionsSet = ranked.positions.toHashSet()
        var i = 0
        while (i < take) {
            val highlight = i in positionsSet
            if (highlight) out.append(Ansi.BOLD_GREEN)
            // Use codepoint-aware emission.
            val sb = StringBuilder()
            sb.appendCodePoint(cps[i])
            out.append(sb)
            if (highlight) {
                out.append(Ansi.RESET)
                // Restore inverse for the cursor row so it persists through
                // the rest of the line.
                if (isCursor) out.append(Ansi.INVERSE)
            }
            i++
        }
        if (isCursor) out.append(Ansi.RESET)
    }

    private fun computeScroll(
        state: FzfState,
        listRows: Int,
    ): Pair<Int, Int> {
        if (state.filtered.isEmpty()) return 0 to 0
        val cur = state.cursor.coerceIn(0, state.filtered.size - 1)
        var top = state.topOfList
        if (cur < top) top = cur
        if (cur >= top + listRows) top = cur - listRows + 1
        if (top < 0) top = 0
        return top to (cur - top)
    }
}
