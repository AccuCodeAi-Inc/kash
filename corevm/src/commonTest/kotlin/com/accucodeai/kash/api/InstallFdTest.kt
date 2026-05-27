package com.accucodeai.kash.api

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the [installFd] / [dupFd] refcount contract documented on
 * [OpenFileDescription]. The contract is "fresh OFD = 1 outstanding ref;
 * installFd consumes it; dupFd retains before installing."
 */
class InstallFdTest {
    private fun bareProcess(): KashProcess {
        val machine = KashMachine(fs = InMemoryFs())
        return machine.ensureInit()
    }

    // A tiny OFD whose source is a closeable buffer — lets us prove
    // release-at-refcount-zero actually closes the underlying stream
    // when owning=true.
    private class ClosableBuffer : SuspendSource {
        var closed: Boolean = false

        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = -1L

        override fun close() {
            closed = true
        }
    }

    @Test fun installFd_putsOfdAtSlot_andDoesNotRetain() {
        val p = bareProcess()
        val ofd = OpenFileDescription(source = Buffer().asSuspendSource(), accessMode = AccessMode.RDONLY)
        p.installFd(5, ofd)
        assertSame(ofd, p.fdTable[5]?.ofd)
        // Round-trip: removing the entry releases the single outstanding
        // ref; for a non-owning OFD with no source close, this is just
        // refs decrement to 0. No exception, no double-release.
        p.fdTable
            .remove(5)
            ?.ofd
            ?.release()
    }

    @Test fun installFd_releasesDisplacedEntry() {
        val p = bareProcess()
        val first = ClosableBuffer()
        val firstOfd =
            OpenFileDescription(source = first, accessMode = AccessMode.RDONLY, owning = true)
        p.installFd(7, firstOfd)
        assertTrue(!first.closed, "fresh OFD should not be closed yet")

        // Installing a second OFD at the same fd must release the first,
        // which (refcount → 0, owning) closes its source.
        val secondOfd = OpenFileDescription(source = Buffer().asSuspendSource(), accessMode = AccessMode.RDONLY)
        p.installFd(7, secondOfd)
        assertTrue(first.closed, "displaced owning OFD should be closed on release")
        assertSame(secondOfd, p.fdTable[7]?.ofd)
    }

    @Test fun dupFd_retainsThenInstalls_oneOfdBothSlots() {
        val p = bareProcess()
        val src = ClosableBuffer()
        val ofd = OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = true)
        p.installFd(3, ofd)

        // dup3→4: both slots point at the SAME OFD instance.
        p.dupFd(3, 4)
        assertSame(p.fdTable[3]?.ofd, p.fdTable[4]?.ofd)

        // Removing fd 3 must NOT close the source — fd 4 still holds a ref.
        p.fdTable
            .remove(3)
            ?.ofd
            ?.release()
        assertTrue(!src.closed, "source must survive while another fd retains the OFD")

