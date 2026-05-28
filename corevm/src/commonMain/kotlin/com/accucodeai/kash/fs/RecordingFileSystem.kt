package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.flow.Flow

/**
 * Per-process [FileSystem] decorator that records data reads and mutations
 * to the machine's [FileAccessBus]. Sits *beneath* [OpenerBoundFs]
 * (OpenerBoundFs → RecordingFileSystem → machine fs) so it sees every call
 * a process makes through `ctx.process.fs` / `ctx.fs`, attributed to the
 * baked [opener].
 *
 * Recorded: [source]/`readBytes`/`openHandle`(RDONLY) → READ;
 * [sink]/`writeBytes`/`appendBytes`/`openHandle`(WR) → WRITE or CREATE;
 * [mkdirs] → CREATE; [remove] → DELETE; [chmod]/[setMtime] → META;
 * [createSymlink]/[createHardLink] → SYMLINK. Pure probes
 * ([exists]/[stat]/[list]/[readSymlink]) are deliberately NOT recorded.
 *
 * Per-mount policy keys off the [FsLabel] of the path's mount:
 *  - USER / EPHEMERAL / unlabeled → record reads **and** mutations.
 *  - HOST_BORROW → record **mutations only** (we care about host changes
 *    *we* make, not host reads).
 *  - ENGINE_CACHE / SYSTEM_BIN → record **nothing** (kills Python-stdlib
 *    and `/usr/bin` resolution noise).
 *
 * Recording happens *after* the delegate call succeeds, so failed opens
 * (missing file, read-only mount) don't produce phantom touches. The bus
 * emit is non-suspending and best-effort.
 */
