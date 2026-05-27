package com.accucodeai.kash.api.io

import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Suspending counterpart to [kotlinx.io.RawSink]. Mirrors the shape so a
 * mechanical port is "add `suspend` markers + flip the receiver type."
 *
 * In-process pipes ([AsyncPipe]) implement this directly against a coroutine
 * channel — `write` parks the *coroutine*, freeing the dispatcher thread.
 * OS-backed sinks adapt their blocking [RawSink] via [asSuspend], which hops
 * to [Dispatchers.IO] for the unavoidable kernel call.
 */
public interface SuspendSink {
    public suspend fun write(
        source: Buffer,
        byteCount: Long,
    )

    public suspend fun flush()

    /**
     * Close the sink. Non-suspending so signal handlers and OFD refcount
     * release paths (both inherently non-suspend) can close pipes cleanly
     * without runBlocking. Channel-backed pipes just call `channel.close()`
     * (synchronous). OS-backed sinks delegate to the underlying blocking
     * `close()`; that's a fast syscall — we don't bounce it onto an IO
     * worker the way `write`/`flush` do.
     */
    public fun close()
}

/** Suspending counterpart to [kotlinx.io.RawSource]. See [SuspendSink]. */
public interface SuspendSource {
    /** -1 at EOF, otherwise number of bytes written into [sink]. */
    public suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long

    /** See [SuspendSink.close]. */
    public fun close()
}

// ---------------------------------------------------------------------------
// OS-boundary adapters. The ONLY place `withContext(Dispatchers.IO)` lives.
// ---------------------------------------------------------------------------

/** Lift a blocking [RawSink] (e.g. [kotlinx.io.files.SystemFileSystem.sink],
 *  REPL stdout) into a [SuspendSink]. Every call hops to [Dispatchers.IO]. */
public fun RawSink.asSuspend(): SuspendSink = OsSuspendSink(this)

/** Lift a blocking [RawSource] into a [SuspendSource]. */
public fun RawSource.asSuspend(): SuspendSource = OsSuspendSource(this)

private class OsSuspendSink(
    private val raw: RawSink,
) : SuspendSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        withContext(ioDispatcher) { raw.write(source, byteCount) }
    }

    override suspend fun flush() {
        withContext(ioDispatcher) { raw.flush() }
    }

    override fun close() {
        raw.close()
    }
}

private class OsSuspendSource(
    private val raw: RawSource,
) : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = withContext(ioDispatcher) { raw.readAtMostTo(sink, byteCount) }

    override fun close() {
        raw.close()
    }
}

// ---------------------------------------------------------------------------
// In-memory adapters: a Buffer is purely synchronous and never suspends.
// ---------------------------------------------------------------------------

/** Treat a [Buffer] as a [SuspendSink] — writes append to the buffer. */
public fun Buffer.asSuspendSink(): SuspendSink = BufferSuspendSink(this)

/** Treat a [Buffer] as a [SuspendSource] — reads drain from the buffer. */
public fun Buffer.asSuspendSource(): SuspendSource = BufferSuspendSource(this)

private class BufferSuspendSink(
    private val buf: Buffer,
) : SuspendSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        buf.write(source, byteCount)
    }

    override suspend fun flush() { /* in-memory */ }

    override fun close() { /* in-memory */ }
}

private class BufferSuspendSource(
    private val buf: Buffer,
) : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = buf.readAtMostTo(sink, byteCount)

    override fun close() { /* in-memory */ }
}

/** /dev/null-style discard sink. */
public object NullSuspendSink : SuspendSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        source.skip(byteCount)
    }

    override suspend fun flush() {}

    override fun close() {}
}

/** Empty source: immediately EOF. */
public object EmptySuspendSource : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = -1L

    override fun close() {}
}

// ---------------------------------------------------------------------------
// Helpers (suspending counterparts to the RawSink/RawSource extensions).
// ---------------------------------------------------------------------------

/**
 * Write UTF-8 text and flush. Per-call flush keeps writes streaming through
 * pipelines — a downstream `head -n 1` sees output as it arrives.
 */
public suspend fun SuspendSink.writeUtf8(s: String) {
    if (s.isEmpty()) return
    val buf = Buffer()
    buf.writeString(s)
    write(buf, buf.size)
    flush()
}

public suspend fun SuspendSink.writeLine(s: String) {
    writeUtf8(s)
    writeUtf8("\n")
}

public suspend fun SuspendSink.writeBytes(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val buf = Buffer()
    buf.write(bytes)
    write(buf, buf.size)
    flush()
}

/** Drain [source] into this sink. Returns total bytes copied. */
public suspend fun SuspendSink.transferFrom(source: SuspendSource): Long {
    val buf = Buffer()
    var total = 0L
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        write(buf, buf.size)
        flush()
        total += n
        yield()
    }
    return total
}

public suspend fun SuspendSource.readUtf8Text(): String {
    val buf = Buffer()
    while (true) {
        val n = readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        yield()
    }
    return buf.readString()
}

public suspend fun SuspendSource.readAllBytes(): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        yield()
    }
    return buf.readByteArray()
}

public suspend fun SuspendSource.readUtf8LineOrNull(): String? = readUtf8DelimitedOrNull('\n'.code.toByte())

/** AutoCloseable-style scoped use for a [SuspendSource]. */
public suspend inline fun <S : SuspendSource, R> S.use(block: (S) -> R): R {
    try {
        return block(this)
    } finally {
        try {
            close()
        } catch (_: Throwable) {
        }
    }
}

/** AutoCloseable-style scoped use for a [SuspendSink]. */
public suspend inline fun <S : SuspendSink, R> S.useSink(block: (S) -> R): R {
    try {
        return block(this)
    } finally {
        try {
            close()
        } catch (_: Throwable) {
        }
    }
}

