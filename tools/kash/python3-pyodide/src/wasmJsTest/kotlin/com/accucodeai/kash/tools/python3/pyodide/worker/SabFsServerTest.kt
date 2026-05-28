@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.tools.python3.pyodide.worker

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for [SabFsServer] running in headless Chrome. Each
 * test stands up a real server bound to an [InMemoryFs], then drives
 * the SAB protocol from the test body via [FakeFsWorker] — same wire
 * format the Pyodide worker uses, just no Pyodide.
 *
 * Verifies the bits that aren't reachable from JVM tests: the
 * Atomics-driven request/response cycle, the fd table around real
 * suspending FileSystem calls, and (most importantly) the
 * flush-on-stop contract that broke the agent's `python3 → cat` flow.
 *
 * The uppercase `val` aliases below intentionally mirror the on-the-wire
 * constant names so test bodies read like the protocol (OP_OPEN, ENOENT,
 * O_CREAT) — hence the property-naming suppression.
 */
@Suppress("ktlint:standard:property-naming")
class SabFsServerTest {
    // Op-code shorthand so tests read like the wire format.
    private val OP_OPEN = SabFsProtocol.Op.OPEN
    private val OP_READ = SabFsProtocol.Op.READ
    private val OP_WRITE = SabFsProtocol.Op.WRITE
    private val OP_CLOSE = SabFsProtocol.Op.CLOSE
    private val OP_STAT = SabFsProtocol.Op.STAT
    private val OP_LIST = SabFsProtocol.Op.LIST
    private val OP_MKDIR = SabFsProtocol.Op.MKDIR
    private val OP_UNLINK = SabFsProtocol.Op.UNLINK
    private val OP_RENAME = SabFsProtocol.Op.RENAME
    private val OK = SabFsProtocol.Status.OK
    private val ENOENT = SabFsProtocol.Status.ENOENT
    private val EISDIR = SabFsProtocol.Status.EISDIR

    // musl/Emscripten flag bits, sourced from the shared protocol object.
    private val O_WRONLY = SabFsProtocol.Open.O_WRONLY
    private val O_RDONLY = SabFsProtocol.Open.O_RDONLY
    private val O_CREAT = SabFsProtocol.Open.O_CREAT
    private val O_TRUNC = SabFsProtocol.Open.O_TRUNC
    private val O_APPEND = SabFsProtocol.Open.O_APPEND

