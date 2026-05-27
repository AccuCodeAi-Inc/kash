package com.accucodeai.kash.tools.nano

/**
 * Render a Unicode codepoint as a 1- or 2-char [String]. JVM has the
 * `String(IntArray, ...)` constructor for this; wasmJs/JS does not. We
 * encode SMP codepoints as a UTF-16 surrogate pair manually — the rest of
 * the editor already operates in UTF-16 char units.
 */
internal fun codepointToString(cp: Int): String =
    if (cp <= 0xFFFF) {
        cp.toChar().toString()
    } else {
        val u = cp - 0x10000
        charArrayOf(
            ((u shr 10) + 0xD800).toChar(),
            ((u and 0x3FF) + 0xDC00).toChar(),
        ).concatToString()
    }
