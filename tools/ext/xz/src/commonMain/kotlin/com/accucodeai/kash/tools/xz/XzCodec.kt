package com.accucodeai.kash.tools.xz

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/** Compression / decompression format selector. */
public enum class XzFormat {
    /** `.xz` container — XZ/LZMA2. */
    XZ,

    /** Legacy `.lzma` — LZMA1 alone-format. */
    LZMA,
}

/**
 * Stream-compress bytes from [src] to [dst] using the chosen [format] and
 * compression [preset] (0–9). Both streams are bridged to the underlying
 * blocking codec on `Dispatchers.IO`.
 */
public expect suspend fun xzCompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
    preset: Int,
)

/**
 * Stream-decompress bytes from [src] to [dst]. The [format] selects between
 * the XZ container and the legacy `.lzma` alone-format. Throws
 * [IllegalArgumentException] (with a useful message) on a corrupt input.
 */
public expect suspend fun xzDecompress(
    src: SuspendSource,
    dst: SuspendSink,
    format: XzFormat,
)
