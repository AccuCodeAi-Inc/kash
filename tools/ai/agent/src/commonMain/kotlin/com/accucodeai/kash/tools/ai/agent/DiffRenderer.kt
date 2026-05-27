package com.accucodeai.kash.tools.ai.agent

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8

/**
 * Tiny line-based diff + renderer used to visualize `write_file` edits in
 * the agent transcript. Not a general-purpose diff library — sized for
 * the typical agent edit (a single file, dozens to a few hundred lines).
 *
 * The algorithm is straight LCS (dynamic programming, O(n·m) memory).
 * For the size of edits we expect (≤ ~1k lines per side), that's a few
 * hundred KB at worst — fine for an interactive session, and avoids the
 * complexity of Myers / patience-diff for what's ultimately a display
 * concern. If we ever start agenting against megabyte files we'll
 * revisit.
 */
internal object DiffRenderer {
    /**
     * One row of a unified-diff hunk. `Same` rows are shown as context
     * (dim, no marker); `Delete` is red with a `-`; `Insert` is green
     * with a `+`. Context is collapsed to ≤ [CONTEXT_LINES] above and
     * below each change cluster.
     */
    internal sealed interface DiffLine {
        val text: String

        data class Same(
            override val text: String,
        ) : DiffLine

        data class Delete(
            override val text: String,
        ) : DiffLine

        data class Insert(
            override val text: String,
        ) : DiffLine
    }

    private const val CONTEXT_LINES: Int = 3

    /**
     * Render a unified-diff style hunk between [before] and [after] to
     * [out]. The output is framed by a header line ("edit: path · +N -M
     * lines") and a closing rule; lines inside the frame use the
     * supplied [palette].
     *
     * If the file didn't exist before (the model is creating it), pass
     * `before = null` — we render the whole new content as inserts with
     * a "new file" badge in the header.
     */
    suspend fun render(
        out: SuspendSink,
        path: String,
        before: String?,
        after: String,
        glyphs: AgentGlyphs,
        palette: Palette,
    ) {
        val beforeLines = before?.lines() ?: emptyList()
        val afterLines = after.lines()
        val isNewFile = before == null
        val script =
            if (isNewFile) {
                // Every line is an insertion — short-circuit the LCS.
                afterLines.map { DiffLine.Insert(it) }
            } else {
                diff(beforeLines, afterLines)
            }
        val (inserts, deletes) =
            script.fold(0 to 0) { (i, d), line ->
                when (line) {
                    is DiffLine.Insert -> (i + 1) to d
                    is DiffLine.Delete -> i to (d + 1)
                    is DiffLine.Same -> i to d
                }
            }
        val hunks = collapseToHunks(script)

        writeHeader(out, path, isNewFile, inserts, deletes, glyphs, palette)
        for ((idx, hunk) in hunks.withIndex()) {
            if (idx > 0) {
                // Separator between non-contiguous hunks — a single
                // ellipsis-like rule inside the frame.
                out.writeUtf8("${palette.dim}${glyphs.codeBoxVertical}${palette.reset}\n")
            }
            for (line in hunk) {
                writeLine(out, line, glyphs, palette)
            }
        }
        writeFooter(out, glyphs, palette)
    }

    /**
     * Collapse the full diff script into a list of hunks, each containing
     * up to [CONTEXT_LINES] of unchanged context on either side of the
     * actual changes. Unchanged runs longer than 2×CONTEXT_LINES become
     * hunk boundaries.
     */
    private fun collapseToHunks(script: List<DiffLine>): List<List<DiffLine>> {
        if (script.none { it !is DiffLine.Same }) return emptyList()
        val hunks = mutableListOf<MutableList<DiffLine>>()
        var current: MutableList<DiffLine>? = null
        var sameRun = 0
        // First pass: emit changed-or-near-changed lines into hunks.
        for ((idx, line) in script.withIndex()) {
            val nearChange =
                line !is DiffLine.Same ||
                    isNearChange(script, idx, CONTEXT_LINES)
            if (nearChange) {
                if (current == null) {
                    current = mutableListOf()
                    hunks.add(current)
                }
                current.add(line)
                sameRun = 0
            } else {
                sameRun++
                if (current != null && sameRun > CONTEXT_LINES) {
                    current = null
                }
            }
        }
        return hunks
    }

    private fun isNearChange(
        script: List<DiffLine>,
        idx: Int,
        radius: Int,
    ): Boolean {
        val lo = (idx - radius).coerceAtLeast(0)
        val hi = (idx + radius).coerceAtMost(script.size - 1)
        for (i in lo..hi) {
            if (script[i] !is DiffLine.Same) return true
        }
        return false
    }

