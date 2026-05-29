package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer

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
        before: ByteArray? = null,
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
                before = before,
            ),
        )
    }

    /**
     * Snapshot a file's content immediately before it's overwritten, so the
     * emitted [FileAccess] can carry [FileAccess.before] for a diff. Returns
     * null unless a capturing observer is attached *and* this is an in-place
     * overwrite (`kind == WRITE`) of a path we'd actually record — a CREATE
     * has no prior content, and an unrecorded mount shouldn't pay the read.
     *
     * Two budgets keep capture from pinning unbounded memory: the file is
     * skipped if it exceeds [FileAccessBus.MAX_CAPTURE_FILE_BYTES] (checked
     * via a cheap, unrecorded `stat` before any read), and only the first
     * [FileAccessBus.MAX_CAPTURE_FILES] files of a capture session snapshot at
     * all. A skip yields null — the consumer degrades to a note, not a diff.
     * Best-effort: a read failure also yields null rather than aborting.
     */
    private suspend fun captureBefore(
        path: String,
        kind: AccessKind,
    ): ByteArray? {
        if (!bus.captureContent || kind != AccessKind.WRITE) return null
        if (!shouldRecord(labelOf(path), kind)) return null
        val size = runCatching { delegate.stat(path, opener).size }.getOrNull()
        if (size != null && size > FileAccessBus.MAX_CAPTURE_FILE_BYTES) return null
        if (!bus.tryReserveCaptureSlot()) return null
        return runCatching { delegate.readBytes(path, opener) }.getOrNull()
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
        val op = opener ?: this.opener
        if (bus.captureContent && kind == AccessKind.WRITE) {
            // Truncation happens at delegate-open, so we can't read "before"
            // up front (sink() is non-suspend; readBytes is suspend). Defer
            // the delegate open to the first *suspend* sink op, where we read
            // the prior content first. See [CaptureSink].
            return CaptureSink(path, append, mode, op, kind)
        }
        return delegate.sink(path, append, mode, op).also { record(path, kind) }
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
        val op = opener ?: this.opener
        if (bus.captureContent && kind == AccessKind.WRITE) {
            return CaptureSink(path, append, mode = -1, opener = op, kind = kind, noFollow = true)
        }
        return delegate.sinkNoFollow(path, append, op).also { record(path, kind) }
    }

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ) {
        if (!bus.hasListeners) return delegate.writeBytes(path, bytes, mode)
        val kind = writeKind(path)
        val before = captureBefore(path, kind)
        delegate.writeBytes(path, bytes, mode)
        record(path, kind, before)
    }

    override suspend fun appendBytes(
        path: String,
        bytes: ByteArray,
    ) {
        if (!bus.hasListeners) return delegate.appendBytes(path, bytes)
        val kind = writeKind(path)
        // For an append, "before" is the content the appended bytes extend —
        // capturing it lets a consumer show the appended lines as a diff.
        val before = captureBefore(path, kind)
        delegate.appendBytes(path, bytes)
        record(path, kind, before)
    }

    /**
     * Content-capturing sink used only when a capturing observer is attached
     * and the target already exists (an in-place overwrite). The delegate
     * sink — which truncates the file on open — is opened lazily on the first
     * *suspend* operation (`write`/`flush`), where we can first read the prior
     * content for [FileAccess.before]. The non-suspend [close] can't read, so
     * a sink that's opened and closed with no write (e.g. `: > file`) still
     * truncates but records `before = null`.
     */
    private inner class CaptureSink(
        private val path: String,
        private val append: Boolean,
        private val mode: Int,
        private val opener: KashProcess,
        private val kind: AccessKind,
        private val noFollow: Boolean = false,
    ) : SuspendSink {
        private var inner: SuspendSink? = null
        private var opened = false

        private fun openDelegate(): SuspendSink =
            if (noFollow) delegate.sinkNoFollow(path, append, opener) else delegate.sink(path, append, mode, opener)

        private suspend fun ensureOpenSuspending(): SuspendSink {
            inner?.let { return it }
            // Read prior content BEFORE the delegate truncates on open — via
            // captureBefore so the size / file-count budgets apply here too.
            val before = captureBefore(path, kind)
            val s = openDelegate()
            inner = s
            opened = true
            record(path, kind, before)
            return s
        }

        override suspend fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            ensureOpenSuspending().write(source, byteCount)
        }

        override suspend fun flush() {
            ensureOpenSuspending().flush()
        }

        override fun close() {
            val open = inner
            if (open != null) {
                open.close()
                return
            }
            // Never written to: still honor the open's side effect (truncate
            // an existing file) by opening + closing the delegate. close() is
            // non-suspend, so we can't read "before" here — record null.
            if (!opened) {
                opened = true
                openDelegate().close()
                record(path, kind, before = null)
            }
        }
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
