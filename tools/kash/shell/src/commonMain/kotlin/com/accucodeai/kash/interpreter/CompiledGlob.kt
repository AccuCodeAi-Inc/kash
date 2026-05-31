package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.shared.regex.LinearRegex
import com.accucodeai.kash.shared.regex.RegexMatch
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Wrap a bash glob pattern as a single object exposing the operations the
 * interpreter needs: full match, anchored match at start/end, find-all,
 * replace-all.
 *
 * Internally a [LinearRegex] (RE2/J on JVM — linear-time, no catastrophic
 * backtracking) is used for the patterns [GlobToRegex] can translate.
 * Patterns containing extglob (`?(p)`, `*(p)`, `+(p)`, `@(p)`, `!(p)`) or
 * unsupported bracket constructs fall back to the recursive matcher in
 * [com.accucodeai.kash.api.util.matchGlob]. The fallback paths are the same
 * O(N²)-per-match cost the codebase had before; only the regex-translatable
 * patterns get the linear-time treatment, which is the bulk of real usage.
 */
internal class CompiledGlob private constructor(
    private val rxLongestBody: LinearRegex?,
    private val rxLongestStart: LinearRegex?,
    private val rxLongestEnd: LinearRegex?,
    private val rxLongestFull: LinearRegex?,
    private val rxShortestStart: LinearRegex?,
    private val rxShortestEnd: LinearRegex?,
    private val rawPattern: String,
    private val hasRegex: Boolean,
) {
    /** Pattern matches [s] in its entirety. */
    fun matchesFull(s: String): Boolean = if (hasRegex) rxLongestFull!!.containsMatch(s) else matchGlob(rawPattern, s)

    /**
     * Longest match starting at offset 0 of [s], or null. Returned as the
     * length of the matched prefix (0 is a valid result — empty match).
     */
    fun matchAtStart(
        s: String,
        longest: Boolean = true,
    ): Int? {
        if (hasRegex) {
            val rx = if (longest) rxLongestStart!! else rxShortestStart!!
            return rx.findFirst(s)?.length
        }
        return matchAtStartFallback(s, longest)
    }

    /**
     * Longest match ending at end-of-string of [s], or null. Returned as the
     * length of the matched suffix.
     */
    fun matchAtEnd(
        s: String,
        longest: Boolean = true,
    ): Int? {
        if (hasRegex) {
            if (longest) {
                // (?:pat)$ — RE2's findFirst returns the leftmost start that
                // anchors at $, which is the LONGEST suffix matching pat.
                return rxLongestEnd!!.findFirst(s)?.length
            }
            // Shortest suffix: rightmost-start. RE2 only walks left-to-right,
            // so scan suffix lengths from shortest to longest and check full
            // match. The regex compile is shared (rxLongestFull) and each
            // containsMatch is linear in suffix length — total O(N²·M) but
            // M is small in practice. Hot paths (`##` / `%%`) use longest.
            for (n in 0..s.length) {
                if (rxLongestFull!!.containsMatch(s.substring(s.length - n))) {
                    return n
                }
            }
            return null
        }
        return matchAtEndFallback(s, longest)
    }

    /** Leftmost match anywhere in [s], or null. */
    fun findFirst(s: String): RegexMatch? = if (hasRegex) rxLongestBody!!.findFirst(s) else findFirstFallback(s)

    /**
     * Replace all non-overlapping leftmost-longest matches of the pattern
     * in [s] using [rep] to compute each replacement from the matched text.
     */
    fun replaceAll(
        s: String,
        rep: (String) -> String,
    ): String {
        if (!hasRegex) return replaceAllFallback(s, rep)
        val matches = rxLongestBody!!.findAll(s)
        val sb = StringBuilder(s.length)
        var cursor = 0
        for (m in matches) {
            if (m.offset < cursor) continue // overlap (shouldn't happen for findAll)
            sb.append(s, cursor, m.offset)
            sb.append(rep(m.text))
            cursor = m.offset + m.length
            if (m.length == 0) {
                // Zero-width match — emit the next source char to advance.
                if (cursor < s.length) sb.append(s[cursor])
                cursor++
            }
        }
        if (cursor < s.length) sb.append(s, cursor, s.length)
        return sb.toString()
    }

    /** Replace the first leftmost-longest match (used by `${var/pat/repl}`). */
    fun replaceFirst(
        s: String,
        rep: (String) -> String,
    ): String {
        if (!hasRegex) return replaceFirstFallback(s, rep)
        val m = rxLongestBody!!.findFirst(s) ?: return s
        return buildString(s.length) {
            append(s, 0, m.offset)
            append(rep(m.text))
            append(s, m.offset + m.length, s.length)
        }
    }

    // --- Fallback paths (extglob / unsupported brackets) ------------------

    private fun matchAtStartFallback(
        s: String,
        longest: Boolean,
    ): Int? {
        // Mirror old patternSubst behavior: scan all prefix lengths in the
        // greedy direction and return the first hit.
        val range = if (longest) s.length downTo 0 else 0..s.length
        for (n in range) {
            if (matchGlob(rawPattern, s.substring(0, n))) return n
        }
        return null
    }

    private fun matchAtEndFallback(
        s: String,
        longest: Boolean,
    ): Int? {
        val range = if (longest) 0..s.length else s.length downTo 0
        for (start in range) {
            if (matchGlob(rawPattern, s.substring(start))) return s.length - start
        }
        return null
    }

    private fun findFirstFallback(s: String): RegexMatch? {
        for (i in 0..s.length) {
            for (end in s.length downTo i) {
                if (matchGlob(rawPattern, s.substring(i, end))) {
                    return RegexMatch(
                        offset = i,
                        length = end - i,
                        text = s.substring(i, end),
                        captures = emptyList(),
                    )
                }
            }
        }
        return null
    }

    private fun replaceAllFallback(
        s: String,
        rep: (String) -> String,
    ): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            var matchEnd = -1
            for (end in s.length downTo i) {
                if (matchGlob(rawPattern, s.substring(i, end))) {
                    matchEnd = end
                    break
                }
            }
            if (matchEnd < 0) {
                sb.append(s[i])
                i++
            } else {
                sb.append(rep(s.substring(i, matchEnd)))
                if (matchEnd == i) {
                    sb.append(s[i])
                    i++
                } else {
                    i = matchEnd
                }
            }
        }
        return sb.toString()
    }

    private fun replaceFirstFallback(
        s: String,
        rep: (String) -> String,
    ): String {
        for (i in 0..s.length) {
            for (end in s.length downTo i) {
                if (matchGlob(rawPattern, s.substring(i, end))) {
                    return buildString(s.length) {
                        append(s, 0, i)
                        append(rep(s.substring(i, end)))
                        append(s, end, s.length)
                    }
                }
            }
        }
        return s
    }

    companion object {
        /**
         * Compile [pattern] to a [CompiledGlob]. Always succeeds — extglob
         * and unsupported brackets transparently fall through to the
         * recursive matcher.
         */
        fun compile(
            pattern: String,
            caseInsensitive: Boolean = false,
        ): CompiledGlob {
            val srcLongest = GlobToRegex.translate(pattern, longest = true)
            val srcShortest = GlobToRegex.translate(pattern, longest = false)
            if (srcLongest == null || srcShortest == null) {
                return CompiledGlob(
                    rxLongestBody = null,
                    rxLongestStart = null,
                    rxLongestEnd = null,
                    rxLongestFull = null,
                    rxShortestStart = null,
                    rxShortestEnd = null,
                    rawPattern = pattern,
                    hasRegex = false,
                )
            }
            val flags = if (caseInsensitive) "si" else "s"
            return try {
                CompiledGlob(
                    rxLongestBody = LinearRegex(srcLongest, flags),
                    rxLongestStart = LinearRegex("^(?:$srcLongest)", flags),
                    rxLongestEnd = LinearRegex("(?:$srcLongest)$", flags),
                    rxLongestFull = LinearRegex("^(?:$srcLongest)$", flags),
                    rxShortestStart = LinearRegex("^(?:$srcShortest)", flags),
                    rxShortestEnd = LinearRegex("(?:$srcShortest)$", flags),
                    rawPattern = pattern,
                    hasRegex = true,
                )
            } catch (_: Throwable) {
                // Translator emitted source RE2 rejects (rare — collating
                // and equivalence classes already returned null, but
                // unforeseen inputs fall back rather than crashing).
                CompiledGlob(
                    rxLongestBody = null,
                    rxLongestStart = null,
                    rxLongestEnd = null,
                    rxLongestFull = null,
                    rxShortestStart = null,
                    rxShortestEnd = null,
                    rawPattern = pattern,
                    hasRegex = false,
                )
            }
        }
    }
}

