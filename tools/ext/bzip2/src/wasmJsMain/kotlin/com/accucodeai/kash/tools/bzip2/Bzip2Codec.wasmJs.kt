package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

private const val UNAVAILABLE = "bzip2 not available on this platform"

public actual suspend fun bzip2Compress(
    src: SuspendSource,
    dst: SuspendSink,
    blockSize100k: Int,
): Unit = throw Bzip2FormatException(UNAVAILABLE)

public actual suspend fun bzip2Decompress(
    src: SuspendSource,
    dst: SuspendSink,
): Unit = throw Bzip2FormatException(UNAVAILABLE)

public actual suspend fun bzip2Test(src: SuspendSource): Long = throw Bzip2FormatException(UNAVAILABLE)
