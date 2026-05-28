package com.accucodeai.kash.tools.python3.pyodide.worker

/**
 * Wire format for the worker↔main filesystem RPC.
 *
 * Two SharedArrayBuffers shared by reference between the Pyodide worker and
 * the kash main thread:
 *  - **Control SAB** ([CONTROL_SLOTS] × Int32): op-code, request/response
 *    seqnos, status, payload length, and a few inline scalar args.
 *  - **Data SAB** ([DEFAULT_DATA_BYTES] of Uint8): variable-length payload
 *    in either direction — UTF-8 path strings inbound, read bytes / stat
 *    blobs / dir listings outbound, write bytes inbound.
 *
 * **Why a separate file from [SabStdin]:** the stdin ring is unidirectional
 * with no notion of an op-code or response — it's a producer-consumer byte
 * pipe. The FS bridge is request-response with multiple op kinds, so the
 * slot layout and the wait/notify discipline are different. Keeping them
 * separate avoids shoehorning one onto the other.
 *
 * **Synchronization discipline:**
 *  - Worker writes [SLOT_OP] + args + payload, atomically bumps [SLOT_REQ_SEQ],
 *    posts a `fs-rpc` message to main, then `Atomics.wait`s on [SLOT_RESP_SEQ].
 *  - Main wakes on the port message (it CAN'T `Atomics.wait` — browsers throw
 *    `TypeError` on main-thread waits), reads the op, dispatches to the kash
 *    [FileSystem][com.accucodeai.kash.fs.FileSystem], writes the response
 *    into data SAB + control SAB, atomically bumps [SLOT_RESP_SEQ], and
 *    `Atomics.notify`s the worker.
 *  - Per-request seqno mismatches indicate a protocol bug — both sides assert.
 *
 * **This object is platform-independent** (pure Int constants) and lives in
 * `commonMain` so the JVM `SabFsProtocolParityTest` can diff it against the
 * `OP_*` / `FS_SLOT_*` / `STATUS_*` constants hand-mirrored in
 * `pyodide-worker.js`. The JS side has no way to import these, so that test
 * is the drift guard — change a value here or there and it fails loudly.
 */
internal object SabFsProtocol {
    // Control SAB layout (Int32 slot indices).

    /** Worker bumps before each request; main reads to identify the request. */
    const val SLOT_REQ_SEQ: Int = 0

    /** Main bumps when response is ready; worker `Atomics.wait`s on this slot. */
    const val SLOT_RESP_SEQ: Int = 1

    /** Op code (worker writes per request). See [Op]. */
    const val SLOT_OP: Int = 2

    /** Response status (main writes; 0 = OK, negative = POSIX errno). */
    const val SLOT_STATUS: Int = 3

    /** Request: payload bytes in data SAB. Response: payload bytes returned. */
    const val SLOT_PAYLOAD_LEN: Int = 4

    /** Inline scalar arg slots (op-specific meaning). */
    const val SLOT_ARG0: Int = 5
    const val SLOT_ARG1: Int = 6
    const val SLOT_ARG2: Int = 7
    const val SLOT_ARG3: Int = 8

    /** Total Int32 slots in the control SAB — rounded up for headroom. */
    const val CONTROL_SLOTS: Int = 16

    /** Control SAB size in bytes. */
    const val CONTROL_BYTES: Int = CONTROL_SLOTS * 4

    /** Default data SAB capacity in bytes. 1 MiB covers almost every Python read in one round-trip. */
    const val DEFAULT_DATA_BYTES: Int = 1024 * 1024

    /** Op-code values. Keep in sync with `OP_*` constants in `pyodide-worker.js`. */
    object Op {
        const val NOP: Int = 0
        const val STAT: Int = 1
        const val LIST: Int = 2
        const val OPEN: Int = 3
        const val READ: Int = 4
        const val WRITE: Int = 5
        const val CLOSE: Int = 6
        const val MKDIR: Int = 7
        const val RMDIR: Int = 8
        const val UNLINK: Int = 9
        const val RENAME: Int = 10
    }

    /**
     * Status codes returned via [SLOT_STATUS] — **WASI errno numbers**, not
     * POSIX. Pyodide's Emscripten ships musl built against the WASI errno
     * table, so `new FS.ErrnoError(2)` means **EACCES**, not ENOENT.
     * Python on Pyodide is compiled against the same table — `OSError.__new__`
     * dispatches errno 2 to `PermissionError`. If you return POSIX 2 here,
     * Python raises `PermissionError: [Errno 2] Permission denied` for a
     * file that simply doesn't exist.
     *
     * Reference: `system/lib/libc/musl/arch/emscripten/bits/errno.h` in
     * the Emscripten tree. Keep these in sync with the `STATUS_*` constants
     * in `pyodide-worker.js`.
     */
    object Status {
        const val OK: Int = 0
        const val EACCES: Int = -2 // WASI 2  (POSIX 13)
        const val EBADF: Int = -8 // WASI 8  (POSIX 9)
        const val EEXIST: Int = -20 // WASI 20 (POSIX 17)
        const val EINVAL: Int = -28 // WASI 28 (POSIX 22)
        const val EIO: Int = -29 // WASI 29 (POSIX 5)
        const val EISDIR: Int = -31 // WASI 31 (POSIX 21)
        const val ENOENT: Int = -44 // WASI 44 (POSIX 2)
        const val ENOSPC: Int = -51 // WASI 51 (POSIX 28)
        const val ENOSYS: Int = -52 // WASI 52 (POSIX 38)
        const val ENOTDIR: Int = -54 // WASI 54 (POSIX 20)
    }

    /**
     * Stat blob layout in the data SAB (32 bytes, little-endian).
     *
     * | offset | bytes | field                  |
     * |-------:|------:|------------------------|
     * |      0 |     8 | size (i64)             |
     * |      8 |     8 | mtime epoch s (i64)    |
     * |     16 |     4 | mode (i32)             |
     * |     20 |     4 | type (i32; see [Type]) |
     * |     24 |     8 | reserved               |
     */
    object Stat {
        const val SIZE: Int = 32
        const val OFF_SIZE: Int = 0
        const val OFF_MTIME: Int = 8
        const val OFF_MODE: Int = 16
        const val OFF_TYPE: Int = 20
    }

    object Type {
        const val REGULAR: Int = 0
        const val DIRECTORY: Int = 1
        const val SYMLINK: Int = 2
    }

    /**
     * POSIX-flavored open(2) flags — values match musl/Emscripten. Mirrored
     * by the `O_*` constants in `pyodide-worker.js`; the parity test pins them.
     */
    object Open {
        const val O_RDONLY: Int = 0x0000
        const val O_WRONLY: Int = 0x0001
        const val O_RDWR: Int = 0x0002
        const val O_CREAT: Int = 0x0040
        const val O_TRUNC: Int = 0x0200
        const val O_APPEND: Int = 0x0400
    }
}
