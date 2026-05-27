package com.accucodeai.kash.tools.less

/**
 * The pager's in-memory view of the input. v1 loads the entire input —
 * either argv FILE or piped stdin — into [lines] before entering raw mode.
 * That is intentional: real-world `less` falls back to read-everything-first
 * whenever the input is not seekable, and a pipe never is. We do not try to
 * be cleverer than that in v1.
 *
 * State is immutable copy-on-write. Each navigation operation returns a new
 * buffer; the pager's main loop just rebinds `buf` to the result.
 */
internal data class LessBuffer(
    val lines: List<String>,
    val top: Int,
    val bodyHeight: Int,
    val filename: String?,
    val totalBytes: Long,
    val lastSearch: LastSearch? = null,
    val highlightPattern: Regex? = null,
    val showLineNumbers: Boolean = false,
) {
    val rowCount: Int get() = lines.size

    /**
     * Clamp [top] to a legal position so the last screenful is fully
     * visible and we never scroll past the end. Called after any movement.
     */
    fun normalized(): LessBuffer {
        val maxTop = (rowCount - bodyHeight).coerceAtLeast(0)
        val t = top.coerceIn(0, maxTop)
        return if (t == top) this else copy(top = t)
    }

    fun withBodyHeight(h: Int): LessBuffer = copy(bodyHeight = h.coerceAtLeast(1)).normalized()

    // ---------- Scrolling ----------

    fun scrollLineForward(n: Int = 1): LessBuffer = copy(top = top + n).normalized()

    fun scrollLineBackward(n: Int = 1): LessBuffer = copy(top = top - n).normalized()

    fun scrollScreenForward(): LessBuffer = scrollLineForward(bodyHeight)

    fun scrollScreenBackward(): LessBuffer = scrollLineBackward(bodyHeight)

    fun scrollHalfForward(): LessBuffer = scrollLineForward((bodyHeight / 2).coerceAtLeast(1))

    fun scrollHalfBackward(): LessBuffer = scrollLineBackward((bodyHeight / 2).coerceAtLeast(1))

    fun gotoStart(): LessBuffer = copy(top = 0)

    fun gotoEnd(): LessBuffer = copy(top = (rowCount - bodyHeight).coerceAtLeast(0))

    /** Jump to a 1-indexed line number (clamped). */
    fun gotoLine(lineNum: Int): LessBuffer {
        val idx = (lineNum - 1).coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        return copy(top = idx).normalized()
    }

    /** Jump to roughly [percent]% through the file. 0..100. */
    fun gotoPercent(percent: Int): LessBuffer {
        val p = percent.coerceIn(0, 100)
        val idx = ((rowCount.toLong() * p) / 100L).toInt().coerceIn(0, (rowCount - 1).coerceAtLeast(0))
        return copy(top = idx).normalized()
    }

    // ---------- Search ----------

    /**
     * Find the next match of [pattern] starting from the line *after* [top].
     * Returns the row index, or null on miss. Does not wrap — POSIX-like
     * less does not auto-wrap forward searches.
     */
    fun searchForward(
        pattern: Regex,
        startRow: Int = top + 1,
    ): Int? {
        val from = startRow.coerceAtLeast(0)
        for (r in from until rowCount) {
            if (pattern.containsMatchIn(lines[r])) return r
        }
        return null
    }

    /** Backward equivalent of [searchForward]. */
    fun searchBackward(
        pattern: Regex,
        startRow: Int = top - 1,
    ): Int? {
        val from = startRow.coerceAtMost(rowCount - 1)
        for (r in from downTo 0) {
            if (pattern.containsMatchIn(lines[r])) return r
        }
        return null
    }

    /**
     * Position the viewport so that [row] is visible (centered when
     * convenient). Also clears any prior search highlight unless callers
     * preserve it explicitly.
     */
    fun jumpToRow(row: Int): LessBuffer {
        val centered = (row - bodyHeight / 2).coerceAtLeast(0)
        return copy(top = centered).normalized()
    }

    companion object {
        fun fromText(
            text: String,
            filename: String?,
            bodyHeight: Int = 20,
            showLineNumbers: Boolean = false,
        ): LessBuffer {
            val raw = if (text.isEmpty()) listOf("") else text.split('\n')
            // Trailing newline → drop the trailing empty segment that split
            // produces, so a 3-line file with trailing \n shows 3 lines, not 4.
            val lines = if (raw.size > 1 && raw.last().isEmpty()) raw.dropLast(1) else raw
            return LessBuffer(
                lines = lines.ifEmpty { listOf("") },
                top = 0,
                bodyHeight = bodyHeight.coerceAtLeast(1),
                filename = filename,
                totalBytes = text.encodeToByteArray().size.toLong(),
                showLineNumbers = showLineNumbers,
            )
        }
    }
}

/** Records the most recent search pattern + direction so `n`/`N` know what to do. */
internal data class LastSearch(
    val pattern: Regex,
    val raw: String,
    val forward: Boolean,
)