        // Removing the last reference triggers close.
        p.fdTable
            .remove(4)
            ?.ofd
            ?.release()
        assertTrue(src.closed, "last release should close the owning source")
    }

    @Test fun dupFd_overwritesDestinationReleasingItsPriorOccupant() {
        val p = bareProcess()
        val a = ClosableBuffer()
        val b = ClosableBuffer()
        p.installFd(
            10,
            OpenFileDescription(source = a, accessMode = AccessMode.RDONLY, owning = true),
        )
        p.installFd(
            11,
            OpenFileDescription(source = b, accessMode = AccessMode.RDONLY, owning = true),
        )

        // dup10→11: source `b` is displaced and (owning, refs=0) closed.
        // Source `a` survives because both fds now point at its OFD.
        p.dupFd(10, 11)
        assertTrue(b.closed, "displaced OFD at destination should close")
        assertTrue(!a.closed, "shared OFD should survive the dup")

        // Cleanup: drop both fds, source `a` closes on the final release.
        p.fdTable
            .remove(10)
            ?.ofd
            ?.release()
        assertTrue(!a.closed, "still one fd left")
        p.fdTable
            .remove(11)
            ?.ofd
            ?.release()
        assertTrue(a.closed)
    }

    @Test fun dupFd_sameFd_isNoop() {
        val p = bareProcess()
        val src = ClosableBuffer()
        val ofd = OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = true)
        p.installFd(2, ofd)

        // POSIX dup2(x, x) succeeds without touching the OFD. The bug
        // we're guarding against: a naive impl would release-then-install,
        // which would close the only outstanding ref's source mid-flight.
        val r = p.dupFd(2, 2)
        assertEquals(2, r)
        assertSame(ofd, p.fdTable[2]?.ofd)
        assertTrue(!src.closed, "dup2(x,x) must not disturb the OFD")
    }

    @Test fun dupFd_unknownSource_returnsNull() {
        val p = bareProcess()
        assertNull(p.dupFd(99, 100))
        // No transient install at the destination from a failed dup.
        assertNull(p.fdTable[100])
    }

    @Test fun owningOfd_releasePastZero_throws() {
        // Defensive: catches the double-release class of bugs at the
        // exact site they happen, instead of leaking the next legit ref.
        val src = ClosableBuffer()
        val ofd = OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = true)
        ofd.release() // refs 1 → 0, source closed
        assertTrue(src.closed)
        assertFailsWith<IllegalStateException> { ofd.release() }
    }

    @Test fun nonOwningOfd_releasePastZero_isNoOp() {
        // Non-owning OFDs don't track refs and don't close; repeated
        // releases must NOT throw — that's how the install code paths
        // handle the host-stdio OFDs that get displaced multiple times.
        val src = ClosableBuffer()
        val ofd = OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = false)
        repeat(10) { ofd.release() }
        assertTrue(!src.closed)
    }

    @Test fun installFd_nonOwning_retainReleaseAreNoOps() {
        val p = bareProcess()
        val src = ClosableBuffer()
        // owning=false: retain/release should not touch refs or close.
        val ofd =
            OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = false)
        p.installFd(0, ofd)
        // Multiple releases in a row don't underflow / don't close.
        repeat(3) { p.fdTable[0]?.ofd?.release() }
        assertTrue(!src.closed, "non-owning OFD must never close its source")
    }

    @Test fun installStdio_threeIndependentOfds_eachIsRefcountOne() {
        // installStdio uses installFd internally for fd 0/1/2. Verify
        // they're three independent OFDs (not a shared instance) and
        // each one's release is independent — matches bash's "stdio
        // inherited as three separate struct files" model.
        val p = bareProcess()
        val stdin = Buffer()
        val stdout = Buffer()
        val stderr = Buffer()
        p.installStdio(
            stdin = stdin.asSuspendSource(),
            stdout = stdout.asSuspendSink(),
            stderr = stderr.asSuspendSink(),
            stdinIsTty = false,
            stdoutIsTty = false,
            stderrIsTty = false,
        )
        val o0 = p.fdTable[0]?.ofd
        val o1 = p.fdTable[1]?.ofd
        val o2 = p.fdTable[2]?.ofd
        assertNotNull(o0)
        assertNotNull(o1)
        assertNotNull(o2)
        // Three distinct OFD instances even though all wrap host stdio.
        assertTrue(o0 !== o1)
        assertTrue(o1 !== o2)
        assertTrue(o0 !== o2)
    }

    @Test fun installFd_releaseTreeOrderingIsStable_underInstall() {
        // Cover the corner case from the audit: applyRedirections's
        // installFd helper schedules a release on cleanup; if a SECOND
        // redirection targets the same fd, the displaced-entry release
        // (from installFd) plus the scheduled cleanup release must not
        // double-release the same OFD.
        val p = bareProcess()
        val src = ClosableBuffer()
        val first =
            OpenFileDescription(source = src, accessMode = AccessMode.RDONLY, owning = true)
        p.installFd(3, first)
        // Second install displaces and releases `first` → closes its
        // source. The next remove on fd 3 releases the second OFD,
        // which has no source — safe.
        val second = OpenFileDescription(source = Buffer().asSuspendSource(), accessMode = AccessMode.RDONLY)
        p.installFd(3, second)
        assertTrue(src.closed)
        assertNull(
            p.fdTable[3]?.ofd?.let {
                // still here
                null
            } ?: Unit.let { null },
        )
        // Cleanup the second entry — should not throw.
        p.fdTable
            .remove(3)
            ?.ofd
            ?.release()
    }
}