internal class RecordingFileSystem(
    private val delegate: FileSystem,
    private val opener: KashProcess,
    private val bus: FileAccessBus,
) : FileSystem {
    // -------- recording core --------

    private fun labelOf(path: String): FsLabel? = (delegate as? MountedFileSystem)?.mountLabel(path)

    private fun shouldRecord(
        label: FsLabel?,
        kind: AccessKind,
    ): Boolean =
        when (label) {
            FsLabel.ENGINE_CACHE, FsLabel.SYSTEM_BIN -> false

            FsLabel.HOST_BORROW -> kind != AccessKind.READ

            // USER, EPHEMERAL, or unlabeled (raw InMemoryFs in tests):
            else -> true
        }

    private fun record(
        path: String,
        kind: AccessKind,
    ) {
        if (!bus.hasListeners) return // no subscribers → skip label lookup + allocation
        val label = labelOf(path)
        if (!shouldRecord(label, kind)) return
        bus.emit(
            FileAccess(
                path = path,
                kind = kind,
                label = label,
                pid = opener.pid,
                scopeId = opener.traceScopeId ?: 0L,
            ),
        )
    }

    /**
     * WRITE if [path] already exists, else CREATE. Probes the delegate, so
     * callers gate this behind [FileAccessBus.hasListeners] to avoid the
     * extra `exists` call on the untraced hot path.
     */
    private fun writeKind(path: String): AccessKind =
        if (delegate.exists(path, opener)) AccessKind.WRITE else AccessKind.CREATE

    // -------- read surface (records READ) --------

    override fun source(path: String): SuspendSource = source(path, opener)

    override fun source(
        path: String,
        opener: KashProcess?,
    ): SuspendSource = delegate.source(path, opener ?: this.opener).also { record(path, AccessKind.READ) }

    override fun sourceNoFollow(path: String): SuspendSource = sourceNoFollow(path, opener)

    override fun sourceNoFollow(
        path: String,
        opener: KashProcess?,
    ): SuspendSource = delegate.sourceNoFollow(path, opener ?: this.opener).also { record(path, AccessKind.READ) }

    override suspend fun readBytes(path: String): ByteArray = readBytes(path, opener)

    override suspend fun readBytes(
        path: String,
        opener: KashProcess?,
    ): ByteArray = delegate.readBytes(path, opener ?: this.opener).also { record(path, AccessKind.READ) }

    // -------- write surface (records WRITE/CREATE) --------

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = sink(path, append, mode, opener)

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
        opener: KashProcess?,
    ): SuspendSink {
        if (!bus.hasListeners) return delegate.sink(path, append, mode, opener ?: this.opener)
        val kind = writeKind(path)
        return delegate.sink(path, append, mode, opener ?: this.opener).also { record(path, kind) }
    }

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
    ): SuspendSink = sinkNoFollow(path, append, opener)

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
        opener: KashProcess?,
    ): SuspendSink {
        if (!bus.hasListeners) return delegate.sinkNoFollow(path, append, opener ?: this.opener)
        val kind = writeKind(path)
        return delegate.sinkNoFollow(path, append, opener ?: this.opener).also { record(path, kind) }
    }

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ) {
        if (!bus.hasListeners) return delegate.writeBytes(path, bytes, mode)
        val kind = writeKind(path)
        delegate.writeBytes(path, bytes, mode)
        record(path, kind)
    }

    override suspend fun appendBytes(
        path: String,
        bytes: ByteArray,
    ) {
        if (!bus.hasListeners) return delegate.appendBytes(path, bytes)
        val kind = writeKind(path)
        delegate.appendBytes(path, bytes)
        record(path, kind)
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        delegate.mkdirs(path, mode)
        record(path, AccessKind.CREATE)
    }

    override fun remove(path: String) {
        delegate.remove(path)
        record(path, AccessKind.DELETE)
    }

    override fun chmod(
        path: String,
        mode: Int,
    ) {
        delegate.chmod(path, mode)
        record(path, AccessKind.META)
    }

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ) {
        delegate.setMtime(path, epochSeconds)
        record(path, AccessKind.META)
    }

    override fun createSymlink(
        linkPath: String,
        target: String,
    ) {
        delegate.createSymlink(linkPath, target)
        record(linkPath, AccessKind.SYMLINK)
    }

    override fun createHardLink(
        linkPath: String,
        target: String,
    ) {
        delegate.createHardLink(linkPath, target)
        record(linkPath, AccessKind.SYMLINK)
    }

    override fun openHandle(
        path: String,
        accessMode: AccessMode,
        opener: KashProcess?,
    ): OpenFileDescription? {
        if (!bus.hasListeners) return delegate.openHandle(path, accessMode, opener ?: this.opener)
        val kind = if (accessMode == AccessMode.RDONLY) AccessKind.READ else writeKind(path)
        return delegate.openHandle(path, accessMode, opener ?: this.opener)?.also { record(path, kind) }
    }

    // `rename` is left to the interface default, which decomposes into
    // writeBytes/remove/mkdirs *on this instance* — so the move is recorded
    // as the CREATE(s) + DELETE it actually performs.

    // -------- probe / metadata surface — pure pass-through, NOT recorded --------

    override fun exists(path: String): Boolean = delegate.exists(path, opener)

    override fun exists(
        path: String,
        opener: KashProcess?,
    ): Boolean = delegate.exists(path, opener ?: this.opener)

    override fun isDirectory(path: String): Boolean = delegate.isDirectory(path, opener)

    override fun isDirectory(
        path: String,
        opener: KashProcess?,
    ): Boolean = delegate.isDirectory(path, opener ?: this.opener)

    override fun list(path: String): List<String> = delegate.list(path, opener)

    override fun list(
        path: String,
        opener: KashProcess?,
    ): List<String> = delegate.list(path, opener ?: this.opener)

    override fun stat(path: String): FileStat = delegate.stat(path, opener)

    override fun stat(
        path: String,
        opener: KashProcess?,
    ): FileStat = delegate.stat(path, opener ?: this.opener)

    override fun statLink(path: String): FileStat = delegate.statLink(path, opener)

    override fun statLink(
        path: String,
        opener: KashProcess?,
    ): FileStat = delegate.statLink(path, opener ?: this.opener)

    override fun listStat(path: String): List<FileStat> = delegate.listStat(path, opener)

    override fun listStat(
        path: String,
        opener: KashProcess?,
    ): List<FileStat> = delegate.listStat(path, opener ?: this.opener)

    override fun readSymlink(path: String): String = delegate.readSymlink(path, opener)

    override fun readSymlink(
        path: String,
        opener: KashProcess?,
    ): String = delegate.readSymlink(path, opener ?: this.opener)

    override fun commandSpec(path: String): CommandSpec? = delegate.commandSpec(path)

    override fun watch(path: String): Flow<FsEvent> = delegate.watch(path)
}
