package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription

/**
 * Resolve `/dev/fd/N` / `/dev/std{in,out,err}` / `/proc/<pid>/fd/N` style
 * paths against [target]'s fd table and vend a fresh [OpenFileDescription]
 * onto the same underlying source/sink.
 *
 * Mirrors Linux's magic-symlink semantics for `/proc/self/fd/N`: opening
 * the path duplicates the entry rather than reopening the underlying
 * inode — the new OFD shares the original's `source`/`sink`/`isTty`/
 * `terminalControl`, with refcount 1 and `owning=false`, so refcount
 * decay on this fresh OFD cannot close the device the original fd still
 * holds.
 *
 * Access-mode checks mirror what real `/proc/self/fd/N` enforces: you
 * cannot widen access by opening the path (POSIX `EACCES`). Failures
 * throw [FileNotFound] — kash's stand-in for both `ENXIO` (slot empty)
 * and `EACCES` (incompatible mode), consistent with the rest of `DevFs`.
 */
internal fun dupFromFdTable(
    target: KashProcess,
    fd: Int,
    accessMode: AccessMode,
    reportedPath: String,
): OpenFileDescription {
    val src = target.fdTable[fd]?.ofd ?: throw FileNotFound(reportedPath)
    val readable = src.accessMode != AccessMode.WRONLY && src.source != null
    val writable = src.accessMode != AccessMode.RDONLY && src.sink != null
    val ok =
        when (accessMode) {
            AccessMode.RDONLY -> readable
            AccessMode.WRONLY -> writable
            AccessMode.RDWR -> readable && writable
        }
    if (!ok) throw FileNotFound(reportedPath)
    // Pass [src] as `underlying` so this fresh OFD's release() propagates
    // a release on the original. Without this, an `exec 4< /dev/fd/N`
    // chain breaks when the source slot (e.g. the procsub fd it was dup'd
    // from) gets closed via independent reclamation — the consumer's
    // OFD would still reference the now-closed pipe streams. With it, the
    // pipe stays alive as long as ANY derived OFD does.
    return OpenFileDescription(
        source = if (accessMode != AccessMode.WRONLY) src.source else null,
        sink = if (accessMode != AccessMode.RDONLY) src.sink else null,
        accessMode = accessMode,
        path = reportedPath,
        isTty = src.isTty,
        owning = false,
        terminalControl = src.terminalControl,
        pipeInode = src.pipeInode,
        underlying = src,
    )
}

/**
 * Compute the `readlink(2)` target for a magic-symlink fd path like
 * `/proc/<pid>/fd/N` or `/dev/fd/N`. Shared between [ProcFs] and [DevFs] so
 * both surface the same string for the same OFD.
 *
 * Order:
 *  - [ofd] is null (slot empty / process gone) → `anon_inode:[fd-<N>]` —
 *    matches what Linux reports for an opened-then-deleted file's stale
 *    `/proc/<pid>/fd` slot, and is the safest placeholder for "we don't
 *    know what's behind this fd."
 *  - OFD records a [OpenFileDescription.path] → use verbatim.
 *  - OFD is a tty → `/dev/tty`.
 *  - OFD has a [OpenFileDescription.pipeInode] → `pipe:[<inode>]`. Same
 *    pipe seen on two fds reports the same inode (matches Linux's
 *    `proc_inum` shape); different pipes get different inodes.
 *  - otherwise → `anon_inode:[fd-<N>]`.
 */
internal fun fdLinkTargetForOfd(
    ofd: OpenFileDescription?,
    fd: Int,
): String {
    if (ofd == null) return "anon_inode:[fd-$fd]"
    ofd.path?.let { return it }
    if (ofd.isTty) return "/dev/tty"
    ofd.pipeInode?.let { return "pipe:[$it]" }
    return "anon_inode:[fd-$fd]"
}
