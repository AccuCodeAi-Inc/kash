package com.accucodeai.kash.tools.vi

/** Render a Unicode codepoint as a 1- or 2-char [String]. See nano's identical helper. */
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