    /** Spin up server + fake worker, run [body], tear down. */
    private fun withServer(body: suspend Harness.() -> Unit): TestResult =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/tmp")
            // Pass `this` (the TestScope) so the server's dispatcher
            // coroutine runs on the same dispatcher the test yields on.
            val server = SabFsServer(fs, this as CoroutineScope, dataCapacity = 64 * 1024)
            server.start()
            val worker =
                FakeFsWorker(
                    controlSab = server.controlSab,
                    dataSab = server.dataSab,
                    dataCapacity = server.dataCapacityBytes,
                    notify = { server.notify() },
                )
            try {
                Harness(fs, server, worker).body()
            } finally {
                server.stop()
            }
        }

    private class Harness(
        val fs: InMemoryFs,
        val server: SabFsServer,
        val worker: FakeFsWorker,
    )

    // ---- happy path: open + write + close round-trips through kash FS ----

    @Test fun writeThenCloseFlushesContentToKashFs() =
        withServer {
            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                    payload = "/tmp/hello.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status, "open failed: status=${open.status}")
            val fd = open.arg0

            val payload = "round-trip\n".encodeToByteArray()
            val w = worker.request(op = OP_WRITE, arg0 = fd, arg1 = 0, payload = payload)
            assertEquals(OK, w.status, "write failed: status=${w.status}")
            assertEquals(payload.size, w.arg0, "wrote count mismatch")

            val c = worker.request(op = OP_CLOSE, arg0 = fd)
            assertEquals(OK, c.status, "close failed: status=${c.status}")

            // After close, the file MUST be visible to kash readers.
            assertContentEquals(payload, fs.readBytes("/tmp/hello.txt"))
        }

    // ---- the reported bug: agent saw an empty file after `python3 -c
    // "open(p,'w').write('x')"` then `cat p`. Python's GC may not run
    // inside the runPythonAsync window, so the fd stays open with
    // dirty=true; the session-end flush is supposed to be the safety
    // net. `stop()` currently fires the flushes and returns without
    // waiting — the shell prompts the next command and the user reads
    // before bytes land. ----

    @Test fun stopWaitsForPendingWritesToFlush() =
        runTest {
            // Set up by hand instead of using [withServer] so the test
            // controls the stop() call.
            val fs = InMemoryFs()
            fs.mkdirs("/tmp")
            val server = SabFsServer(fs, this as CoroutineScope, dataCapacity = 64 * 1024)
            server.start()
            val worker =
                FakeFsWorker(
                    controlSab = server.controlSab,
                    dataSab = server.dataSab,
                    dataCapacity = server.dataCapacityBytes,
                    notify = { server.notify() },
                )

            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                    payload = "/tmp/forgot-close.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0

            val payload = "bytes that must not vanish".encodeToByteArray()
            val w = worker.request(op = OP_WRITE, arg0 = fd, arg1 = 0, payload = payload)
            assertEquals(OK, w.status)

            // Simulate Python forgetting to call close (GC didn't fire).
            // Now end the session — by the time stop() returns, the kash FS
            // must reflect the write. Anything else is the agent bug.
            server.stop()

            assertContentEquals(
                payload,
                fs.readBytes("/tmp/forgot-close.txt"),
                "stop() must flush dirty writes synchronously — Python may not have closed " +
                    "the fd (GC deferral inside runPythonAsync), but by session end the bytes " +
                    "have to be in kash FS or the next shell command reads an empty file.",
            )
        }

    // ---- error paths surface as WASI errnos (not POSIX — Pyodide's
    // musl uses WASI numbering; ENOENT is 44, EISDIR is 31). ----

    @Test fun openMissingFileWithoutCreateReturnsEnoent() =
        withServer {
            val r =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_RDONLY,
                    payload = "/tmp/nope.txt".encodeToByteArray(),
                )
            assertEquals(ENOENT, r.status, "expected ENOENT (-44), got ${r.status}")
        }

    @Test fun openDirectoryForWriteReturnsEisdir() =
        withServer {
            fs.mkdirs("/tmp/a-dir")
            val r =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY,
                    payload = "/tmp/a-dir".encodeToByteArray(),
                )
            assertEquals(EISDIR, r.status, "expected EISDIR (-31), got ${r.status}")
        }

    @Test fun openDirectoryForReadSucceedsLikeLinux() =
        withServer {
            // Linux: `open(dir, O_RDONLY)` returns a valid fd. Python's
            // zipimport + FileFinder probe every sys.path entry as a
            // file; if we EISDIR the open, the REPL crashes on
            // `import rlcompleter` because Pyodide's stack doesn't catch
            // IsADirectoryError on the path itself.
            fs.mkdirs("/tmp/probe-me")
            val r =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_RDONLY,
                    payload = "/tmp/probe-me".encodeToByteArray(),
                )
            assertEquals(OK, r.status, "O_RDONLY of a directory must succeed (Linux semantics)")
            // Read returns zero bytes — there's no file content, but
            // that's not an error from open's perspective.
            val read = worker.request(op = OP_READ, arg0 = r.arg0, arg1 = 0, arg3 = 4096)
            assertEquals(OK, read.status)
            assertEquals(0, read.payloadLen)
            assertEquals(OK, worker.request(OP_CLOSE, arg0 = r.arg0).status)
        }

    // ---- stat blob round-trip ----

    @Test fun statReturnsSizeModeAndType() =
        withServer {
            fs.writeBytes("/tmp/x", ByteArray(42) { 'a'.code.toByte() })
            val r = worker.request(op = OP_STAT, payload = "/tmp/x".encodeToByteArray())
            assertEquals(OK, r.status)
            assertEquals(SabFsProtocol.Stat.SIZE, r.payloadLen)

            // Decode the 32-byte stat blob the same way the JS plugin does.
            val size = readI64Le(r.payload, SabFsProtocol.Stat.OFF_SIZE)
            val type = readI32Le(r.payload, SabFsProtocol.Stat.OFF_TYPE)
            assertEquals(42, size, "stat.size")
            assertEquals(SabFsProtocol.Type.REGULAR, type, "stat.type for a regular file")
        }

    @Test fun statOnDirectoryReportsTypeDirectory() =
        withServer {
            val r = worker.request(op = OP_STAT, payload = "/tmp".encodeToByteArray())
            assertEquals(OK, r.status)
            val type = readI32Le(r.payload, SabFsProtocol.Stat.OFF_TYPE)
            assertEquals(SabFsProtocol.Type.DIRECTORY, type)
        }

    // ---- Python script: `f = open(p,'w'); f.write('hi'); os.stat(p)`.
    // Until the fd closes, kash FS has no file — the data sits in the
    // server-side GrowableBuf, and Python's `os.stat` mid-script used to
    // hit ENOENT before we taught opStat to look at the fd table. ----

    @Test fun statSeesBufferedSizeOfOpenWriteFdBeforeClose() =
        withServer {
            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                    payload = "/tmp/inflight.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0
            val payload = "buffered, not flushed yet".encodeToByteArray()
            assertEquals(OK, worker.request(OP_WRITE, arg0 = fd, arg1 = 0, payload = payload).status)

            // File is NOT yet in kash FS (no close, no flush).
            assertTrue(!fs.exists("/tmp/inflight.txt"))

            // But stat through the bridge must report it as a regular
            // file with the buffered size — that's what CPython's
            // semantics promise, and what Python scripts depend on.
            val st = worker.request(op = OP_STAT, payload = "/tmp/inflight.txt".encodeToByteArray())
            assertEquals(OK, st.status, "stat of open write fd should not be ENOENT")
            assertEquals(SabFsProtocol.Stat.SIZE, st.payloadLen)
            val size = readI64Le(st.payload, SabFsProtocol.Stat.OFF_SIZE)
            val type = readI32Le(st.payload, SabFsProtocol.Stat.OFF_TYPE)
            assertEquals(payload.size.toLong(), size, "stat.size should reflect buffered bytes")
            assertEquals(SabFsProtocol.Type.REGULAR, type)

            // Close + verify the bytes actually landed (sanity that the
            // synthesis path didn't disturb the real flush).
            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)
            assertContentEquals(payload, fs.readBytes("/tmp/inflight.txt"))
        }

    // ---- listdir uses NUL between entries (the only byte safe inside
    // POSIX filenames). Verify round-trip with names that contain
    // spaces, tabs, and non-ASCII ---

    @Test fun listdirEncodesEntriesNulSeparated() =
        withServer {
            fs.mkdirs("/tmp/d")
            fs.writeBytes("/tmp/d/plain", "a".encodeToByteArray())
            fs.writeBytes("/tmp/d/has space", "b".encodeToByteArray())
            fs.writeBytes("/tmp/d/普通话", "c".encodeToByteArray())

            val r = worker.request(op = OP_LIST, payload = "/tmp/d".encodeToByteArray())
            assertEquals(OK, r.status)
            val names =
                r.payload
                    .decodeToString()
                    .split("\u0000")
                    .toSet()
            assertEquals(setOf("plain", "has space", "普通话"), names)
        }

    // ---- multi-write into one fd, then read back, then close-flush ---

    @Test fun multipleWritesAccumulateAndFlushAsOneFile() =
        withServer {
            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                    payload = "/tmp/multi.bin".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0

            // Three chunks at positions 0, 5, 10.
            assertEquals(
                OK,
                worker.request(OP_WRITE, arg0 = fd, arg1 = 0, payload = "hello".encodeToByteArray()).status,
            )
            assertEquals(
                OK,
                worker.request(OP_WRITE, arg0 = fd, arg1 = 5, payload = " kash".encodeToByteArray()).status,
            )
            assertEquals(
                OK,
                worker.request(OP_WRITE, arg0 = fd, arg1 = 10, payload = " bridge".encodeToByteArray()).status,
            )

            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)
            assertContentEquals(
                "hello kash bridge".encodeToByteArray(),
                fs.readBytes("/tmp/multi.bin"),
            )
        }

    @Test fun unlinkRemovesTheFile() =
        withServer {
            fs.writeBytes("/tmp/doomed", "x".encodeToByteArray())
            val r = worker.request(op = OP_UNLINK, payload = "/tmp/doomed".encodeToByteArray())
            assertEquals(OK, r.status)
            assertTrue(!fs.exists("/tmp/doomed"))
        }

    @Test fun mkdirCreatesADirectory() =
        withServer {
            val r = worker.request(op = OP_MKDIR, arg0 = 0b111_101_101, payload = "/tmp/newdir".encodeToByteArray())
            assertEquals(OK, r.status)
            assertTrue(fs.isDirectory("/tmp/newdir"))
        }

    // ---- OP_READ: the read path was completely untested in the first
    // round — fix that. Each of these mirrors a real Python pattern. ----

    @Test fun readReturnsFullContentOfExistingFile() =
        withServer {
            val payload = "hello kash bridge".encodeToByteArray()
            fs.writeBytes("/tmp/readme.txt", payload)

            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_RDONLY,
                    payload = "/tmp/readme.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0

            // Python passes (position, length) per syscall; one full read.
            val r = worker.request(op = OP_READ, arg0 = fd, arg1 = 0, arg3 = payload.size)
            assertEquals(OK, r.status)
            assertContentEquals(payload, r.payload)

            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)
        }

    @Test fun readAtPositionReturnsSlice() =
        withServer {
            fs.writeBytes("/tmp/seek.txt", "0123456789".encodeToByteArray())
            val open = worker.request(OP_OPEN, arg0 = O_RDONLY, payload = "/tmp/seek.txt".encodeToByteArray())
            assertEquals(OK, open.status)
            val fd = open.arg0

            val mid = worker.request(OP_READ, arg0 = fd, arg1 = 3, arg3 = 4)
            assertEquals(OK, mid.status)
            assertContentEquals("3456".encodeToByteArray(), mid.payload)

            val tail = worker.request(OP_READ, arg0 = fd, arg1 = 8, arg3 = 100)
            assertEquals(OK, tail.status)
            assertContentEquals("89".encodeToByteArray(), tail.payload)

            // Past EOF — empty result, not an error.
            val past = worker.request(OP_READ, arg0 = fd, arg1 = 100, arg3 = 10)
            assertEquals(OK, past.status)
            assertEquals(0, past.payloadLen)

            worker.request(OP_CLOSE, arg0 = fd)
        }

    @Test fun readSurvivesNulBytesAndArbitraryBinary() =
        withServer {
            val bytes = ByteArray(256) { it.toByte() } // every byte 0..255
            fs.writeBytes("/tmp/binary.bin", bytes)

            val open = worker.request(OP_OPEN, arg0 = O_RDONLY, payload = "/tmp/binary.bin".encodeToByteArray())
            assertEquals(OK, open.status)
            val fd = open.arg0

            val r = worker.request(OP_READ, arg0 = fd, arg1 = 0, arg3 = bytes.size)
            assertEquals(OK, r.status)
            assertContentEquals(bytes, r.payload, "all 256 byte values must round-trip exactly")

            worker.request(OP_CLOSE, arg0 = fd)
        }

    @Test fun readInChunksReassemblesLargerThanSingleRound() =
        withServer {
            // 100 KiB — well within the test's data SAB (64 KiB? No — withServer
            // uses 64 KiB. Pick a payload that needs chunking.)
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            fs.writeBytes("/tmp/big.bin", bytes)

            val open = worker.request(OP_OPEN, arg0 = O_RDONLY, payload = "/tmp/big.bin".encodeToByteArray())
            assertEquals(OK, open.status)
            val fd = open.arg0

            // Read in 8 KiB chunks, the way Python's BufferedReader does.
            val chunkSize = 8 * 1024
            val out = ByteArray(bytes.size)
            var pos = 0
            while (pos < bytes.size) {
                val r = worker.request(OP_READ, arg0 = fd, arg1 = pos, arg3 = chunkSize)
                assertEquals(OK, r.status)
                if (r.payloadLen == 0) break
                r.payload.copyInto(out, pos)
                pos += r.payloadLen
            }
            assertEquals(bytes.size, pos)
            assertContentEquals(bytes, out)

            worker.request(OP_CLOSE, arg0 = fd)
        }

    // ---- fd-table edge cases ----

    @Test fun readWriteCloseOnBadFdReturnsEbadf() =
        withServer {
            val EBADF = SabFsProtocol.Status.EBADF
            assertEquals(EBADF, worker.request(OP_READ, arg0 = 99999, arg1 = 0, arg3 = 16).status)
            assertEquals(
                EBADF,
                worker.request(OP_WRITE, arg0 = 99999, arg1 = 0, payload = "x".encodeToByteArray()).status,
            )
            assertEquals(EBADF, worker.request(OP_CLOSE, arg0 = 99999).status)
        }

    @Test fun manyConcurrentFdsAreServedIndependently() =
        withServer {
            // Open 8 write fds, write a distinguishing payload to each,
            // close in reverse order. Verify each file's contents in kash.
            val fds = IntArray(8)
            for (i in fds.indices) {
                val o =
                    worker.request(
                        op = OP_OPEN,
                        arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                        payload = "/tmp/m$i.txt".encodeToByteArray(),
                    )
                assertEquals(OK, o.status, "open $i")
                fds[i] = o.arg0
            }
            for (i in fds.indices) {
                val w =
                    worker.request(
                        op = OP_WRITE,
                        arg0 = fds[i],
                        arg1 = 0,
                        payload = "payload $i".encodeToByteArray(),
                    )
                assertEquals(OK, w.status, "write $i")
            }
            // Close last → first so we cover the inner-iteration ordering.
            for (i in fds.indices.reversed()) {
                assertEquals(OK, worker.request(OP_CLOSE, arg0 = fds[i]).status, "close $i")
            }
            for (i in fds.indices) {
                assertContentEquals(
                    "payload $i".encodeToByteArray(),
                    fs.readBytes("/tmp/m$i.txt"),
                    "file $i content",
                )
            }
        }

    // ---- open modes: TRUNC and APPEND ----

    @Test fun openWithTruncDiscardsPreviousContent() =
        withServer {
            fs.writeBytes("/tmp/trunc.txt", "old content that should disappear".encodeToByteArray())

            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_TRUNC,
                    payload = "/tmp/trunc.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0
            assertEquals(OK, worker.request(OP_WRITE, arg0 = fd, arg1 = 0, payload = "new".encodeToByteArray()).status)
            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)

            assertContentEquals("new".encodeToByteArray(), fs.readBytes("/tmp/trunc.txt"))
        }

    @Test fun openWithAppendPreservesExistingContent() =
        withServer {
            fs.writeBytes("/tmp/app.txt", "one\n".encodeToByteArray())

            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_APPEND,
                    payload = "/tmp/app.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0
            // Python's BufferedWriter passes the append cursor as position;
            // we mirror that — write at position = existing length.
            assertEquals(
                OK,
                worker.request(OP_WRITE, arg0 = fd, arg1 = 4, payload = "two\n".encodeToByteArray()).status,
            )
            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)

            assertContentEquals("one\ntwo\n".encodeToByteArray(), fs.readBytes("/tmp/app.txt"))
        }

    @Test fun appendIgnoresCallerOffsetAndWritesAtEof() =
        withServer {
            // O_APPEND is server-authoritative: even if the worker passes a
            // bogus position (here 0, which would otherwise clobber the
            // existing content), the write must land at end-of-file. Pins the
            // POSIX semantics so we don't silently depend on Emscripten
            // seeking a custom FS plugin to EOF before each append write.
            fs.writeBytes("/tmp/app2.txt", "one\n".encodeToByteArray())
            val open =
                worker.request(
                    op = OP_OPEN,
                    arg0 = O_WRONLY or O_CREAT or O_APPEND,
                    payload = "/tmp/app2.txt".encodeToByteArray(),
                )
            assertEquals(OK, open.status)
            val fd = open.arg0
            assertEquals(
                OK,
                worker.request(OP_WRITE, arg0 = fd, arg1 = 0, payload = "two\n".encodeToByteArray()).status,
            )
            assertEquals(OK, worker.request(OP_CLOSE, arg0 = fd).status)
            assertContentEquals("one\ntwo\n".encodeToByteArray(), fs.readBytes("/tmp/app2.txt"))
        }

    // ---- rename ----

    @Test fun renameMovesFileAcrossDirs() =
        withServer {
            fs.mkdirs("/tmp/src")
            fs.mkdirs("/tmp/dst")
            fs.writeBytes("/tmp/src/payload", "moved".encodeToByteArray())

            // Wire format for OP_RENAME: ARG0 = from byte-length;
            // payload = from + NUL + to.
            val from = "/tmp/src/payload".encodeToByteArray()
            val to = "/tmp/dst/payload".encodeToByteArray()
            val buf = ByteArray(from.size + 1 + to.size)
            from.copyInto(buf, 0)
            buf[from.size] = 0
            to.copyInto(buf, from.size + 1)
            val r = worker.request(op = OP_RENAME, arg0 = from.size, payload = buf)
            assertEquals(OK, r.status)

            assertTrue(!fs.exists("/tmp/src/payload"))
            assertContentEquals("moved".encodeToByteArray(), fs.readBytes("/tmp/dst/payload"))
        }
}

// ---- little-endian decoders mirroring the JS plugin's readU53LeFromData /
// readI32LeFromData — duplicated here so the test asserts the exact wire
// the JS side parses, not just whatever the encoder happens to produce.

private fun readI32Le(
    blob: ByteArray,
    off: Int,
): Int =
    (blob[off].toInt() and 0xFF) or
        ((blob[off + 1].toInt() and 0xFF) shl 8) or
        ((blob[off + 2].toInt() and 0xFF) shl 16) or
        ((blob[off + 3].toInt() and 0xFF) shl 24)

private fun readI64Le(
    blob: ByteArray,
    off: Int,
): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (blob[off + i].toLong() and 0xFF)
    return v
}
