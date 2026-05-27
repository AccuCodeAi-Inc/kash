package com.accucodeai.kash.tools.xz

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

public actual suspend fun xzCompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
    preset: Int,
): Unit = throw UnsupportedOperationException("xz not available on this platform")

public actual suspend fun xzDecompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
): Unit = throw UnsupportedOperationException("xz not available on this platform")