public suspend fun SuspendSource.readUtf8DelimitedOrNull(delim: Byte): String? {
    val chunk = Buffer()
    val accum = Buffer()
    var any = false
    var since = 0
    while (true) {
        val n = readAtMostTo(chunk, 1L)
        if (n == -1L) break
        any = true
        val b = chunk.readByte()
        if (b == delim) return accum.readString()
        // Accumulate raw UTF-8 bytes; decode once at end via Buffer.readString.
        // The prior `out.append(b.toInt().toChar())` sign-extended any byte ≥ 0x80
        // and produced garbage code points (e.g. U+FFC3 for byte 0xC3).
        accum.writeByte(b)
        // Yield periodically so wasm's single JS thread isn't starved on
        // pathologically long lines from synchronous sources (e.g.
        // `tr -d '\n' < /dev/urandom`). 1 KiB is a cheap microtask cadence.
        if (++since == 1024) {
            since = 0
            yield()
        }
    }
    return if (any) accum.readString() else null
}

// ---------------------------------------------------------------------------
// Buffered lookahead — batches reads from the upstream into an internal
// segment chain, so callers that need to scan for a delimiter (e.g. line
// readers, `mapfile`, `read`, `for line in`) don't pay one upstream read per
// byte. Tools that pull lines in tight loops should wrap their source once
// via [buffered] and reuse the wrapper across calls — wrapping per-call
// silently drops the bytes already read past the previous delimiter.
// ---------------------------------------------------------------------------

/**
 * A [SuspendSource] that buffers reads from an upstream source so callers
 * can look ahead for a delimiter without a per-byte upstream hop. Reads of
 * unbuffered bytes pull a full [chunkSize] from upstream at a time; the
 * internal buffer is drained-first on any [readAtMostTo] call, so the
 * wrapper is a drop-in substitute for the upstream source.
 *
 * **Identity discipline**: wrap a given upstream source at most once and
 * reuse the wrapper for the source's full lifetime — wrapping the same
 * source twice produces two wrappers with disjoint internal buffers and
 * the second one's reads will skip the bytes already pulled by the first.
 */
public class BufferedSuspendSource(
    private val upstream: SuspendSource,
    private val chunkSize: Int = 8 * 1024,
) : SuspendSource {
    private val buf: Buffer = Buffer()
    private var eof: Boolean = false

    /** Bytes currently buffered and immediately available without an upstream call. */
    public val available: Long
        get() = buf.size

    /**
     * Pull at least one more byte from upstream into the buffer.
     * Returns `false` if upstream is at EOF (buffer may still hold residual bytes).
     */
    private suspend fun refill(): Boolean {
        if (eof) return false
        val n = upstream.readAtMostTo(buf, chunkSize.toLong())
        if (n == -1L) {
            eof = true
            return false
        }
        return true
    }

    /**
     * Index of the first byte equal to [delim] in the buffered (and
     * possibly to-be-buffered) bytes, measured from the current buffer
     * head. Returns `-1L` if upstream EOF is reached without finding
     * [delim]; on return, all remaining upstream bytes are in the buffer
     * (see [available]).
     */
    public suspend fun indexOf(delim: Byte): Long {
        var scanFrom = 0L
        while (true) {
            val idx = buf.indexOf(delim, scanFrom)
            if (idx >= 0) return idx
            scanFrom = buf.size
            if (!refill()) return -1L
        }
    }

    /**
     * Drain [byteCount] bytes from the buffer and UTF-8-decode them.
     * The bytes must already be buffered — call [indexOf] (or
     * [readAtMostTo] / refill helpers) first.
     */
    public fun readUtf8(byteCount: Long): String {
        require(byteCount >= 0) { "byteCount < 0" }
        require(buf.size >= byteCount) { "only ${buf.size} bytes buffered (asked for $byteCount)" }
        return buf.readString(byteCount)
    }

    /** Discard [byteCount] buffered bytes (e.g. the delimiter found by [indexOf]). */
    public fun skip(byteCount: Long) {
        require(buf.size >= byteCount) { "only ${buf.size} bytes buffered (asked to skip $byteCount)" }
        buf.skip(byteCount)
    }

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (buf.size == 0L) {
            if (!refill()) return -1L
        }
        val n = minOf(buf.size, byteCount)
        buf.readAtMostTo(sink, n)
        return n
    }

    override fun close() {
        upstream.close()
    }
}

/** Wrap this source in a [BufferedSuspendSource]. Idempotent on already-buffered sources. */
public fun SuspendSource.buffered(chunkSize: Int = 8 * 1024): BufferedSuspendSource =
    if (this is BufferedSuspendSource) this else BufferedSuspendSource(this, chunkSize)

/**
 * Batched line-reader over a buffered source. Scans the internal buffer
 * (and refills from upstream in [chunkSize]-byte chunks) for [delim],
 * then UTF-8-decodes the prefix in one pass. O(line length) work with
 * O(line length / chunkSize) upstream reads — versus the unbuffered
 * [SuspendSource.readUtf8DelimitedOrNull]'s one upstream read per byte.
 */
public suspend fun BufferedSuspendSource.readUtf8DelimitedOrNull(delim: Byte): String? {
    val idx = indexOf(delim)
    if (idx >= 0) {
        val s = readUtf8(idx)
        skip(1) // drop the delimiter byte
        return s
    }
    // EOF before delim: return whatever was buffered (the trailing
    // unterminated line), or null if upstream was empty all along.
    val rest = available
    return if (rest == 0L) null else readUtf8(rest)
}

/** Buffered counterpart to [SuspendSource.readUtf8LineOrNull]. */
public suspend fun BufferedSuspendSource.readUtf8LineOrNull(): String? = readUtf8DelimitedOrNull('\n'.code.toByte())
