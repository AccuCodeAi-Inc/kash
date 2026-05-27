package com.accucodeai.kash.tools.jq.regex

import com.accucodeai.kash.shared.regex.LinearRegex
import com.accucodeai.kash.tools.jq.JqRuntimeError

internal fun compileRegex(
    pattern: String,
    flags: String,
): LinearRegex =
    try {
        LinearRegex(pattern, flags)
    } catch (e: Throwable) {
        throw JqRuntimeError("regex: ${e.message ?: "invalid pattern"}")
    }
