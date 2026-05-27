package com.accucodeai.kash.tools.zstd

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Streaming zstd codec. Reads from [src] and writes the
 * (de)compressed bytes to [sink] in chunks. Implemented per-platform:
 *
 *  - JVM: bridges to `io.airlift.compress.zstd.{ZstdInputStream,
 *    ZstdOutputStream}` via blocking InputStream/OutputStream adapters
 *    inside [kotlinx.coroutines.Dispatchers.IO]. The lib doesn't expose
 *    compression level on the streaming API; `level` is accepted but
 *    ignored (matches the reference C zstd default of 3 in spirit).
 *  - wasmJs: throws — no pure-Kotlin zstd implementation available.
 */
public expect suspend fun zstdCompress(
    src: SuspendSource,
    sink: SuspendSink,
    level: Int,
)

public expect suspend fun zstdDecompress(
    src: SuspendSource,
    sink: SuspendSink,
)

/** True when this build has a working zstd backend (JVM yes, wasmJs no). */
public expect val zstdSupported: Boolean
