package com.accucodeai.kash.terminal.posix

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

/**
 * Opaque handle to a `struct termios` for use with libc tc{get,set}attr.
 *
 * We deliberately don't expose individual field accessors: every transition
 * we need (current → raw → restored) is expressible as
 * `current() → copy() → cfmakeraw() → apply()`, and the struct layout
 * differs subtly between macOS (NCCS=20, `unsigned long` flags, 72 bytes)
 * and Linux (NCCS=32, `unsigned int` flags, 60 bytes). Treating the struct
 * as a sized blob lets us skip per-OS field layouts entirely — the OS's own
 * `cfmakeraw` does the raw-mode transform in place, and we never inspect
 * individual flags from Kotlin.
 *
 * Lifetime: each instance owns a [MemorySegment] in the supplied [Arena].
 * Use a short-lived arena for one-off calls; for the long-lived "saved
 * attrs" segment that lives until shutdown, allocate in [Libc.GLOBAL_ARENA].
 */
internal class Termios private constructor(
    val segment: MemorySegment,
) {
    /** Apply this state to [fd] with `TCSANOW`. Returns 0 on success, -1 on error. */
    fun apply(fd: Int): Int = Libc.tcsetattr(fd, Libc.TCSANOW, segment)

    /** In-place: transform this state into a raw-mode termios. */
    fun makeRaw() {
        Libc.cfmakeraw(segment)
    }

    /** Allocate a byte-identical copy in [arena]. */
    fun copy(arena: Arena): Termios {
        val copy = arena.allocate(SIZE, 8L)
        MemorySegment.copy(segment, 0L, copy, 0L, SIZE)
        return Termios(copy)
    }

    companion object {
        private val IS_MAC: Boolean =
            System.getProperty("os.name").lowercase().let {
                it.contains("mac") || it.contains("darwin")
            }

        /**
         * `sizeof(struct termios)` on the running platform.
         *
         * macOS (Darwin bsd/sys/termios.h): 4×8 byte tcflag_t + 20 byte
         * c_cc[NCCS=20] + 4 byte pad + 2×8 byte speed_t = 72.
         *
         * Linux (glibc bits/termios-struct.h): 4×4 byte tcflag_t + 1 byte
         * c_line + 32 byte c_cc[NCCS=32] + 3 byte pad + 2×4 byte speed_t = 60.
         */
        val SIZE: Long = if (IS_MAC) 72L else 60L

        /** Allocate uninitialized termios storage in [arena]. */
        fun allocate(arena: Arena): Termios = Termios(arena.allocate(SIZE, 8L))

        /**
         * Read the current termios for [fd] into a fresh allocation in
         * [arena]. Throws [IllegalStateException] if `tcgetattr` returns
         * non-zero — typically means [fd] isn't a terminal (`ENOTTY`).
         */
        fun current(
            arena: Arena,
            fd: Int,
        ): Termios {
            val t = allocate(arena)
            val rc = Libc.tcgetattr(fd, t.segment)
            check(rc == 0) { "tcgetattr(fd=$fd) failed: rc=$rc (likely ENOTTY)" }
            return t
        }
    }
}
