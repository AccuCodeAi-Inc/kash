package com.accucodeai.kash.tools.lz4

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Stream the lz4-compression of [src] into [dst] using the `.lz4` frame
 * format (RFC-style frame, what the `lz4` CLI emits). Reads to EOF, finalizes
 * the frame, flushes the sink.
 */
public expect suspend fun lz4Compress(
    src: SuspendSource,
    dst: SuspendSink,
)

/**
 * Stream the lz4-decompression of [src] into [dst]. Handles concatenated
 * frames. Throws [Lz4FormatException] on invalid input.
 */
public expect suspend fun lz4Decompress(
    src: SuspendSource,
    dst: SuspendSink,
)

/** `lz4 -t`: decode and discard. Returns total decompressed byte count. */
public expect suspend fun lz4Test(src: SuspendSource): Long

/** LZ4 format / corruption error. */
public class Lz4FormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
