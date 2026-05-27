package com.accucodeai.kash.api

/**
 * Install [ofd] at slot [fd] in this process's fd table, releasing whatever
 * occupant was there. **Consumes** the caller's outstanding reference — do
 * NOT call [OpenFileDescription.retain] beforehand. A freshly constructed
 * OFD comes with refcount 1; this helper moves that reference into the
 * fd-table slot.
 *
 * Mirrors Linux's `fd_install()` plus the `close(old)` that happens when
 * an `open(2)` reuses an fd freed by a prior close.
 *
 * To install the same OFD at multiple fds (the `dup` semantic), use
 * [dupFd] — or call [OpenFileDescription.retain] yourself before each
 * additional [installFd].
 */
public fun KashProcess.installFd(
    fd: Int,
    ofd: OpenFileDescription,
) {
    fdTable
        .put(fd, FdTableEntry(ofd = ofd))
        ?.ofd
        ?.release()
}

/**
 * POSIX `dup2(srcFd, dstFd)` — make [dstFd] refer to the same OFD as
 * [srcFd], replacing any existing occupant at [dstFd]. Bumps the OFD's
 * refcount (now N+1 fds reference it) and releases the displaced entry.
 *
 * Returns the new fd ([dstFd]) on success, or `null` if [srcFd] is not
 * open — POSIX `EBADF` surfaced as a null sentinel. Callers translate
 * to their preferred diagnostic + exit code (typically the "Bad file
 * descriptor" stderr line + return 1). This replaces an earlier
 * exception-flavored variant that forced every call site to wrap in
 * `try { … } catch (IllegalArgumentException)`.
 *
 * No-op (returns [dstFd] unchanged) when `srcFd == dstFd` — POSIX 2008's
 * `dup2(x, x)` is defined to succeed without touching the OFD. We mirror
 * that to avoid an erroneous release of the OFD's last ref.
 */
public fun KashProcess.dupFd(
    srcFd: Int,
    dstFd: Int,
): Int? {
    if (srcFd == dstFd) return dstFd
    val src = fdTable[srcFd]?.ofd ?: return null
    src.retain()
    installFd(dstFd, src)
    return dstFd
}
