package com.accucodeai.kash.tools.less

/**
 * Render a Unicode codepoint as a 1- or 2-char [String]. Mirrors the helper
 * in the nano tool: JVM has `String(IntArray, ...)` for this; wasmJs/JS
 * does not, so we encode SMP codepoints by hand.
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
