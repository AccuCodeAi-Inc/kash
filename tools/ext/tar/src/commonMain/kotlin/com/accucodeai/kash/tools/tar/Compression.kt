package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Marker for compression sinks whose underlying codec needs an explicit
 * end-of-stream signal (e.g. gzip's CRC + ISIZE trailer). Callers should
 * call [finishCompression] after they've written every byte they intend to
 * write, *before* closing.
 */
public interface FinishableSink {
    public suspend fun finishCompression()
}

/**
 * Compression filter that wraps a [SuspendSource] (for decompression) or a
 * [SuspendSink] (for compression). Targets that can't supply a real codec
 * (wasmJs today) return null and the tar command surfaces a clean diagnostic.
 */
public expect object GzipFilter {
    public fun decompress(source: SuspendSource): SuspendSource?

    public fun compress(sink: SuspendSink): SuspendSink?
}

/** Same contract as [GzipFilter], for bzip2 (`-j`). JVM: commons-compress. */
public expect object Bzip2Filter {
    public fun decompress(source: SuspendSource): SuspendSource?

    public fun compress(sink: SuspendSink): SuspendSink?
}

/** Same contract as [GzipFilter], for xz (`-J`). JVM: tukaani-xz. */
public expect object XzFilter {
    public fun decompress(source: SuspendSource): SuspendSource?

    public fun compress(sink: SuspendSink): SuspendSink?
}
