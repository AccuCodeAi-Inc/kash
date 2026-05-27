package com.accucodeai.kash.terminal.posix

import com.accucodeai.kash.api.terminal.TerminalSize
import java.lang.foreign.Arena
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * `ioctl(fd, TIOCGWINSZ, &winsize)` wrapper. Same struct on macOS and Linux
 * (4 × uint16); only the `TIOCGWINSZ` request number differs between them —
 * macOS encodes ioctl request numbers with the size and direction bits per
 * BSD convention, Linux uses a flat opcode.
 *
 * Used by [PosixTerminalControl.size] and by kash-app's `WINCH` handler
 * to keep `$COLUMNS` / `$LINES` env vars in sync with the live window.
 */
internal object Winsize {
    private val IS_MAC: Boolean =
        System.getProperty("os.name").lowercase().let {
            it.contains("mac") || it.contains("darwin")
        }

    /**
     * `TIOCGWINSZ` request number.
     *  - macOS (Darwin sys/ttycom.h): `_IOR('t', 104, struct winsize)` =
     *    `0x40087468` (direction READ | size 8 | group 't' | num 104).
     *  - Linux (asm-generic/ioctls.h): flat `0x5413`.
     */
    private val TIOCGWINSZ: Long = if (IS_MAC) 0x40087468L else 0x5413L

    private val LAYOUT: MemoryLayout =
        MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("ws_row"),
            ValueLayout.JAVA_SHORT.withName("ws_col"),
            ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
            ValueLayout.JAVA_SHORT.withName("ws_ypixel"),
        )

    /**
     * Query the size of [fd]'s controlling tty. Returns null if the ioctl
     * fails — typically because [fd] isn't a terminal, or the terminal
     * doesn't report a size (rare; SSH/tmux without `LINES`/`COLUMNS`).
     */
    fun query(fd: Int = 0): TerminalSize? {
        Arena.ofConfined().use { arena ->
            val seg: MemorySegment = arena.allocate(LAYOUT)
            val rc = Libc.ioctl(fd, TIOCGWINSZ, seg)
            if (rc != 0) return null
            val rows = seg.get(ValueLayout.JAVA_SHORT, 0L).toInt() and 0xFFFF
            val cols = seg.get(ValueLayout.JAVA_SHORT, 2L).toInt() and 0xFFFF
            return TerminalSize(cols = cols, rows = rows)
        }
    }
}
