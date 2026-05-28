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
