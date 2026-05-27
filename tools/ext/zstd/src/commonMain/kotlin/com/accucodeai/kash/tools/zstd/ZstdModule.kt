package com.accucodeai.kash.tools.zstd

import com.accucodeai.kash.api.CommandSpec

/**
 * Zstandard tools — `zstd` (compress), `unzstd` (= `zstd -d`),
 * `zstdcat` (= `zstd -dc`). Backed by `io.airlift:aircompressor` on JVM;
 * wasmJs returns a "not available" error at run time.
 */
public val zstdCommands: List<CommandSpec> =
    listOf(
        ZstdCommand(name = "zstd", defaultDecompress = false, defaultToStdout = false),
        ZstdCommand(name = "unzstd", defaultDecompress = true, defaultToStdout = false),
        ZstdCommand(name = "zstdcat", defaultDecompress = true, defaultToStdout = true),
    )
