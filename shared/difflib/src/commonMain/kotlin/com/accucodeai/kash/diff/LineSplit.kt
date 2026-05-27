package com.accucodeai.kash.diff

/**
 * The line list of a text plus whether the text ended with a trailing
 * newline. We split on `\n`, dropping the empty trailing element produced
 * by a final LF, and re-attach the newline on output. [hasTrailingNewline]
 * drives the conventional `\ No newline at end of file` marker.
 */
public class LineSplit(
    public val lines: List<String>,
    public val hasTrailingNewline: Boolean,
)

/** Split [text] into lines, tracking the trailing-newline flag. */
public fun splitLines(text: String): LineSplit {
    if (text.isEmpty()) return LineSplit(emptyList(), true)
    val hasTrailing = text.endsWith('\n')
    val body = if (hasTrailing) text.substring(0, text.length - 1) else text
    return LineSplit(body.split('\n'), hasTrailing)
}
