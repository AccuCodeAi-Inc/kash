package com.accucodeai.kash.tools.zstd

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

public actual val zstdSupported: Boolean = false

public actual suspend fun zstdCompress(
    src: SuspendSource,
    sink: SuspendSink,
    level: Int,
): Unit = throw UnsupportedOperationException("zstd not available on this platform")

public actual suspend fun zstdDecompress(
    src: SuspendSource,
    sink: SuspendSink,
): Unit = throw UnsupportedOperationException("zstd not available on this platform")
