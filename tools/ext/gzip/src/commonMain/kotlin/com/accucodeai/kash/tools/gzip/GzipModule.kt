package com.accucodeai.kash.tools.gzip

import com.accucodeai.kash.api.CommandSpec

/**
 * `gzip` / `gunzip` / `zcat` — DEFLATE-based stream compression.
 *
 * - `gzip` compresses; with `-d` decompresses.
 * - `gunzip` is `gzip -d`.
 * - `zcat` is `gunzip -c` (decompress to stdout, keep originals).
 *
 * Streaming on JVM via `java.util.zip.Deflater` + `GZIPInputStream`. On
 * wasmJs the codec stubs out — these commands surface a clear "not
 * available" error there.
 */
public val gzipCommands: List<CommandSpec> =
    listOf(
        GzipImpl(name = "gzip", defaultMode = Mode.COMPRESS),
        GzipImpl(name = "gunzip", defaultMode = Mode.DECOMPRESS),
        GzipImpl(name = "zcat", defaultMode = Mode.DECOMPRESS),
    )
