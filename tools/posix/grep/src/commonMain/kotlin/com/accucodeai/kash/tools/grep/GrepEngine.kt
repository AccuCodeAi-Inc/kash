package com.accucodeai.kash.tools.grep

import com.accucodeai.kash.api.ansi.ColorMode
import com.accucodeai.kash.shared.regex.LinearRegex
import com.accucodeai.kash.shared.regex.breToEre
import com.accucodeai.kash.shared.regex.escapeLiteral

/** Which regex dialect the patterns use. */
internal enum class GrepMode { BRE, ERE, FIXED }

/**
 * `--binary-files=…` mode. Default [BINARY] mirrors GNU grep: when a file
 * looks binary (contains a NUL byte) and at least one line matches, print
 * `Binary file LABEL matches` instead of the matching lines.
 */
internal enum class BinaryMode { BINARY, TEXT, WITHOUT_MATCH }

/** Parsed CLI options for a single grep invocation. */
internal data class GrepOptions(
    val mode: GrepMode,
    val patterns: List<String>,
    val files: List<String>,
    val ignoreCase: Boolean = false,
    val invert: Boolean = false,
    val lineNumbers: Boolean = false,
    val filesWithMatches: Boolean = false,
    /** -L. Print only filenames with NO match. Mutually exclusive with -l in practice. */
    val filesWithoutMatch: Boolean = false,
    val countOnly: Boolean = false,
    val quiet: Boolean = false,
    /** -h. */
    val suppressFilename: Boolean = false,
    /** -H. Force filename prefix even with a single file / stdin. */
    val forceFilename: Boolean = false,
    val recursive: Boolean = false,
    /** -x. Match only if the pattern covers the entire line. */
    val wholeLine: Boolean = false,
    /** -w. Require word-boundaries around the match. */
    val wordRegexp: Boolean = false,
    /** -o. Print only the matching substrings, one per line. */
    val onlyMatching: Boolean = false,
    /** -m N. Stop reading a source after N matching lines (after-context still drains). */
    val maxCount: Int? = null,
    /** -A N / --after-context. Print N lines of context after each match. */
    val afterContext: Int = 0,
    /** -B N / --before-context. Print N lines of context before each match. */
    val beforeContext: Int = 0,
    /** --include=GLOB. Applied to basenames during -r walks (any-match-included). */
    val include: List<String> = emptyList(),
    /** --exclude=GLOB. Applied to basenames during -r walks. */
    val exclude: List<String> = emptyList(),
    /** --include-dir=GLOB. Applied to directory basenames during -r descent. */
    val includeDir: List<String> = emptyList(),
    /** --exclude-dir=GLOB. Applied to directory basenames during -r descent. */
    val excludeDir: List<String> = emptyList(),
    /** --binary-files / -a / -I. Default BINARY mirrors GNU grep. */
    val binaryMode: BinaryMode = BinaryMode.BINARY,
    /**
     * --color[=WHEN]. Default NEVER — scripts stay byte-identical. The
     * interactive REPL aliases `grep` to `grep --color=auto` so users still
     * get colors at the prompt.
     */
    val color: ColorMode = ColorMode.NEVER,
    /**
     * -z / --null-data. Treat input AND output records as NUL-terminated
     * instead of newline-terminated — so a "line" may contain newlines and the
     * record separator is `\0`. Pairs with `find -print0`. The NUL-based
     * binary-file heuristic is disabled under -z (NUL is the delimiter here,
     * not a binary signal).
     */
    val nullData: Boolean = false,
)

/**
 * Compile [opts] patterns into a single linear-time regex. For FIXED mode each
 * pattern is treated as a literal string. For BRE mode each pattern is
 * translated to ERE first. Multiple patterns are alternated with `|`.
 *
 * Empty pattern list is an error; the caller validates and reports it.
 */
internal fun compileRegex(opts: GrepOptions): LinearRegex {
    val translated =
        opts.patterns.map { p ->
            when (opts.mode) {
                GrepMode.FIXED -> escapeLiteral(p)
                GrepMode.BRE -> breToEre(p)
                GrepMode.ERE -> p
            }
        }
    // Wrap each in a non-capturing group so `|`-alternation doesn't bind
    // unexpectedly across patterns. RE2 supports `(?:...)`.
    val alternation =
        if (translated.size == 1) {
            translated[0]
        } else {
            translated.joinToString("|") { "(?:$it)" }
        }
    // -w wraps in word boundaries; -x anchors to whole line. -x takes
    // precedence — `\b...\b` inside `^...$` is redundant but harmless, so we
    // just stack them.
    val withWord = if (opts.wordRegexp) "\\b(?:$alternation)\\b" else alternation
    val joined = if (opts.wholeLine) "^(?:$withWord)$" else withWord
    val flags = if (opts.ignoreCase) "i" else ""
    return LinearRegex(joined, flags)
}

/** Result of grepping one source. */
internal data class GrepSourceResult(
    val matched: Boolean,
    val count: Int,
)
