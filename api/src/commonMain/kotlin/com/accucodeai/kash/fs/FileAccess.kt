package com.accucodeai.kash.fs

import kotlinx.coroutines.channels.Channel
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
 * @property before the file's content immediately *before* this mutation,
 *                  captured by the recording layer. Populated ONLY when a
 *                  capturing observer is attached (see
 *                  [FileAccessBus.captureContent]) and only for in-place
 *                  overwrites of an existing file (`WRITE` / append). `null`
 *                  in every other case: capture off, a `CREATE` (no prior
 *                  content), a `DELETE`/`META`/`SYMLINK`, or a path the layer
 *                  couldn't read back. Lets a consumer render a before→after
 *                  diff without a separate pre-read; `after` is just the
 *                  file's current content, which the consumer reads itself.
 */
public data class FileAccess(
    val path: String,
    val kind: AccessKind,
    val label: FsLabel?,
    val pid: Int,
    val scopeId: Long,
    val before: ByteArray? = null,
) {
    // `before` is a ByteArray, so the generated data-class equals/hashCode
    // would compare it by identity — surprising for an otherwise value-like
    // event. Override with content semantics so two FileAccess values that
    // captured the same prior bytes compare equal.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileAccess) return false
        return path == other.path &&
            kind == other.kind &&
            label == other.label &&
            pid == other.pid &&
            scopeId == other.scopeId &&
            before.contentEquals(other.before)
    }

    override fun hashCode(): Int {
        var result = path.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + pid
        result = 31 * result + scopeId.hashCode()
        result = 31 * result + (before?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * The mutations (non-[AccessKind.READ]) deduped to the *first* event per
 * path, in first-seen order. The first event is the one that carries the
 * true pre-command [FileAccess.before] (a later re-write of the same path in
 * the same command would snapshot the already-modified content). Pair with
 * the path's current content to render a before→after diff.
 */
public fun List<FileAccess>.mutations(): List<FileAccess> =
    asSequence()
        .filter { it.kind != AccessKind.READ }
        .distinctBy { it.path }
        .toList()

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
    // rare (once per traced exec). Each entry carries whether that observer
    // asked for [FileAccess.before] content capture.
    private class Subscriber(
        val observer: (FileAccess) -> Unit,
        val captureContent: Boolean,
    )

    private val observers = AtomicReference<List<Subscriber>>(emptyList())

    /**
     * True iff anyone is listening — a streaming subscriber OR a sync
     * observer. The recording layer checks this and skips all work
     * (including allocating the [FileAccess]) when it's false, so an
     * untraced session with no subscribers pays nothing.
     */
    public val hasListeners: Boolean
        get() = observers.load().isNotEmpty() || _events.subscriptionCount.value > 0

    /**
     * True iff at least one attached observer asked for content capture (see
     * [addObserver]'s `captureContent`). The recording layer gates the extra
     * pre-mutation read on this, so the coarse default path pays nothing.
     */
    public val captureContent: Boolean
        get() = observers.load().any { it.captureContent }

    // Per-capture-session budget for [FileAccess.before] snapshots, so a
    // command that overwrites thousands of files can't make the recording
    // layer buffer thousands of before-images. Reset at the start of each
    // capturing [traced] window; consulted by the recording layer per write.
    private val captureFilesUsed = AtomicReference(0)

    /** Reset the file-count budget. Called when a capturing trace begins. */
    public fun resetCaptureBudget() {
        captureFilesUsed.store(0)
    }

    /**
     * Reserve one before-capture slot, returning false once
     * [MAX_CAPTURE_FILES] have been taken this session. The recording layer
     * calls this only after deciding a write is otherwise worth capturing, so
     * skipped/over-budget files cost no read and buffer no bytes.
     */
    public fun tryReserveCaptureSlot(): Boolean {
        while (true) {
            val cur = captureFilesUsed.load()
            if (cur >= MAX_CAPTURE_FILES) return false
            if (captureFilesUsed.compareAndSet(cur, cur + 1)) return true
        }
    }

    /** Record one access. Non-suspending. Async subscribers may drop on
     *  overflow; registered [addObserver] observers always see it. */
    public fun emit(access: FileAccess) {
        _events.tryEmit(access)
        val obs = observers.load()
        for (i in obs.indices) obs[i].observer(access)
    }

    /**
     * Register a synchronous observer. Pair with [removeObserver].
     *
     * Set [captureContent] to opt this consumer into [FileAccess.before]
     * content capture — the recording layer then snapshots a file's prior
     * content before overwriting it. Costs an extra read per in-place write
     * while any capturing observer is attached, so leave it off unless you
     * actually diff.
     */
    public fun addObserver(
        observer: (FileAccess) -> Unit,
        captureContent: Boolean = false,
    ) {
        val sub = Subscriber(observer, captureContent)
        while (true) {
            val cur = observers.load()
            if (observers.compareAndSet(cur, cur + sub)) return
        }
    }

    /** Remove a previously-[addObserver]ed observer (by identity). */
    public fun removeObserver(observer: (FileAccess) -> Unit) {
        while (true) {
            val cur = observers.load()
            val idx = cur.indexOfFirst { it.observer === observer }
            if (idx < 0) return
            val next = cur.toMutableList().also { it.removeAt(idx) }
            if (observers.compareAndSet(cur, next)) return
        }
    }

    public companion object {
        /**
         * Largest prior-content snapshot [FileAccess.before] will hold per
         * file. A file bigger than this is overwritten normally but its
         * before-image is skipped (diff degrades to a note) — a multi-MB
         * diff is unreadable anyway, and buffering it wastes memory.
         */
        public const val MAX_CAPTURE_FILE_BYTES: Long = 1L * 1024 * 1024

        /**
         * Most files one capturing trace will snapshot. Past this, further
         * writes record no before-image — bounds the memory a single command
         * (e.g. a recursive rewrite) can pin at [MAX_CAPTURE_FILE_BYTES] each.
         */
        public const val MAX_CAPTURE_FILES: Int = 64
    }
}

/** A traced exec's result: the body's [value] plus the files it [touched]. */
public data class TracedResult<T>(
    val value: T,
    val touched: List<FileAccess>,
)

/**
 * Run [body] with a synchronous observer attached to this bus, returning the
 * body's result paired with every [FileAccess] recorded during it, in order.
 *
 * This is the shared core of file-access tracing: the embedding facade
 * (`Kash.exec(traceAccess = true)` → [ExecResult.touched][com.accucodeai.kash.api.ExecResult.touched])
 * and the agent's persistent shell both wrap their exec in this rather than
 * re-deriving the observer / channel / drain dance. Set [captureContent] to
 * also populate [FileAccess.before] for before→after diffs.
 *
 * The observer feeds an unbounded [Channel] inline on [emit], so by the time
 * [body] returns every event is already buffered — the post-drain never
 * suspends and sees the complete set.
 */
public suspend fun <T> FileAccessBus.traced(
    captureContent: Boolean = false,
    body: suspend () -> T,
): TracedResult<T> {
    val channel = Channel<FileAccess>(Channel.UNLIMITED)
    val observer: (FileAccess) -> Unit = { channel.trySend(it) }
    if (captureContent) resetCaptureBudget()
    addObserver(observer, captureContent)
    val value =
        try {
            body()
        } finally {
            removeObserver(observer)
            channel.close()
        }
    val touched = ArrayList<FileAccess>()
    while (true) {
        val r = channel.tryReceive()
        if (r.isSuccess) touched.add(r.getOrThrow()) else break
    }
    return TracedResult(value, touched)
}
