package com.accucodeai.kash.tools.uname

/**
 * Platform-derived `uname` fields. Unknown values are the literal string
 * `"unknown"` (matching GNU coreutils, which prints `unknown` and drops the
 * field from `-a` output where appropriate).
 */
internal data class UnameInfo(
    val sysname: String,
    val nodename: String,
    val release: String,
    val version: String,
    val machine: String,
    val processor: String,
    val hardwarePlatform: String,
    val operatingSystem: String,
)

internal expect fun unameInfo(): UnameInfo
