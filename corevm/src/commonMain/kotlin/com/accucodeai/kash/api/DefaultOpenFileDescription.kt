package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Construct a default [OpenFileDescription].
 */
public fun OpenFileDescription(
    source: SuspendSource? = null,
    sink: SuspendSink? = null,
    accessMode: AccessMode = AccessMode.RDWR,
    statusFlags: Int = 0,
    offset: Long = 0L,
    path: String? = null,
    isTty: Boolean = false,
    owning: Boolean = true,
    terminalControl: TerminalControl? = null,
    pipeInode: Long? = null,
    underlying: OpenFileDescription? = null,
): OpenFileDescription =
    DefaultOpenFileDescription(
        source = source,
        sink = sink,
        accessMode = accessMode,
        statusFlags = statusFlags,
        offset = offset,
        path = path,
        isTty = isTty,
        owning = owning,
        terminalControl = terminalControl,
        pipeInode = pipeInode,
        underlying = underlying,
    )

internal class DefaultOpenFileDescription(
    override val source: SuspendSource? = null,
    override val sink: SuspendSink? = null,
    override val accessMode: AccessMode = AccessMode.RDWR,
    override var statusFlags: Int = 0,
    override var offset: Long = 0L,
    override val path: String? = null,
    override val isTty: Boolean = false,
    override val owning: Boolean = true,
    override var signalOwner: Int? = null,
    override val ofdLocks: MutableList<FileLock> = mutableListOf(),
    override val terminalControl: TerminalControl? = null,
    override val pipeInode: Long? = null,
    /**
     * Optional inner OFD whose lifecycle this OFD piggybacks on. Set when
     * a magic `/dev/fd/N` open dups out a fresh OFD pointing at an existing
     * OFD's source/sink — the outer OFD is non-owning (it doesn't directly
     * own the streams) but it MUST keep the inner OFD's refcount alive so
     * the streams aren't closed under it. On construction we [retain] the
     * inner; on [release] we forward.
     */
    private val underlying: OpenFileDescription? = null,
) : OpenFileDescription {
    private var refs: Int = 1

    init {
        underlying?.retain()
    }

    override fun retain() {
        if (!owning && underlying == null) return
        refs++
    }

    override fun release() {
        if (!owning && underlying == null) return
        check(refs > 0) { "OpenFileDescription.release() called with refcount already 0" }
        if (--refs == 0) {
            // try/finally guards each close so a throw from source.close()
            // doesn't strand sink.close() or skip underlying.release() —
            // would otherwise leak the next OFD that occupies this slot.
            try {
                if (owning) {
                    try {
                        source?.close()
                    } finally {
                        sink?.close()
                    }
                }
            } finally {
                underlying?.release()
            }
        }
    }
}
