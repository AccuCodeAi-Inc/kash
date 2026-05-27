package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.ControllingTty
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The bidirectional-terminal install path: when stdio is wired to a
 * controlling terminal, fds 0/1/2 all carry source + sink + tty traits,
 * so `cat /dev/stdout` and `echo > /dev/stdin` work like they do on
 * Linux (where the kernel opens /dev/tty O_RDWR and dups into 0/1/2).
 */
class InstallStdioTtyTest {
    private fun bundle(term: TerminalControl = FakeTerminalControl()): ControllingTty =
        ControllingTty(
            source = Buffer().asSuspendSource(),
            sink = Buffer().asSuspendSink(),
            terminalControl = term,
        )

    private fun newProcess(): KashProcess {
        val machine = KashMachine(fs = InMemoryFs())
        return machine.ensureInit()
    }

    @Test fun installStdio_ttyBundle_allThreeFdsAreRdwr() =
        runTest {
            val tty = bundle()
            val p = newProcess()
            p.installStdio(tty)
            for (fd in 0..2) {
                val ofd = p.fdTable[fd]?.ofd
                assertNotNull(ofd, "fd $fd not installed")
                assertEquals(AccessMode.RDWR, ofd.accessMode, "fd $fd should be RDWR")
                assertTrue(ofd.isTty, "fd $fd should be tty")
                assertNotNull(ofd.source, "fd $fd needs a source (for cat /dev/std*)")
                assertNotNull(ofd.sink, "fd $fd needs a sink")
                assertEquals("/dev/tty", ofd.path)
            }
        }

    @Test fun installStdio_ttyBundle_terminalControlOnAllThreeFds() =
        runTest {
            val term = FakeTerminalControl()
            val tty = bundle(term)
            val p = newProcess()
            p.installStdio(tty)
            for (fd in 0..2) {
                assertSame(term, p.fdTable[fd]?.ofd?.terminalControl)
            }
        }

    @Test fun installStdio_ttyBundle_allShareUnderlyingSource() =
        runTest {
            // Each fd is its own OFD (independent f_pos / f_flags), but
            // they all reference the same underlying source — matching
            // Linux's "three separate `struct file`s onto the same
            // `tty_struct`."
            val tty = bundle()
            val p = newProcess()
            p.installStdio(tty)
            assertSame(tty.source, p.fdTable[0]?.ofd?.source)
            assertSame(tty.source, p.fdTable[1]?.ofd?.source)
            assertSame(tty.source, p.fdTable[2]?.ofd?.source)
            // Distinct OFDs though.
            assertTrue(p.fdTable[0]?.ofd !== p.fdTable[1]?.ofd)
            assertTrue(p.fdTable[1]?.ofd !== p.fdTable[2]?.ofd)
        }

    @Test fun installStdio_ttyBundle_stderrOverride_keepsRdwrTty() =
        runTest {
            // Caller may want fd 2 on a distinct sink (e.g. JVM
            // System.err vs System.out). It still inherits the RDWR + tty
            // shape because errors are written to the same terminal
            // *device*; the override only changes which kotlinx-io sink
            // receives the bytes.
            val tty = bundle()
            val errSink = Buffer().asSuspendSink()
            val p = newProcess()
            p.installStdio(tty, stderr = errSink)

            assertSame(tty.sink, p.fdTable[1]?.ofd?.sink, "fd 1 keeps tty.sink")
            assertSame(errSink, p.fdTable[2]?.ofd?.sink, "fd 2 uses override")
            assertEquals(AccessMode.RDWR, p.fdTable[2]?.ofd?.accessMode)
            assertTrue(p.fdTable[2]?.ofd?.isTty == true)
        }

    @Test fun installStdio_ttyBundle_unblocksCatDevStdout() =
        runTest {
            // The original bug: `cat /dev/stdout` failed because fd 1
            // had no source. Under the new install path, fd 1 IS
            // readable — `dupFromFdTable(opener, 1, RDONLY, …)` succeeds
            // because fd 1's accessMode is RDWR with a non-null source.
            val tty = bundle()
            val p = newProcess()
            p.installStdio(tty)
            val fd1 = p.fdTable[1]!!.ofd
            assertEquals(AccessMode.RDWR, fd1.accessMode)
            assertNotNull(fd1.source)
        }
}