    /**
     * Build the diff script via straight LCS. Each table cell is a single
     * Int (LCS length), so memory is `(|a|+1)·(|b|+1)·4 bytes` — ~4 MB
     * for a thousand-line file on each side.
     */
    internal fun diff(
        before: List<String>,
        after: List<String>,
    ): List<DiffLine> {
        val n = before.size
        val m = after.size
        // dp[i][j] = LCS length of before[0..i) and after[0..j)
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 1..n) {
            for (j in 1..m) {
                dp[i][j] =
                    if (before[i - 1] == after[j - 1]) {
                        dp[i - 1][j - 1] + 1
                    } else {
                        maxOf(dp[i - 1][j], dp[i][j - 1])
                    }
            }
        }
        // Walk back from (n, m) to (0, 0) to produce the diff script.
        val out = ArrayDeque<DiffLine>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            if (before[i - 1] == after[j - 1]) {
                out.addFirst(DiffLine.Same(before[i - 1]))
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                out.addFirst(DiffLine.Delete(before[i - 1]))
                i--
            } else {
                out.addFirst(DiffLine.Insert(after[j - 1]))
                j--
            }
        }
        while (i > 0) {
            out.addFirst(DiffLine.Delete(before[i - 1]))
            i--
        }
        while (j > 0) {
            out.addFirst(DiffLine.Insert(after[j - 1]))
            j--
        }
        return out.toList()
    }

    /**
     * Total visual width of the diff frame, in columns. Both the header
     * (corner + rule + label + rule) and the footer (corner + rule) are
     * sized to this width so they line up at the same right column.
     * Bounded at 80 for readability; could be wired to `term.size().cols`
     * later if we ever want to fill the screen.
     */
    private const val FRAME_WIDTH: Int = 64
    private const val LEADING_RULE: Int = 2

    private suspend fun writeHeader(
        out: SuspendSink,
        path: String,
        isNewFile: Boolean,
        inserts: Int,
        deletes: Int,
        glyphs: AgentGlyphs,
        palette: Palette,
    ) {
        val badge = if (isNewFile) "new" else "edit"
        // Build the label twice: once with ANSI escapes for display, once
        // plain to measure the visual length. (Including ESC sequences in
        // the length count is how the borders ended up different sizes
        // before — color bytes consume code units but draw zero columns.)
        val statsColored =
            buildString {
                if (inserts > 0) append("${palette.green}+$inserts${palette.reset}")
                if (deletes > 0) {
                    if (isNotEmpty()) append("${palette.dim} ${palette.reset}")
                    append("${palette.red}-$deletes${palette.reset}")
                }
            }
        val statsPlain =
            buildString {
                if (inserts > 0) append("+$inserts")
                if (deletes > 0) {
                    if (isNotEmpty()) append(" ")
                    append("-$deletes")
                }
            }
        val labelColored =
            " $badge: $path${if (statsColored.isNotEmpty()) " · $statsColored" else ""} "
        val labelPlain =
            " $badge: $path${if (statsPlain.isNotEmpty()) " · $statsPlain" else ""} "

        val rule = glyphs.codeBoxHorizontal
        // Subtract one for the corner glyph, plus the leading rule cells
        // and the label's visible width, to land the trailing rule at the
        // same right edge as the footer.
        val trailingRule =
            (FRAME_WIDTH - 1 - LEADING_RULE - labelPlain.length).coerceAtLeast(2)
        out.writeUtf8(
            "${palette.dim}${glyphs.codeBoxTopLeft}${rule.repeat(LEADING_RULE)}${palette.reset}" +
                "${palette.dim}$labelColored${palette.reset}" +
                "${palette.dim}${rule.repeat(trailingRule)}${palette.reset}\n",
        )
    }

    private suspend fun writeFooter(
        out: SuspendSink,
        glyphs: AgentGlyphs,
        palette: Palette,
    ) {
        // Minus one for the corner glyph — same right edge as the header.
        val ruleLen = (FRAME_WIDTH - 1).coerceAtLeast(2)
        out.writeUtf8(
            "${palette.dim}${glyphs.codeBoxBottomLeft}${glyphs.codeBoxHorizontal.repeat(ruleLen)}" +
                "${palette.reset}\n",
        )
    }

    private suspend fun writeLine(
        out: SuspendSink,
        line: DiffLine,
        glyphs: AgentGlyphs,
        palette: Palette,
    ) {
        val gutter = "${palette.dim}${glyphs.codeBoxVertical}${palette.reset}"
        when (line) {
            is DiffLine.Same -> {
                out.writeUtf8("$gutter ${palette.dim}  ${line.text}${palette.reset}\n")
            }

            is DiffLine.Delete -> {
                out.writeUtf8("$gutter ${palette.red}- ${line.text}${palette.reset}\n")
            }

            is DiffLine.Insert -> {
                out.writeUtf8("$gutter ${palette.green}+ ${line.text}${palette.reset}\n")
            }
        }
    }

    /** ANSI bundle so callers don't pass eight separate strings. */
    internal data class Palette(
        val reset: String,
        val dim: String,
        val red: String,
        val green: String,
    )
}
