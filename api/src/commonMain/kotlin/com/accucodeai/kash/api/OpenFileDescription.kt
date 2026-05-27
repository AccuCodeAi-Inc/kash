package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.terminal.TerminalControl

/**
 * Kernel-side "open file description" (POSIX terminology). Owns the file
 * offset, the status flags (`O_APPEND`, `O_NONBLOCK`, access mode), the
 * underlying source/sink, and OFD-style locks.
 *
 * Crucial: an OFD is *shared* across fd-table entries created by `dup()` /
 * `fork()` — those entries see the same offset. A fresh `open()` of the
 * same path creates a *new* OFD with its own offset.
 *
 * What lives on the fd-table entry instead of here:
 *   - `FD_CLOEXEC` — per-fd, not per-OFD. See [FdTableEntry.closeOnExec].
 *
 * Interface so the concrete impl (`DefaultOpenFileDescription` in
 * `:corevm`) can evolve independently. Construct via the top-level
 * [OpenFileDescription] factory function exported from `:corevm`.
 *
 * **Refcount lifecycle.** A freshly constructed OFD is born with **one
 * outstanding reference**, owned by the caller. The contract that follows
 * mirrors Linux's `struct file`:
 *
 *  - Transferring the OFD into an `FdTableEntry` slot **consumes** that
 *    reference — do NOT [retain] before installing. The helpers
 *    `KashProcess.installFd(fd, ofd)` / `KashProcess.installStdio(...)`
 *    enforce this.
 *  - Sharing the OFD across an additional slot (the `dup`/`dup2`/`fork`
 *    semantics) requires an explicit [retain] before installing. The
 *    `KashProcess.dupFd(srcFd, dstFd)` helper does this for you.
 *  - Removing an OFD from a slot (`close(2)`, fd-table overwrite, process
 *    exit) calls [release]. At refcount zero the underlying source/sink
 *    is closed iff [owning] is true.
 *
 * Non-owning OFDs (the host stdio wrappers, capture buffers, pipe stages —
 * anything the shell still owns the stream of) treat [retain]/[release] as
 * no-ops so the underlying stream is never closed by the OFD lifecycle.
 *
 * **Scaffolding status:** source/sink/accessMode/isTty/owning/path/
 * terminalControl/statusFlags and the refcount lifecycle are
 * load-bearing — used by redirection (fd 0/1/2 and fd ≥ 3), DevFs
 * `/dev/tty`, ProcFs symlinks, `tty(1)`, and the `[ -t N ]` test.
 * [offset] and [signalOwner] are captured by the snapshot serializer
 * so they round-trip across persistence, but no in-shell feature reads
 * them yet — they wire up at runtime as `lseek(2)` / async-IO land.
 * [ofdLocks] is shape-only; `F_OFD_SETLK` isn't enforced.
 */
public interface OpenFileDescription {
    public val source: SuspendSource?
    public val sink: SuspendSink?

    /** O_RDONLY / O_WRONLY / O_RDWR — set at open() and immutable. */
    public val accessMode: AccessMode

    /**
     * Mutable status flags (`O_APPEND`, `O_NONBLOCK`, `O_SYNC`, …),
     * editable via `fcntl(F_SETFL)`. Bitset to match POSIX; constants
     * land alongside the redirection rewrite.
     */
    public var statusFlags: Int

    /**
     * Current byte offset. dup/fork share this OFD and therefore see the
     * same offset; fresh open() creates a new OFD with its own offset.
     */
    public var offset: Long

    /**
     * Path that opened this OFD (best-effort, for `/proc/self/fd`-style
     * introspection and diagnostics). Pipes/sockets leave this null.
     */
    public val path: String?

    /**
     * Pipe inode for IPC OFDs (anonymous pipes from [AsyncPipe]). When set,
     * `/proc/<pid>/fd/N` and `/dev/fd/N` reports `pipe:[<inode>]`. Both ends
     * of the same pipe share the inode, so the SAME pipe seen on different
     * fds (or different dups across fork) reports the same `pipe:[N]` — and
     * DIFFERENT pipes report different inodes. Null for non-pipe OFDs (use
     * [path] or [isTty] for those). Mirrors Linux's `proc_inum` shape.
     */
    public val pipeInode: Long?

    /**
     * F_SETOWN target for SIGIO / SIGURG delivery. Per-OFD per POSIX, so
     * dup'd fds share the same owner.
     */
    public var signalOwner: Int?

    /**
     * POSIX `isatty(2)` — true iff this OFD is connected to a terminal
     * (the host tty, or a future pty). Set at open time; immutable after.
     *
     * Replaces the historical per-CommandContext `stdinIsTty`/`stdoutIsTty`
     * /`stderrIsTty` triple — now derivable as `fdTable[N]?.ofd?.isTty`.
     * A redirected fd (e.g. `>FILE`) opens an OFD with `isTty = false`,
     * which is exactly what `[ -t N ]` and tool-level "should I drop to a
     * REPL" gating need to ask.
     */
    public val isTty: Boolean

    /**
     * Non-null iff this OFD wraps a raw-mode-capable terminal device — the
     * handle a tool like `nano` or kash's own REPL uses to flip the tty
     * into raw mode and read decoded keys. Null for non-tty OFDs (files,
     * pipes, command-substitution buffers) and for tty OFDs the host didn't
     * wire a control to. Tools should prefer this over any session-global
     * lookup, so that redirection (`tool </dev/null`) correctly hides the
     * terminal from the tool.
     *
     * **Pure capability handle.** This field is a capability *only* —
     * `enterRawMode`/`exitRawMode` (termios mutators) plus a `readKey()`
     * convenience that returns decoded keys. It does NOT carry a parallel
     * byte stream. Raw bytes always flow through [source]; in raw mode the
     * implementation routes them to its escape decoder, in cooked mode they
     * flow straight to [source]. There is exactly one host-fd reader per
     * stdin OFD.
     */
    public val terminalControl: TerminalControl?

    /**
     * OFD-style `F_OFD_SETLK` locks. Per-OFD; survive fork as long as the
     * OFD survives; released when the OFD's refcount hits zero. Phase 1:
     * placeholder, no enforcement.
     */
    public val ofdLocks: MutableList<FileLock>

    /**
     * Whether this OFD *owns* its underlying [source]/[sink]. True (default)
     * means [release] closes them at refcount zero, the way `close(2)` does
     * for a real file. False means [retain]/[release] are no-ops — used to
     * wrap shell-managed stdio (the host terminal, command-substitution
     * buffers, pipe stages) into an OFD for `[ -t N ]` introspection
     * without giving the OFD lifecycle control over the underlying stream.
     */
    public val owning: Boolean

    /** Bump the refcount — a new [FdTableEntry] now points at this OFD. */
    public fun retain()

    /** Decrement the refcount; close source/sink at zero. */
    public fun release()
}

/**
 * Access mode set at `open(2)` time. Immutable after open — `fcntl(F_SETFL)`
 * can change status flags but not the access mode.
 */
public enum class AccessMode { RDONLY, WRONLY, RDWR }

/**
 * Region lock held by an OFD (`F_OFD_SETLK` / `flock(2)`-style). Phase 1
 * placeholder — kash has no locking enforcement today.
 */
public data class FileLock(
    val start: Long,
    val len: Long,
    val type: LockType,
)

public enum class LockType { READ, WRITE }
