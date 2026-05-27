package com.accucodeai.kash.tools.lz4

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

private const val UNAVAILABLE = "lz4 not available on this platform"

public actual suspend fun lz4Compress(
    src: SuspendSource,
    dst: SuspendSink,
): Unit = throw Lz4FormatException(UNAVAILABLE)

public actual suspend fun lz4Decompress(
    src: SuspendSource,
    dst: SuspendSink,
): Unit = throw Lz4FormatException(UNAVAILABLE)

public actual suspend fun lz4Test(src: SuspendSource): Long = throw Lz4FormatException(UNAVAILABLE)
