package com.accucodeai.kash.api.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.io.Buffer
import kotlinx.io.bytestring.ByteString
import kotlinx.io.bytestring.isEmpty
import kotlinx.io.readByteString
import kotlinx.io.write
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * In-process pipe between two coroutines, backed by a bounded coroutine
 * [Channel]. Bounded capacity provides coroutine-level backpressure; closing
 * the [source] (read end) propagates a SIGPIPE-like [BrokenPipeException] to
 * the next write on [sink].
 *
 * Suspend-native: `write` and `read` park the *coroutine*, freeing the
 * dispatcher thread. No `runBlocking` — that was the v1 sin and is gone.
 *
 * Each pipe gets a process-unique [inode] number (monotonic counter, shared
 * across both ends). `/proc/<pid>/fd/N` and `/dev/fd/N` reports use it for
 * the `pipe:[<inode>]` synthetic path, matching Linux's behavior where the
 * same pipe seen on different fds reports the same inode.
 */
@OptIn(ExperimentalAtomicApi::class)
public class AsyncPipe(
    capacity: Int = 64,
) {
    private val channel: Channel<ByteString> = Channel(capacity)

    /** Process-unique inode for `pipe:[N]` synthetic paths. */
    public val inode: Long = nextInode.incrementAndFetch()

    public val sink: SuspendSink = ChannelSuspendSink(channel, inode)
    public val source: SuspendSource = ChannelSuspendSource(channel, inode)

    public companion object {
        /**
         * Seed the inode counter with wall-clock epoch millis so two unrelated
         * kash invocations produce visibly distinct `pipe:[N]` values rather
         * than every short-lived `-c` script reporting `pipe:[2]`. Within one
         * process the counter is still strictly monotonic; this just shifts
         * the origin so cross-process inodes look kernel-style (large, non-
         * obvious, unlikely to collide between invocations). Linux's real
         * pipe inodes also derive from a global counter; ours are
         * process-local but visually equivalent for the scripts that gate on
         * `pipe:[…]`-shape inodes.
         */
        private val nextInode =
            AtomicLong(
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds(),
            )
    }
}

/**
 * Side-channel marker for a stream that's one end of a pipe. Both
 * [AsyncPipe.sink] and [AsyncPipe.source] implement this so downstream code
 * (e.g. `installStdio` rebuilding fd 0/1/2 OFDs from raw streams) can recover
 * the pipe inode for `/proc/<pid>/fd/N` reporting without threading it
 * through the OFD constructors at every layer.
 */
public interface PipeEnd {
    public val pipeInode: Long
}

public class BrokenPipeException : kotlinx.io.IOException("broken pipe")

private class ChannelSuspendSink(
    private val channel: Channel<ByteString>,
    override val pipeInode: Long,
) : SuspendSink,
    PipeEnd {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (byteCount <= 0L) return
        val bs = source.readByteString(byteCount.toInt())
        try {
            channel.send(bs)
        } catch (_: ClosedSendChannelException) {
            // Reader closed the channel normally (drained then closed source).
            throw BrokenPipeException()
        } catch (e: CancellationException) {
            // Reader called source.close() which translates to channel.cancel().
            // That presents to senders as CancellationException with the
            // cancel cause. Distinguish "the reader gave up" (SIGPIPE) from
            // "our own coroutine was canceled by structured concurrency"
            // by inspecting isClosedForSend — set iff the channel itself is
            // closed/canceled, independent of caller-coroutine state.
            @OptIn(DelicateCoroutinesApi::class)
            if (channel.isClosedForSend) throw BrokenPipeException() else throw e
        }
    }

    override suspend fun flush() {
        // No-op — every `write` already enqueues atomically.
    }

    override fun close() {
        channel.close()
    }
}

private class ChannelSuspendSource(
    private val channel: Channel<ByteString>,
    override val pipeInode: Long,
) : SuspendSource,
    PipeEnd {
    private var pending: ByteString = ByteString()
    private var pendingOffset: Int = 0
    private var closed: Boolean = false

    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (closed) return -1L
        if (pending.isEmpty() || pendingOffset >= pending.size) {
            val next = channel.receiveCatching().getOrNull()
            if (next == null) {
                closed = true
                return -1L
            }
            pending = next
            pendingOffset = 0
        }
        val available = pending.size - pendingOffset
        val take = minOf(available.toLong(), byteCount).toInt()
        sink.write(pending, pendingOffset, pendingOffset + take)
        pendingOffset += take
        return take.toLong()
    }

    override fun close() {
        if (!closed) {
            closed = true
            channel.cancel()
        }
    }
}