/**
 * Machine-scoped cache of [CompiledGlob]s, keyed by pattern.
 *
 * Compiling a pattern builds up to SIX RE2 patterns (longest/shortest ×
 * body/start/end/full), each an NFA/DFA construction — the most expensive op
 * in this subsystem. The string parameter-expansion operators (prefix/suffix
 * strip `${var##pat}`, substitution `${var/pat/rep}`, case-mod `${var^^}`, …)
 * recompile the same pattern on every call, so a basename-strip inside a loop
 * recompiles one identical glob every iteration. Caching turns near-100% of
 * those compiles into a map lookup.
 *
 * (Pathname globbing i.e. `echo *.kt` — deliberately does NOT use this. A real
 * A/B showed RE2's per-match setup is ~2× slower than the trivial recursive
 * matcher for simple patterns over many short directory entries, so that path
 * keeps the hand-rolled matcher.)
 *
 * Lifetime is the shell session: the root [Interpreter] owns one, and every
 * subshell fork inherits it by reference via [Interpreter.SharedSession] (kash
 * forks readily, so a per-fork cache would be cold on every `$(...)`). No
 * process-global companion, so snapshots and isolated sessions never share
 * compiled state and the cache is freed with the session.
 *
 * Copy-on-write behind an [AtomicReference]: reads (the hot path) are a single
 * atomic load + map get; misses publish a new snapshot map. A lost update
 * under a write/write race (e.g. a background job expanding concurrently on
 * the same machine) just means one entry is recomputed later — never
 * incorrect. Two maps avoid allocating a composite cache key per lookup, and
 * the snapshot is dropped once it exceeds [CACHE_MAX] so a script emitting
 * unbounded distinct patterns can't grow it without limit.
 *
 * `public` only so it can be an opaque field on the public
 * [Interpreter.SharedSession] (which kash forks share by reference); its API is
 * `internal`, so nothing outside the shell module can read or mutate the cache
 * and [CompiledGlob] stays internal.
 */
@OptIn(ExperimentalAtomicApi::class)
public class GlobCache {
    private val cacheCs = AtomicReference<Map<String, CompiledGlob>>(emptyMap())
    private val cacheCi = AtomicReference<Map<String, CompiledGlob>>(emptyMap())

    internal fun compile(
        pattern: String,
        caseInsensitive: Boolean = false,
    ): CompiledGlob {
        val ref = if (caseInsensitive) cacheCi else cacheCs
        ref.load()[pattern]?.let { return it }
        val compiled = CompiledGlob.compile(pattern, caseInsensitive)
        val cur = ref.load()
        val base = if (cur.size >= CACHE_MAX) emptyMap() else cur
        ref.store(base + (pattern to compiled))
        return compiled
    }

    private companion object {
        const val CACHE_MAX = 1024
    }
}
