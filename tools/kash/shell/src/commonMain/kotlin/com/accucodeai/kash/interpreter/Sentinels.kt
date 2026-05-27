package com.accucodeai.kash.interpreter

/**
 * In-string sentinels for source-vs-value provenance tracking.
 *
 * Bash uses raw bytes 0x01 (`CTLESC`) and 0x7F (`CTLNUL`) as internal
 * markers during expansion. Their job: distinguish
 * characters that came from the *source script* (subject to one final
 * dequote pass) from characters that flowed in through a *variable
 * value* (already dequoted at lookup time, no further stripping).
 *
 * Kotlin strings are UTF-16, so we use Private Use Area codepoints
 * instead of raw control bytes — a user-script that happens to write
 * a literal 0x01 still flows through normally instead of triggering
 * marker semantics by accident. The PUA picks make the sentinels
 * unrepresentable in legitimate text the script is meant to handle.
 *
 * Any external input that DOES contain a sentinel char gets sanitized
 * on the way in via [sanitizeUserInput] — bash does the equivalent
 * via `escape-marking pass` at every variable-value entry point
 *.
 */
internal object Sentinels {
    /**
     * Source-derived backslash marker. The character following CTLESC
     * is "protected": it came from the source script and survives the
     * quote-removal pass intact. Mirrors bash's CTLESC marker.
     */
    const val CTLESC: Char = ''

    /**
     * Quoted-null marker — distinguishes `''` (an empty quoted field
     * that survives field splitting) from a truly missing field.
     * Mirrors bash's CTLNUL marker. Reserved for future use;
     * not yet emitted by kash's expander.
     */
    const val CTLNUL: Char = ''
}

/**
 * Strip all CTLESC markers from [s]: each `CTLESC X` becomes `X`,
 * mirroring bash's `dequote pass`. Called at the
 * value→argv boundary so sentinels never reach user code.
 *
 * Idempotent: a string with no sentinels passes through unchanged
 * (the common case).
 */
internal fun dequoteSentinels(s: String): String {
    if (Sentinels.CTLESC !in s && Sentinels.CTLNUL !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when (c) {
            Sentinels.CTLESC -> {
                // Skip CTLESC, keep the following character (if any).
                if (i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
            }

            Sentinels.CTLNUL -> {
                // Drop the quoted-null marker entirely.
                i++
            }

            else -> {
                sb.append(c)
                i++
            }
        }
    }
    return sb.toString()
}

/**
 * Prefix CTLESC before every `\` in [s]. Used when a *source-script*
 * backslash flows into a patsub replacement string — the marker tells
 * `expandRepl` to preserve the backslash literally rather than treat
 * it as a value-derived dq-retention artefact to strip. Mirrors
 * bash's `escape-marking pass` on the source-text path.
 */
internal fun markSourceBackslashes(s: String): String {
    if ('\\' !in s) return s
    val sb = StringBuilder(s.length + 2)
    for (c in s) {
        if (c == '\\') sb.append(Sentinels.CTLESC)
        sb.append(c)
    }
    return sb.toString()
}
