package com.accucodeai.kash.tools.fzf

/**
 * Result of matching one candidate against a query.
 *
 * @property score higher is better.
 * @property positions indices (into the candidate string, in codepoint terms)
 *   of the characters that aligned with successive query characters. Used by
 *   the renderer to highlight matches.
 */
public data class FzfMatch(
    val score: Int,
    val positions: List<Int>,
)

/**
 * Fuzzy subsequence scoring. Pure — no I/O, no state.
 *
 * Algorithm (clean-room, not a port of junegunn/fzf):
 *
 *  - Subsequence test: the characters of `query` must appear in order in
 *    `candidate`. Otherwise no match.
 *  - Smart-case: if `caseSensitive` is null, infer — case-insensitive when
 *    the query is all-lowercase, sensitive otherwise.
 *  - Greedy left-to-right walk: for each query char, advance through
 *    `candidate` to the first matching position. Cheap, deterministic, good
 *    enough for v1. (Real fzf does DP for an optimal alignment; we don't.)
 *  - Score: +16 per matched char, +8 if the matched char is at a word
 *    boundary (preceded by /, _, -, ., space, or start-of-string), +4 if
 *    it's the first char of the candidate, +5 if it's a camelCase boundary
 *    (previous codepoint lowercase, this codepoint uppercase). -2 for each
 *    gap (non-matched chars) between consecutive matched positions. Tie-
 *    break: shorter candidate wins (handled by the caller in `rank`).
 *
 *  Empty query → score 0 match at no positions for every candidate.
 */
public object FzfMatcher {
    private val WORD_BOUNDARY_PREV = setOf('/', '_', '-', '.', ' ', '\t')

    /**
     * @param caseSensitive null means smart-case.
     */
    public fun match(
        query: String,
        candidate: String,
        caseSensitive: Boolean? = null,
    ): FzfMatch? {
        if (query.isEmpty()) return FzfMatch(score = 0, positions = emptyList())
        val cs = caseSensitive ?: query.any { it.isUpperCase() }

        val qcp = query.toCodePoints()
        val ccp = candidate.toCodePoints()
        if (qcp.isEmpty()) return FzfMatch(0, emptyList())
        if (ccp.size < qcp.size) return null

        val positions = ArrayList<Int>(qcp.size)
        var ci = 0
        for (q in qcp) {
            var hit = -1
            while (ci < ccp.size) {
                if (eq(ccp[ci], q, cs)) {
                    hit = ci
                    ci++
                    break
                }
                ci++
            }
            if (hit < 0) return null
            positions.add(hit)
        }

        var score = 0
        var prev = -2
        for ((i, p) in positions.withIndex()) {
            score += 16
            // Gap penalty: skipped chars between this match and the prior.
            if (i > 0) {
                val gap = p - prev - 1
                if (gap > 0) score -= 2 * gap
            }
            // Start-of-string bonus.
            if (p == 0) score += 4
            // Word-boundary bonus: preceded by a separator.
            if (p > 0 && ccp[p - 1].toChar() in WORD_BOUNDARY_PREV) {
                score += 8
            }
            // camelCase bonus: lower→upper transition.
            if (p > 0) {
                val prevCh = ccp[p - 1].toChar()
                val thisCh = ccp[p].toChar()
                if (prevCh.isLowerCase() && thisCh.isUpperCase()) score += 5
            }
            prev = p
        }
        return FzfMatch(score, positions)
    }

    /**
     * Match all `candidates` against `query` and return surviving entries
     * sorted by descending score, breaking ties by shorter candidate, then
     * by original index (stable).
     */
    public fun rank(
        query: String,
        candidates: List<String>,
        caseSensitive: Boolean? = null,
    ): List<Ranked> {
        if (query.isEmpty()) {
            return candidates.mapIndexed { i, c -> Ranked(i, c, 0, emptyList()) }
        }
        val out = ArrayList<Ranked>(candidates.size)
        for ((i, c) in candidates.withIndex()) {
            val m = match(query, c, caseSensitive) ?: continue
            out.add(Ranked(i, c, m.score, m.positions))
        }
        out.sortWith(
            compareByDescending<Ranked> { it.score }
                .thenBy { it.text.length }
                .thenBy { it.index },
        )
        return out
    }

    public data class Ranked(
        val index: Int,
        val text: String,
        val score: Int,
        val positions: List<Int>,
    )

    private fun eq(
        a: Int,
        b: Int,
        caseSensitive: Boolean,
    ): Boolean =
        if (caseSensitive) {
            a == b
        } else {
            a.toChar().lowercaseChar() == b.toChar().lowercaseChar()
        }
}

internal fun String.toCodePoints(): IntArray {
    val out = IntArray(this.length)
    var n = 0
    var i = 0
    while (i < this.length) {
        val c1 = this[i]
        if (c1.isHighSurrogate() && i + 1 < this.length && this[i + 1].isLowSurrogate()) {
            val c2 = this[i + 1]
            out[n++] = 0x10000 + (c1.code - 0xD800) * 0x400 + (c2.code - 0xDC00)
            i += 2
        } else {
            out[n++] = c1.code
            i++
        }
    }
    return if (n == out.size) out else out.copyOf(n)
}
