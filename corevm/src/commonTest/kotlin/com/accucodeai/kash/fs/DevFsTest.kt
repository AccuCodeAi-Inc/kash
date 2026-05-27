package com.accucodeai.kash.fs

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.Session
import com.accucodeai.kash.api.installFd
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.api.terminal.ControllingTty
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.test.FakeTerminalControl
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers DevFs's per-process resolution of `/dev/tty` via
 * `opener.session().controllingTty`, plus the always-available `/dev/null`
 * surface. Mirrors Linux's `open("/dev/tty")` semantics:
 *
 *  - opener with a session that owns a tty → fresh OFD, terminalControl
 *    populated, source/sink point at the session's tty
 *  - opener with no session, or session with no tty → FileNotFound
 *    (kash's stand-in for POSIX ENXIO)
 *  - null opener → same: no per-process context, no /dev/tty
 *  - `/dev/null` works in all cases — no opener context needed
 */
class DevFsTest {
    private fun bundleWith(term: TerminalControl = FakeTerminalControl()): ControllingTty =
        ControllingTty(source = Buffer().asSuspendSource(), sink = Buffer().asSuspendSink(), terminalControl = term)

    private fun bootMachine(): KashMachine = KashMachine(fs = InMemoryFs())

    private fun bootProcess(
        machine: KashMachine = bootMachine(),
        sid: Int = 1,
        registerSession: Session? = null,
    ): KashProcess {
        val p = machine.ensureInit()
        p.sid = sid
        if (registerSession != null) machine.sessions[registerSession.sid] = registerSession
        return p
    }

    @Test fun devNull_existsAndOpens_withOrWithoutOpener() =
        runTest {
            val fs = DevFs()
            assertTrue(fs.exists("/null"))
            // openHandle with no opener still works for /dev/null.
            val ofd = fs.openHandle("/null", AccessMode.RDWR, opener = null)
            assertNotNull(ofd)
            assertEquals("/dev/null", ofd.path)
            assertEquals(false, ofd.isTty)
            assertNull(ofd.terminalControl)
            // Source returns EOF immediately; sink discards.
            val src = ofd.source!!
            val buf = Buffer()
            assertEquals(-1L, src.readAtMostTo(buf, 1024))
        }

    @Test fun devNull_accessModeGatesSourceSink() =
        runTest {
            val fs = DevFs()
            val ro = fs.openHandle("/null", AccessMode.RDONLY, opener = null)!!
            assertNotNull(ro.source)
            assertNull(ro.sink)

            val wo = fs.openHandle("/null", AccessMode.WRONLY, opener = null)!!
            assertNull(wo.source)
            assertNotNull(wo.sink)

            val rw = fs.openHandle("/null", AccessMode.RDWR, opener = null)!!
            assertNotNull(rw.source)
            assertNotNull(rw.sink)
        }

    @Test fun devTty_nullOpener_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            // No opener → no per-process context → ENXIO equivalent.
            assertFailsWith<FileNotFound> {
                fs.openHandle("/tty", AccessMode.RDWR, opener = null)
            }
        }

    @Test fun devTty_openerWithoutSessionRegistered_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            val machine = bootMachine()
            // Opener has sid=1 but machine.sessions is empty — simulates a
            // process whose session has been torn down or was never registered.
            val opener = bootProcess(machine, sid = 1, registerSession = null)
            assertFailsWith<FileNotFound> {
                fs.openHandle("/tty", AccessMode.RDWR, opener = opener)
            }
        }

    @Test fun devTty_sessionWithoutControllingTty_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            val machine = bootMachine()
            // Session exists but has no controllingTty (post-setsid daemon, or
            // a kash invoked non-interactively).
            val opener =
                bootProcess(
                    machine,
                    sid = 1,
                    registerSession = Session(sid = 1, leaderPid = 1, controllingTty = null),
                )
            assertFailsWith<FileNotFound> {
                fs.openHandle("/tty", AccessMode.RDWR, opener = opener)
            }
        }

    @Test fun devTty_openerWithSessionAndTty_returnsFreshOfdWithTerminalControl() =
        runTest {
            val fs = DevFs()
            val machine = bootMachine()
            val term = FakeTerminalControl()
            val bundle = bundleWith(term)
            val opener =
                bootProcess(
                    machine,
                    sid = 1,
                    registerSession = Session(sid = 1, leaderPid = 1, controllingTty = bundle),
                )

            val ofd = fs.openHandle("/tty", AccessMode.RDWR, opener = opener)
            assertNotNull(ofd)
            assertEquals("/dev/tty", ofd.path)
            assertEquals(true, ofd.isTty)
            assertSame(term, ofd.terminalControl)
            assertSame(bundle.source, ofd.source)
            assertSame(bundle.sink, ofd.sink)
            // Non-owning: kash still owns the underlying tty device.
            assertEquals(false, ofd.owning)
        }

    @Test fun devTty_eachOpenReturnsFreshOfd_pointingAtSameDevice() =
        runTest {
            // Linux: two open("/dev/tty") calls return two distinct struct
            // files (independent f_pos, f_flags) onto the same struct
            // tty_struct. Mirror that.
            val fs = DevFs()
            val machine = bootMachine()
            val bundle = bundleWith()
            val opener =
                bootProcess(
                    machine,
                    sid = 1,
                    registerSession = Session(sid = 1, leaderPid = 1, controllingTty = bundle),
                )

            val a = fs.openHandle("/tty", AccessMode.RDWR, opener = opener)!!
            val b = fs.openHandle("/tty", AccessMode.RDWR, opener = opener)!!
            assertTrue(a !== b, "each open should yield a fresh OFD")
            assertSame(a.terminalControl, b.terminalControl)
            assertSame(a.source, b.source)
            assertSame(a.sink, b.sink)
        }

    @Test fun devTty_differentSessions_resolveToTheirOwnTty() =
        runTest {
            // Two processes, two sessions, two different terminals. Each
            // opener's open("/dev/tty") MUST resolve to its own session's
            // tty — the heart of the per-process resolution model.
            val fs = DevFs()
            val machine = bootMachine()
            val termA = FakeTerminalControl()
            val termB = FakeTerminalControl()
            val bundleA = bundleWith(termA)
            val bundleB = bundleWith(termB)
            machine.sessions[1] = Session(sid = 1, leaderPid = 1, controllingTty = bundleA)
            machine.sessions[2] = Session(sid = 2, leaderPid = 2, controllingTty = bundleB)
            val pA = machine.ensureInit().also { it.sid = 1 }
            val pB = pA.fork().also { it.sid = 2 }

            val ofdA = fs.openHandle("/tty", AccessMode.RDWR, opener = pA)!!
            val ofdB = fs.openHandle("/tty", AccessMode.RDWR, opener = pB)!!
            assertSame(termA, ofdA.terminalControl)
            assertSame(termB, ofdB.terminalControl)
        }

    @Test fun devNull_sourceAndSink_workViaStreamApi() =
        runTest {
            // exists()/source()/sink() must work without openHandle, since
            // some code paths (older redirections, ad-hoc readBytes) go
            // through the stream API.
            val fs = DevFs()
            val s = fs.source("/null")
            assertEquals(-1L, s.readAtMostTo(Buffer(), 16))
            val sink = fs.sink("/null", append = false, mode = 0b110_110_110)
            val buf = Buffer().apply { write("ignored".encodeToByteArray()) }
            sink.write(buf, buf.size) // must not throw
        }

    @Test fun devZero_returnsZeroBytesInBoundedReads() =
        runTest {
            val fs = DevFs()
            assertTrue(fs.exists("/zero"))
            val s = fs.source("/zero")
            val buf = Buffer()
            // Pull 16 bytes; assert exactly 16 zero bytes come back.
            val n = s.readAtMostTo(buf, 16L)
            assertEquals(16L, n)
            val bytes = buf.readByteArray()
            assertEquals(16, bytes.size)
            assertTrue(bytes.all { it == 0.toByte() }, "expected all zero bytes")
            // /dev/zero is infinite — second pull should also succeed.
            val n2 = s.readAtMostTo(buf, 8L)
            assertEquals(8L, n2)
        }

    @Test fun devZero_acceptsWritesSilently() =
        runTest {
            // Linux /dev/zero swallows writes like /dev/null. Mirror that
            // so `> /dev/zero` doesn't blow up.
            val fs = DevFs()
            val sink = fs.sink("/zero", append = false, mode = 0b110_110_110)
            val buf = Buffer().apply { write("ignored".encodeToByteArray()) }
            sink.write(buf, buf.size) // must not throw
        }

    @Test fun devZero_listedAtRootAndStatsAsChar() =
        runTest {
            val fs = DevFs()
            assertTrue("zero" in fs.list("/"), "zero should appear in /dev listing")
            val st = fs.stat("/zero")
            assertEquals(FileType.CHAR, st.type)
        }

    @Test fun devTty_streamApi_throwsFileNotFound_withoutOpener() =
        runTest {
            // The legacy stream API can't resolve per-process — calling
            // fs.source("/tty") with no process context surfaces ENXIO.
            val fs = DevFs()
            assertFailsWith<FileNotFound> { fs.source("/tty") }
            assertFailsWith<FileNotFound> { fs.sink("/tty", append = false, mode = 0) }
        }

    // ---- /dev/fd, /dev/stdin, /dev/stdout, /dev/stderr ----

    private fun ofdRead(content: String = ""): OpenFileDescription =
        OpenFileDescription(
            source = Buffer().apply { write(content.encodeToByteArray()) }.asSuspendSource(),
            accessMode = AccessMode.RDONLY,
            path = "/some/file",
            owning = false,
        )

    private fun ofdWrite(): OpenFileDescription =
        OpenFileDescription(
            sink = Buffer().asSuspendSink(),
            accessMode = AccessMode.WRONLY,
            path = "/sink",
            owning = false,
        )

    @Test fun devFd_nullOpener_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            assertFailsWith<FileNotFound> {
                fs.openHandle("/fd/0", AccessMode.RDONLY, opener = null)
            }
        }

    @Test fun devFd_unopenedSlot_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            // Slot 7 has no entry.
            assertFailsWith<FileNotFound> {
                fs.openHandle("/fd/7", AccessMode.RDONLY, opener = opener)
            }
        }

    @Test fun devFd_readableSlot_returnsFreshOfd_shareSource() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            val src = ofdRead("hello")
            opener.installFd(3, src)

            val ofd = fs.openHandle("/fd/3", AccessMode.RDONLY, opener = opener)
            assertNotNull(ofd)
            assertTrue(ofd !== src, "fresh OFD, not the slot's OFD")
            assertEquals("/dev/fd/3", ofd.path)
            assertSame(src.source, ofd.source)
            assertEquals(false, ofd.owning)
            assertNull(ofd.sink)
        }

    @Test fun devFd_writeOnReadOnlySlot_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            opener.installFd(3, ofdRead())
            assertFailsWith<FileNotFound> {
                fs.openHandle("/fd/3", AccessMode.WRONLY, opener = opener)
            }
        }

    @Test fun devFd_readOnWriteOnlySlot_throwsFileNotFound() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            opener.installFd(5, ofdWrite())
            assertFailsWith<FileNotFound> {
                fs.openHandle("/fd/5", AccessMode.RDONLY, opener = opener)
            }
        }

    @Test fun devStdin_stdout_stderr_routeToFds_0_1_2() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            val s0 = ofdRead("in")
            val s1 = ofdWrite()
            val s2 = ofdWrite()
            opener.installFd(0, s0)
            opener.installFd(1, s1)
            opener.installFd(2, s2)

            val stdin = fs.openHandle("/stdin", AccessMode.RDONLY, opener = opener)!!
            val stdout = fs.openHandle("/stdout", AccessMode.WRONLY, opener = opener)!!
            val stderr = fs.openHandle("/stderr", AccessMode.WRONLY, opener = opener)!!

            assertEquals("/dev/stdin", stdin.path)
            assertEquals("/dev/stdout", stdout.path)
            assertEquals("/dev/stderr", stderr.path)
            assertSame(s0.source, stdin.source)
            assertSame(s1.sink, stdout.sink)
            assertSame(s2.sink, stderr.sink)
        }

    @Test fun devFd_existsReflectsOpenerTable() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            opener.installFd(3, ofdRead())
            assertTrue(fs.exists("/fd/3", opener = opener))
            assertTrue(!fs.exists("/fd/4", opener = opener))
            // No opener → can't see the slot.
            assertTrue(!fs.exists("/fd/3", opener = null))
        }

    @Test fun devFd_listEnumeratesOpenerSlots() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            opener.installFd(0, ofdRead())
            opener.installFd(2, ofdWrite())
            opener.installFd(7, ofdRead())
            assertEquals(listOf("0", "2", "7"), fs.list("/fd", opener = opener))
            // Without an opener, listing yields empty (matches headless ProcFs semantics).
            assertEquals(emptyList(), fs.list("/fd", opener = null))
        }

    @Test fun devRoot_listIncludesFdStdioEntries() =
        runTest {
            val fs = DevFs()
            val entries = fs.list("/").toSet()
            assertTrue("fd" in entries)
            assertTrue("stdin" in entries)
            assertTrue("stdout" in entries)
            assertTrue("stderr" in entries)
        }

    @Test fun devFd_forkedChildSeesItsOwnTable() =
        runTest {
            // The /dev/fd resolution must consult the *opener's* fd table,
            // not the parent shell's — proves the per-process semantics
            // that bash users rely on inside subshells / redirected
            // children.
            val fs = DevFs()
            val parent = bootProcess()
            parent.installFd(3, ofdRead("parent"))

            val child = parent.fork()
            // Parent and child both see fd 3 after fork (refcount bumped).
            assertNotNull(fs.openHandle("/fd/3", AccessMode.RDONLY, opener = parent))
            assertNotNull(fs.openHandle("/fd/3", AccessMode.RDONLY, opener = child))

            // Child closes its slot 3 — parent's still works, child's
            // does not.
            child.fdTable
                .remove(3)
                ?.ofd
                ?.release()
            assertNotNull(fs.openHandle("/fd/3", AccessMode.RDONLY, opener = parent))
            assertFailsWith<FileNotFound> {
                fs.openHandle("/fd/3", AccessMode.RDONLY, opener = child)
            }
        }

    // ---- /dev/random, /dev/urandom ----

    @Test fun devRandom_yieldsRequestedBytes() =
        runTest {
            val fs = DevFs()
            val ofd = fs.openHandle("/random", AccessMode.RDONLY, opener = null)!!
            assertEquals("/dev/random", ofd.path)
            val buf = Buffer()
            val n = ofd.source!!.readAtMostTo(buf, 32)
            assertEquals(32L, n)
            assertEquals(32L, buf.size)
        }

    @Test fun devUrandom_yieldsRequestedBytes() =
        runTest {
            val fs = DevFs()
            val ofd = fs.openHandle("/urandom", AccessMode.RDONLY, opener = null)!!
            assertEquals("/dev/urandom", ofd.path)
            val buf = Buffer()
            val n = ofd.source!!.readAtMostTo(buf, 32)
            assertEquals(32L, n)
        }

    @Test fun devRandom_neverEofs() =
        runTest {
            val fs = DevFs()
            val src = fs.openHandle("/random", AccessMode.RDONLY, opener = null)!!.source!!
            // Two consecutive 4 KiB reads both return >0 — never EOF.
            assertTrue(src.readAtMostTo(Buffer(), 4096) > 0)
            assertTrue(src.readAtMostTo(Buffer(), 4096) > 0)
        }

    @Test fun devRandom_shortReadAboveMaxChunk() =
        runTest {
            // Matches getrandom(2)'s "short read above 4 KiB" semantics.
            val fs = DevFs()
            val src = fs.openHandle("/random", AccessMode.RDONLY, opener = null)!!.source!!
            val n = src.readAtMostTo(Buffer(), 1_000_000)
            assertEquals(4096L, n)
        }

    @Test fun devRandom_writeIsRejected() =
        runTest {
            val fs = DevFs()
            assertFailsWith<FileNotFound> {
                fs.openHandle("/random", AccessMode.WRONLY, opener = null)
            }
            assertFailsWith<FileNotFound> {
                fs.openHandle("/urandom", AccessMode.WRONLY, opener = null)
            }
        }

    @Test fun devRandom_streamApi_works() =
        runTest {
            // `cat /dev/urandom | head -c 16` style usage goes through
            // source() rather than openHandle().
            val fs = DevFs()
            val buf = Buffer()
            assertEquals(16L, fs.source("/random").readAtMostTo(buf, 16))
            assertEquals(16L, fs.source("/urandom").readAtMostTo(Buffer(), 16))
        }

    @Test fun devRoot_listIncludesRandomUrandom() =
        runTest {
            val fs = DevFs()
            val entries = fs.list("/").toSet()
            assertTrue("random" in entries)
            assertTrue("urandom" in entries)
        }

    // ---- source(path, opener) — the `cat /dev/fd/N` path ----

    @Test fun source_devFdN_dupsUnderlyingSource() =
        runTest {
            // The bug this regresses: `cat /dev/fd/10` (from
            // `cat <(echo hi)`) was hitting fs.source, but DevFs only
            // overrode openHandle. Now source(path, opener) delegates.
            val fs = DevFs()
            val opener = bootProcess()
            val orig =
                OpenFileDescription(
                    source = Buffer().apply { write("hi".encodeToByteArray()) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = "pipe:[10]",
                    owning = false,
                )
            opener.installFd(10, orig)

            val src = fs.source("/fd/10", opener = opener)
            val buf = Buffer()
            while (src.readAtMostTo(buf, 4096) != -1L) Unit
            assertEquals("hi", buf.readByteArray().decodeToString())
        }

    @Test fun source_devStdin_routesToFd0() =
        runTest {
            val fs = DevFs()
            val opener = bootProcess()
            val orig =
                OpenFileDescription(
                    source = Buffer().apply { write("one".encodeToByteArray()) }.asSuspendSource(),
                    accessMode = AccessMode.RDONLY,
                    path = null,
                    owning = false,
                )
            opener.installFd(0, orig)
            val buf = Buffer()
            fs.source("/stdin", opener = opener).readAtMostTo(buf, 16)
            assertEquals("one", buf.readByteArray().decodeToString())
        }

    @Test fun source_devFd_nullOpener_throws() =
        runTest {
            val fs = DevFs()
            assertFailsWith<FileNotFound> { fs.source("/fd/3", opener = null) }
            assertFailsWith<FileNotFound> { fs.source("/stdin", opener = null) }
        }
}
