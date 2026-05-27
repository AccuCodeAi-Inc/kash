package com.accucodeai.kash.tools.diff

/**
 * Whitespace/case folding applied *for comparison only* — the original
 * line text is what diff emits. We model this by mapping each line to a
 * comparison key, threading the keys into LCS, and pairing keys back to
 * originals.
 */
internal class IgnoreOptions(
    val ignoreCase: Boolean = false,
    val ignoreAllWhitespace: Boolean = false, // -w
    val ignoreWhitespaceChange: Boolean = false, // -b
    val ignoreBlankLines: Boolean = false, // -B
) {
    val active: Boolean
        get() = ignoreCase || ignoreAllWhitespace || ignoreWhitespaceChange || ignoreBlankLines

    /** Comparison key for [line]. */
    fun key(line: String): String {
        var s = line
        if (ignoreAllWhitespace) {
            s = s.filter { !it.isWhitespace() }
        } else if (ignoreWhitespaceChange) {
            // Collapse runs of blanks to a single space, trim trailing blanks.
            val sb = StringBuilder()
            var prevSpace = false
            for (c in s) {
                if (c == ' ' || c == '\t') {
                    if (!prevSpace) sb.append(' ')
                    prevSpace = true
                } else {
                    sb.append(c)
                    prevSpace = false
                }
            }
            s = sb.toString().trimEnd()
        }
        if (ignoreCase) s = s.lowercase()
        return s
    }
}

/**
 * Returns true when [oldText] and [newText] are equal under [opts]
 * (used for `-q`/`-s` and exit-code decisions). With `-B`, blank-only
 * lines are dropped before comparison.
 */
internal fun textsEqual(
    oldText: String,
    newText: String,
    opts: IgnoreOptions,
): Boolean {
    if (!opts.active) return oldText == newText
    val a = comparisonKeys(oldText, opts)
    val b = comparisonKeys(newText, opts)
    return a == b
}

internal fun comparisonKeys(
    text: String,
    opts: IgnoreOptions,
): List<String> {
    val split =
        com.accucodeai.kash.diff
            .splitLines(text)
    val keys = split.lines.map { opts.key(it) }
    return if (opts.ignoreBlankLines) keys.filter { it.isNotEmpty() } else keys
}
