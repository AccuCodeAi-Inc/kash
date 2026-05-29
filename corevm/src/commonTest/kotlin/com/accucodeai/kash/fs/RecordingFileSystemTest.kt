package com.accucodeai.kash.fs

import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingFileSystemTest {
    // SuspendSink isn't AutoCloseable; write+flush then close.
    private suspend fun SuspendSink.putAndClose(s: String) {
        writeUtf8(s)
        close()
    }

    private fun mounted(): MountedFileSystem =
        MountedFileSystem(
            listOf(
                Mount("/", InMemoryFs(), FsLabel.USER),
                Mount("/cache", InMemoryFs(), FsLabel.ENGINE_CACHE),
                Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL),
                Mount("/host", InMemoryFs(), FsLabel.HOST_BORROW),
            ),
        )

    /** A process whose fs is the recording facade over [delegate]. */
    private fun bootProc(
        delegate: FileSystem,
        scopeId: Long? = null,
    ): Pair<KashMachine, KashProcess> {
        val machine = KashMachine(fs = delegate)
        machine.ensureInit()
        val proc =
            machine.processTable[1]!!.fork().apply {
                commandName = "test"
                traceScopeId = scopeId
            }
        return machine to proc
    }

    private suspend fun KashMachine.recordWhile(
        scope: kotlinx.coroutines.CoroutineScope,
        block: suspend () -> Unit,
    ): List<FileAccess> {
        val seen = mutableListOf<FileAccess>()
        val job: Job = scope.launch(UnconfinedTestDispatcher()) { fileAccess.events.collect { seen += it } }
        block()
        job.cancel()
        return seen
    }

    /** Run [block] with content capture on, returning the recorded touches. */
    private suspend fun KashMachine.captureWhile(block: suspend () -> Unit): List<FileAccess> =
        fileAccess.traced(captureContent = true) { block() }.touched

    private suspend fun FileSystem.readText(path: String): String = readBytes(path).decodeToString()

    @Test
    fun sourceRecordsRead() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            // seed a file via the raw fs (no recording) then read via facade
            machine.fs.sink("/f").putAndClose("hi")
            val events =
                machine.recordWhile(this) {
                    proc.fs.source("/f").close()
                }
            assertEquals(1, events.size)
            assertEquals(AccessKind.READ, events[0].kind)
            assertEquals("/f", events[0].path)
            assertEquals(FsLabel.USER, events[0].label)
            assertEquals(proc.pid, events[0].pid)
        }

    @Test
    fun sinkOnMissingPathIsCreateExistingIsWrite() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val created =
                machine.recordWhile(this) {
                    proc.fs.sink("/new").putAndClose("x")
                }
            assertEquals(listOf(AccessKind.CREATE), created.map { it.kind })

            val overwritten =
                machine.recordWhile(this) {
                    proc.fs.sink("/new").putAndClose("y")
                }
            assertEquals(listOf(AccessKind.WRITE), overwritten.map { it.kind })
        }

    @Test
    fun mutationKindsAreClassified() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.sink("/a").putAndClose("x")
            val events =
                machine.recordWhile(this) {
                    proc.fs.mkdirs("/d")
                    proc.fs.chmod("/a", 0b111_101_101)
                    proc.fs.createSymlink("/l", "/a")
                    proc.fs.remove("/a")
                }
            assertEquals(
                listOf(AccessKind.CREATE, AccessKind.META, AccessKind.SYMLINK, AccessKind.DELETE),
                events.map { it.kind },
            )
        }

    @Test
    fun probesAreNotRecorded() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.sink("/a").putAndClose("x")
            val events =
                machine.recordWhile(this) {
                    proc.fs.exists("/a")
                    proc.fs.isDirectory("/a")
                    proc.fs.stat("/a")
                    proc.fs.list("/")
                }
            assertTrue(events.isEmpty(), "stat/exists/list must not be recorded; got $events")
        }

    @Test
    fun engineCacheAndSystemBinAreIgnored() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val events =
                machine.recordWhile(this) {
                    proc.fs.sink("/cache/stdlib.bin").putAndClose("x")
                    proc.fs.source("/cache/stdlib.bin").close()
                }
            assertTrue(events.isEmpty(), "ENGINE_CACHE mount must produce no events; got $events")
        }

    @Test
    fun hostBorrowRecordsMutationsButNotReads() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val events =
                machine.recordWhile(this) {
                    proc.fs.sink("/host/out").putAndClose("x") // CREATE — recorded
                    proc.fs.source("/host/out").close() // READ — dropped
                }
            assertEquals(listOf(AccessKind.CREATE), events.map { it.kind })
            assertEquals(FsLabel.HOST_BORROW, events[0].label)
        }

    @Test
    fun scopeIdIsCarriedFromOpener() =
        runTest {
            val (machine, proc) = bootProc(mounted(), scopeId = 77L)
            val events =
                machine.recordWhile(this) {
                    proc.fs.sink("/f").putAndClose("x")
                }
            assertEquals(77L, events.single().scopeId)
        }

    // -------- content capture (FileAccess.before) --------

    @Test
    fun captureRecordsPriorContentOnWriteBytesOverwrite() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.writeBytes("/f", "old".encodeToByteArray())
            val events =
                machine.captureWhile {
                    proc.fs.writeBytes("/f", "new".encodeToByteArray())
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals("old", write.before?.decodeToString(), "WRITE must carry prior content")
            assertEquals("new", machine.fs.readText("/f"))
        }

    @Test
    fun captureLeavesBeforeNullOnCreate() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val events =
                machine.captureWhile {
                    proc.fs.writeBytes("/fresh", "hi".encodeToByteArray())
                }
            val create = events.single { it.kind == AccessKind.CREATE }
            assertEquals(null, create.before, "a CREATE has no prior content")
        }

    @Test
    fun captureOffLeavesBeforeNullEvenOnOverwrite() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.writeBytes("/f", "old".encodeToByteArray())
            // recordWhile attaches only a streaming collector — no capturing
            // observer — so the recording layer must NOT snapshot before.
            val events =
                machine.recordWhile(this) {
                    proc.fs.writeBytes("/f", "new".encodeToByteArray())
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals(null, write.before, "capture off → no before snapshot")
        }

    @Test
    fun captureRecordsPriorContentOnSinkTruncate() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.sink("/f").putAndClose("old")
            val events =
                machine.captureWhile {
                    proc.fs.sink("/f").putAndClose("new")
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals("old", write.before?.decodeToString(), "deferred sink must capture pre-truncate content")
            assertEquals("new", machine.fs.readText("/f"), "and the overwrite must still land")
        }

    @Test
    fun captureRecordsPriorContentOnSinkAppend() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.sink("/f").putAndClose("old")
            val events =
                machine.captureWhile {
                    proc.fs.sink("/f", append = true).putAndClose("more")
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals("old", write.before?.decodeToString())
            assertEquals("oldmore", machine.fs.readText("/f"))
        }

    @Test
    fun zeroWriteTruncateStillTruncatesButHasNoBefore() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.sink("/f").putAndClose("old")
            // Open + close with no write (`: > /f`). close() is non-suspend so
            // it can't read prior content, but it must still truncate.
            val events =
                machine.captureWhile {
                    proc.fs.sink("/f").close()
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals(null, write.before, "no-write close can't snapshot before")
            assertEquals("", machine.fs.readText("/f"), "but the file must still be truncated")
        }

    @Test
    fun captureIsByteExactForNonUtf8PriorContent() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val blob = byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x80.toByte())
            proc.fs.writeBytes("/f", blob)
            val events =
                machine.captureWhile {
                    proc.fs.writeBytes("/f", "text".encodeToByteArray())
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertTrue(blob.contentEquals(write.before), "before must be the raw prior bytes, verbatim")
        }

    @Test
    fun captureSkipsBeforeForFilesOverTheSizeCap() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            // Seed prior content just past the per-file cap.
            val big = ByteArray((FileAccessBus.MAX_CAPTURE_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() }
            proc.fs.writeBytes("/big", big)
            val events =
                machine.captureWhile {
                    proc.fs.writeBytes("/big", "small".encodeToByteArray())
                }
            val write = events.single { it.kind == AccessKind.WRITE }
            assertEquals(null, write.before, "an over-cap file must not snapshot its before-image")
            assertEquals("small", machine.fs.readText("/big"), "but the overwrite still lands")
        }

    @Test
    fun captureStopsAfterTheFileCountCap() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            val n = FileAccessBus.MAX_CAPTURE_FILES + 5
            for (i in 0 until n) proc.fs.writeBytes("/f$i", "old$i".encodeToByteArray())
            val events =
                machine.captureWhile {
                    for (i in 0 until n) proc.fs.writeBytes("/f$i", "new$i".encodeToByteArray())
                }
            val writes = events.filter { it.kind == AccessKind.WRITE }
            assertEquals(n, writes.size, "every overwrite is still recorded")
            val withBefore = writes.count { it.before != null }
            assertEquals(
                FileAccessBus.MAX_CAPTURE_FILES,
                withBefore,
                "only the first MAX_CAPTURE_FILES files snapshot a before-image",
            )
        }

    @Test
    fun captureBudgetResetsBetweenTracedSessions() =
        runTest {
            val (machine, proc) = bootProc(mounted())
            proc.fs.writeBytes("/a", "old".encodeToByteArray())
            // Exhaust the budget in one session…
            machine.captureWhile {
                repeat(FileAccessBus.MAX_CAPTURE_FILES) { i ->
                    proc.fs.writeBytes("/pad$i", "x".encodeToByteArray()) // CREATE — no slot taken
                    proc.fs.writeBytes("/pad$i", "y".encodeToByteArray()) // WRITE — takes a slot
                }
            }
            // …a fresh session must start with a full budget again.
            val events =
                machine.captureWhile {
                    proc.fs.writeBytes("/a", "new".encodeToByteArray())
                }
            val write = events.single { it.kind == AccessKind.WRITE && it.path == "/a" }
            assertEquals("old", write.before?.decodeToString(), "budget must reset per traced session")
        }

    @Test
    fun forkedChildInheritsScopeAndAttributesToItsOwnPid() =
        runTest {
            val (machine, parent) = bootProc(mounted(), scopeId = 9L)
            val child = parent.fork()
            assertEquals(9L, child.traceScopeId, "fork must copy the scope")
            val events =
                machine.recordWhile(this) {
                    child.fs.sink("/f").putAndClose("x")
                }
            assertEquals(9L, events.single().scopeId)
            assertEquals(child.pid, events.single().pid)
        }
}
