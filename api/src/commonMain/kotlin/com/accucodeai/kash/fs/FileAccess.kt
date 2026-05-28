package com.accucodeai.kash.fs

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What a process did to a path. Deliberately coarse: we record the
 * *intent* of the call, not byte counts. Per the design decision, only
 * actual data reads and mutations are recorded — pure metadata probes
 * (`stat`/`exists`/`list`/`readlink`) are NOT, so a `ls` or tab-completion
 * doesn't drown the signal.
 */
public enum class AccessKind {
    /** File opened for reading ([FileSystem.source] / `readBytes` / `openHandle` RDONLY). */
    READ,

    /** Existing file opened for writing / appending. */
    WRITE,

    /** A path brought into existence: `sink` on a missing path, `mkdirs`. */
    CREATE,

    /** Path removed ([FileSystem.remove]). */
    DELETE,

    /** Metadata mutated without touching contents ([FileSystem.chmod] / [FileSystem.setMtime]). */
    META,

    /** A symbolic or hard link created. */
    SYMLINK,
}

/**
 * One filesystem touch attributed to the process that performed it.
 *
 * Emitted on [com.accucodeai.kash.api.KashMachine.fileAccess] as commands
 * run. This is an out-of-band *metric* — it never reaches a command's
 * stdout/stderr. Consumers (AI telemetry, audit) subscribe to the bus and
 * group by [scopeId] to answer "what files did this command touch?".
 *
 * @property path   logical (pre-mount-resolution) absolute path.
 * @property kind   what was done — see [AccessKind].
 * @property label  which mount the path landed in, or null for an
 *                  unlabeled / non-[MountedFileSystem] backend. Lets a
 *                  consumer ignore engine-cache / system-bin churn.
 * @property pid    pid of the opener process.
 * @property scopeId correlation id grouping every access of one command
 *                  invocation (inherited across the fork subtree). 0 means
 *                  unscoped (e.g. shell-internal access outside a command).
 */
public data class FileAccess(
    val path: String,
    val kind: AccessKind,
    val label: FsLabel?,
    val pid: Int,
    val scopeId: Long,
)

/** The distinct paths that were read (deduped, first-seen order). */
public fun List<FileAccess>.reads(): List<String> =
    asSequence()
        .filter { it.kind == AccessKind.READ }
        .map { it.path }
        .distinct()
        .toList()

/**
 * The distinct paths that were mutated — created, written, deleted, or had
 * metadata/links changed (deduped, first-seen order).
 */
public fun List<FileAccess>.writes(): List<String> =
    asSequence()
        .filter { it.kind != AccessKind.READ }
        .map { it.path }
        .distinct()
        .toList()

/**
 * Machine-wide, best-effort stream of [FileAccess] events.
 *
 * Emission is non-suspending ([emit] uses `tryEmit`) so it can be called
 * from the synchronous [FileSystem] surface. Under extreme burst beyond
 * [bufferCapacity] events are dropped rather than blocking the filesystem —
 * this is a metric, not an audit log of record, matching the "best effort"
 * goal. Bump [bufferCapacity] or attach a fast collector if you need
 * tighter guarantees.
 */
@OptIn(ExperimentalAtomicApi::class)
public class FileAccessBus(
    bufferCapacity: Int = 1024,
) {
    private val _events =
        MutableSharedFlow<FileAccess>(
            replay = 0,
            extraBufferCapacity = bufferCapacity,
        )

    /**
     * Live, async stream — subscribe for long-running telemetry across many
     * commands. May drop under burst beyond the buffer (best effort).
     */
    public val events: SharedFlow<FileAccess> = _events.asSharedFlow()

    // Synchronous, in-process fan-out used for *complete* per-exec capture
    // (`ExecResult.touched`). Called inline on [emit], so by the time an
    // exec returns every event has reached its observer — no async drain.
    // Copy-on-write: the hot path reads an immutable snapshot; mutation is
    // rare (once per traced exec).
    private val observers = AtomicReference<List<(FileAccess) -> Unit>>(emptyList())

    /**
     * True iff anyone is listening — a streaming subscriber OR a sync
     * observer. The recording layer checks this and skips all work
     * (including allocating the [FileAccess]) when it's false, so an
     * untraced session with no subscribers pays nothing.
     */
    public val hasListeners: Boolean
        get() = observers.load().isNotEmpty() || _events.subscriptionCount.value > 0

    /** Record one access. Non-suspending. Async subscribers may drop on
     *  overflow; registered [addObserver] observers always see it. */
    public fun emit(access: FileAccess) {
        _events.tryEmit(access)
        val obs = observers.load()
        for (i in obs.indices) obs[i](access)
    }

    /** Register a synchronous observer. Pair with [removeObserver]. */
    public fun addObserver(observer: (FileAccess) -> Unit) {
        while (true) {
            val cur = observers.load()
            if (observers.compareAndSet(cur, cur + observer)) return
        }
    }

    /** Remove a previously-[addObserver]ed observer (by identity). */
    public fun removeObserver(observer: (FileAccess) -> Unit) {
        while (true) {
            val cur = observers.load()
            val idx = cur.indexOf(observer)
            if (idx < 0) return
            val next = cur.toMutableList().also { it.removeAt(idx) }
            if (observers.compareAndSet(cur, next)) return
        }
    }
}
