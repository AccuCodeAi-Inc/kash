package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FsEvent
import kotlinx.coroutines.flow.Flow

/**
 * Per-process [FileSystem] facade. Every read-surface method forwards
 * to the underlying FS with [opener] baked in, so a tool calling
 * `ctx.process.fs.stat(p)` automatically passes its own process as the
 * opener — mirroring how Linux's VFS auto-threads `current` into
 * filesystem operations.
 *
 * Constructed lazily by [KashProcess.fs] (the getter on the interface)
 * and disposable: callers never need to manage its lifecycle.
 *
 * Write-side methods (mkdirs, remove, chmod, setMtime, createSymlink,
 * createHardLink, writeBytes, appendBytes, sink, sinkNoFollow) don't
 * carry opener — POSIX writes don't depend on calling-process state
 * the way per-process reads (`/proc/self`, `/dev/tty`) do. They route
 * straight through.
 *
 * The facade's identity is the wrapper instance, not the underlying
 * FS — so consumers can rely on `ctx.process.fs === ctx.process.fs`
 * being true within a coroutine, but two processes get distinct facades.
 */
internal class OpenerBoundFs(
    private val delegate: FileSystem,
    private val opener: KashProcess,
) : FileSystem {
    // -------- read surface — opener-bound forwards --------

    override fun exists(path: String): Boolean = delegate.exists(path, opener)

    override fun isDirectory(path: String): Boolean = delegate.isDirectory(path, opener)

    override fun source(path: String): SuspendSource = delegate.source(path, opener)

    override fun sourceNoFollow(path: String): SuspendSource = delegate.sourceNoFollow(path, opener)

    override fun list(path: String): List<String> = delegate.list(path, opener)

    override fun stat(path: String): FileStat = delegate.stat(path, opener)

    override fun statLink(path: String): FileStat = delegate.statLink(path, opener)

    override fun readSymlink(path: String): String = delegate.readSymlink(path, opener)

    override fun listStat(path: String): List<FileStat> = delegate.listStat(path, opener)

    override suspend fun readBytes(path: String): ByteArray = delegate.readBytes(path, opener)

    override fun openHandle(
        path: String,
        accessMode: AccessMode,
        opener: KashProcess?,
    ): OpenFileDescription? = delegate.openHandle(path, accessMode, opener ?: this.opener)

    // -------- opener-aware overloads — explicit opener wins --------
    //
    // If a caller threads an explicit opener (rare; usually the facade
    // is the opener bearer), respect it. Otherwise fall back to the
    // baked-in opener.

    override fun exists(
        path: String,
        opener: KashProcess?,
    ): Boolean = delegate.exists(path, opener ?: this.opener)

    override fun isDirectory(
        path: String,
        opener: KashProcess?,
    ): Boolean = delegate.isDirectory(path, opener ?: this.opener)

    override fun source(
        path: String,
        opener: KashProcess?,
    ): SuspendSource = delegate.source(path, opener ?: this.opener)

    override fun sourceNoFollow(
        path: String,
        opener: KashProcess?,
    ): SuspendSource = delegate.sourceNoFollow(path, opener ?: this.opener)

    override fun list(
        path: String,
        opener: KashProcess?,
    ): List<String> = delegate.list(path, opener ?: this.opener)

    override fun stat(
        path: String,
        opener: KashProcess?,
    ): FileStat = delegate.stat(path, opener ?: this.opener)

    override fun statLink(
        path: String,
        opener: KashProcess?,
    ): FileStat = delegate.statLink(path, opener ?: this.opener)

    override fun readSymlink(
        path: String,
        opener: KashProcess?,
    ): String = delegate.readSymlink(path, opener ?: this.opener)

    override fun listStat(
        path: String,
        opener: KashProcess?,
    ): List<FileStat> = delegate.listStat(path, opener ?: this.opener)

    override suspend fun readBytes(
        path: String,
        opener: KashProcess?,
    ): ByteArray = delegate.readBytes(path, opener ?: this.opener)

    // -------- write surface — straight pass-through --------

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = delegate.sink(path, append, mode, opener)

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
        opener: KashProcess?,
    ): SuspendSink = delegate.sink(path, append, mode, opener ?: this.opener)

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
    ): SuspendSink = delegate.sinkNoFollow(path, append, opener)

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
        opener: KashProcess?,
    ): SuspendSink = delegate.sinkNoFollow(path, append, opener ?: this.opener)

    override fun mkdirs(
        path: String,
        mode: Int,
    ): Unit = delegate.mkdirs(path, mode)

    override fun remove(path: String): Unit = delegate.remove(path)

    override fun chmod(
        path: String,
        mode: Int,
    ): Unit = delegate.chmod(path, mode)

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ): Unit = delegate.setMtime(path, epochSeconds)

    override fun createSymlink(
        linkPath: String,
        target: String,
    ): Unit = delegate.createSymlink(linkPath, target)

    override fun createHardLink(
        linkPath: String,
        target: String,
    ): Unit = delegate.createHardLink(linkPath, target)

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ): Unit = delegate.writeBytes(path, bytes, mode)

    override suspend fun appendBytes(
        path: String,
        bytes: ByteArray,
    ): Unit = delegate.appendBytes(path, bytes)

    override fun commandSpec(path: String): com.accucodeai.kash.api.CommandSpec? = delegate.commandSpec(path)

    override fun watch(path: String): Flow<FsEvent> = delegate.watch(path)
}
