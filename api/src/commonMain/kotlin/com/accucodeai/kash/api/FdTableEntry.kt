package com.accucodeai.kash.api

/**
 * One entry in a [KashProcess]'s file descriptor table — the per-*fd*
 * state, distinct from the kernel-side [OpenFileDescription] it points at.
 *
 * The split matters because some flags are per-fd and some are per-OFD:
 *
 *   - **Per-fd** (lives here): `FD_CLOEXEC`. Two `dup`'d fds onto the same
 *     OFD have independent CLOEXEC bits.
 *   - **Per-OFD** (lives on [OpenFileDescription]): file offset, status
 *     flags (`O_APPEND`, `O_NONBLOCK`), access mode, OFD locks. `dup`/fork
 *     produce new fds pointing to the same OFD, so they share these.
 *
 * `fork()` duplicates the table (new entries) but the entries point at the
 * *same* OFDs (refcounts bumped). `execve()` drops entries whose
 * [closeOnExec] is true; the OFDs they referenced are released.
 */
public data class FdTableEntry(
    public val ofd: OpenFileDescription,
    /**
     * `FD_CLOEXEC` — true ⇒ the fd is dropped from the table by
     * [KashProcess.execReset]. Default false (POSIX `open(2)` default,
     * unless `O_CLOEXEC` was specified).
     */
    public var closeOnExec: Boolean = false,
)
