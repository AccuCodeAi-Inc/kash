package com.accucodeai.kash.tools.fzf

/**
 * Immutable UI state. Every key press produces a new state. Matchers and
 * scrolling are derived; we recompute filtered/positions whenever the query
 * changes via [withQuery].
 *
 * @property candidates the full input pool (stdin lines, or fs walk).
 * @property query current search string.
 * @property filtered ranked subset matching `query` (or all candidates if
 *   the query is empty).
 * @property cursor index into `filtered` of the highlighted row.
 * @property selected set of indices into `candidates` (NOT `filtered`) that
 *   the user has toggled in multi-select mode.
 * @property topOfList scroll offset: index into `filtered` of the first
 *   visible row.
 * @property caseSensitive null = smart-case.
 */
public data class FzfState(
    val candidates: List<String>,
    val query: String,
    val filtered: List<FzfMatcher.Ranked>,
    val cursor: Int,
    val selected: Set<Int>,
    val topOfList: Int,
    val caseSensitive: Boolean?,
) {
    public val totalCount: Int get() = candidates.size
    public val filteredCount: Int get() = filtered.size
    public val selectedCount: Int get() = selected.size

    /** Currently highlighted candidate's index into `candidates`, or null. */
    public val cursorCandidateIndex: Int?
        get() = filtered.getOrNull(cursor)?.index

    public fun withQuery(newQuery: String): FzfState {
        val ranked = FzfMatcher.rank(newQuery, candidates, caseSensitive)
        return copy(
            query = newQuery,
            filtered = ranked,
            cursor = 0,
            topOfList = 0,
        )
    }

    public fun moveCursor(delta: Int): FzfState {
        if (filtered.isEmpty()) return copy(cursor = 0, topOfList = 0)
        val nc = (cursor + delta).coerceIn(0, filtered.size - 1)
        return copy(cursor = nc)
    }

    public fun toggleSelectionAtCursor(): FzfState {
        val idx = cursorCandidateIndex ?: return this
        val next = if (idx in selected) selected - idx else selected + idx
        return copy(selected = next)
    }

    public fun deleteLastCodepoint(): FzfState {
        if (query.isEmpty()) return this
        val cps = query.toCodePoints()
        val truncated =
            buildString {
                for (i in 0 until cps.size - 1) appendCodePoint(cps[i])
            }
        return withQuery(truncated)
    }

    public fun deleteLastWord(): FzfState {
        if (query.isEmpty()) return this
        // Unix word-erase: trim trailing whitespace, then trim the word, then
        // also trim the whitespace that preceded the word.
        var end = query.length
        while (end > 0 && query[end - 1].isWhitespace()) end--
        while (end > 0 && !query[end - 1].isWhitespace()) end--
        while (end > 0 && query[end - 1].isWhitespace()) end--
        return withQuery(query.substring(0, end))
    }

    public fun clearQuery(): FzfState = withQuery("")

    public fun appendCodepoint(cp: Int): FzfState {
        val sb = StringBuilder(query)
        sb.appendCodePoint(cp)
        return withQuery(sb.toString())
    }

    /**
     * Compute the accept set on Enter:
     *  - multi-select with at least one toggled item → those items (in
     *    input order).
     *  - otherwise the single highlighted item (if any).
     */
    public fun accept(multi: Boolean): List<String> {
        if (multi && selected.isNotEmpty()) {
            return selected.sorted().map { candidates[it] }
        }
        val idx = cursorCandidateIndex ?: return emptyList()
        return listOf(candidates[idx])
    }

    public companion object {
        public fun initial(
            candidates: List<String>,
            initialQuery: String,
            caseSensitive: Boolean?,
        ): FzfState {
            val ranked = FzfMatcher.rank(initialQuery, candidates, caseSensitive)
            return FzfState(
                candidates = candidates,
                query = initialQuery,
                filtered = ranked,
                cursor = 0,
                selected = emptySet(),
                topOfList = 0,
                caseSensitive = caseSensitive,
            )
        }
    }
}

/** Multiplatform `appendCodePoint`. */
internal fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp < 0x10000) {
        append(cp.toChar())
    } else {
        val high = ((cp - 0x10000) shr 10) + 0xD800
        val low = ((cp - 0x10000) and 0x3FF) + 0xDC00
        append(high.toChar())
        append(low.toChar())
    }
    return this
}
