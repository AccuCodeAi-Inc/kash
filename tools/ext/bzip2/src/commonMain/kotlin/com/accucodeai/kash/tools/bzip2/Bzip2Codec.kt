package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Stream the bzip2-compression of [src] into [dst]. Reads run to EOF on the
 * source, finalizes the bzip2 stream, and flushes the sink. [blockSize100k]
 * is the bzip2 block size in units of 100KiB — valid range 1..9 (compressor
 * default = 9 in CLI bzip2). Throws on bad block size.
 */
public expect suspend fun bzip2Compress(
    src: SuspendSource,
    dst: SuspendSink,
    blockSize100k: Int,
)

/**
 * Stream the bzip2-decompression of [src] into [dst]. Handles concatenated
 * bzip2 streams (as `bunzip2` does). Throws [Bzip2FormatException] on invalid
 * input.
 */
public expect suspend fun bzip2Decompress(
    src: SuspendSource,
    dst: SuspendSink,
)

/**
 * `bzip2 -t`: validate input by decoding it and discarding output. Throws
 * [Bzip2FormatException] on corruption. Returns total decompressed byte count.
 */
public expect suspend fun bzip2Test(src: SuspendSource): Long

/** Bzip2 format / corruption error. */
public class Bzip2FormatException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
