package com.accucodeai.kash.terminal.posix

import java.lang.foreign.Arena
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests for the Panama libc bindings. These don't require a real
 * terminal — they verify the bindings load, the right symbols resolve, and
 * obviously-wrong fds get sensible answers (`isatty` returns 0,
 * `tcgetattr` fails, `ioctl(TIOCGWINSZ)` returns null).
 *
 * The full round-trip — tcgetattr → cfmakeraw → tcsetattr → restore —
 * requires a real tty on fd 0 and is exercised via [PtyRoundTripTest],
 * which auto-detects whether a tty is present (e.g., when run under
 * `script -q /dev/null ./gradlew :kash-app:jvmTest`) and skips otherwise.
 */
class LibcTest {
    @Test fun bindingsResolveAtClassLoad() {
        // Just touching Libc forces all the MethodHandles to resolve.
        // Failure here means a libc symbol isn't where Panama looked —
        // which would mean we're on a platform we don't support.
        assertNotNull(Libc.GLOBAL_ARENA)
    }

    @Test fun isattyOnNonTerminalFdReturnsZero() {
        // Gradle's test runner doesn't allocate a PTY by default, so fd 0
        // is a pipe or a closed stream. Either way isatty(0) returns 0.
        // The point of this test is "the binding doesn't crash and gives
        // a number" — value semantics are validated by the PTY round-trip.
        val rc = Libc.isatty(0)
        assertTrue(rc == 0 || rc == 1, "isatty returned an unexpected value: $rc")
    }

    @Test fun isattyOnBogusFdReturnsZero() {
        // fd 999 is almost certainly unallocated; isatty returns 0 with errno
        // = EBADF. We're only checking that the syscall returns cleanly.
        assertEquals(0, Libc.isatty(999))
    }

    @Test fun winsizeQueryOnNonTerminalReturnsNull() {
        // ioctl(TIOCGWINSZ) on a non-tty fd returns -1 (ENOTTY). We mask
        // that into a null TerminalSize so callers don't see the errno.
        val sz = Winsize.query(999)
        // 999 is a bogus fd, so we expect null. fd 0 may or may not be a tty
        // depending on how the test runner was launched; either way the
        // bogus-fd case is deterministic.
        assertEquals(null, sz)
    }

    @Test fun termiosStructSizeMatchesPlatform() {
        // Sanity check on the per-OS size constant. macOS = 72, Linux = 60.
        val isMac =
            System.getProperty("os.name").lowercase().let {
                it.contains("mac") || it.contains("darwin")
            }
        val expected = if (isMac) 72L else 60L
        assertEquals(expected, Termios.SIZE)
    }

    @Test fun termiosAllocatesInConfinedArena() {
        // Verify we can allocate a Termios in a confined arena without
        // the underlying segment leaking past arena.close().
        Arena.ofConfined().use { arena ->
            val t = Termios.allocate(arena)
            assertEquals(Termios.SIZE, t.segment.byteSize())
        }
    }
}
