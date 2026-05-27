package com.accucodeai.kash.tools.gzip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Stream gzip-compress [source] -> [sink].
 *
 * @param level deflate level 1..9; 6 is the standard default.
 * @param storeName if non-null, write FNAME header field (otherwise omit).
 *                  Pass `null` for `-n` / when reading from stdin.
 * @param storeMtime mtime epoch seconds; pass 0L when `-n` or stdin (per RFC 1952 §2.3.1).
 */
public expect suspend fun gzipCompress(
    source: SuspendSource,
    sink: SuspendSink,
    level: Int,
    storeName: String?,
    storeMtime: Long,
)

/** Stream gzip-decompress [source] -> [sink]. Throws on malformed/short data. */
public expect suspend fun gzipDecompress(
    source: SuspendSource,
    sink: SuspendSink,
)

/**
 * Verify a gzip stream is well-formed by decompressing it and discarding
 * the output. Throws on any error.
 */
public expect suspend fun gzipTest(source: SuspendSource)
