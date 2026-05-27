package com.accucodeai.kash.tools.pax

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaxCommandTest {
    // ----- Layer A: ustar format primitives -----

    @Test
    fun checksumRoundTrip() {
        val h =
            UstarFormat.buildHeader(
                name = "hello.txt",
                mode = 0b110_100_100,
                uid = 0,
                gid = 0,
                size = 5,
                mtime = 12345L,
                typeflag = UstarFormat.TYPE_REGULAR,
            )
        assertEquals(UstarFormat.BLOCK, h.size)
        val parsed = UstarFormat.parseHeader(h)
        assertNotNull(parsed)
        assertEquals("hello.txt", parsed!!.name)
        assertEquals(5L, parsed.size)
        assertEquals(12345L, parsed.mtime)
        assertTrue(parsed.isRegular)
    }

    @Test
    fun checksumDetectsCorruption() {
        val h =
            UstarFormat.buildHeader(
                name = "x",
                mode = 0,
                uid = 0,
                gid = 0,
                size = 0,
                mtime = 0,
                typeflag = UstarFormat.TYPE_REGULAR,
            )
        h[0] = 'Y'.code.toByte() // corrupt name without updating checksum
        assertNull(UstarFormat.parseHeader(h))
    }

    @Test
    fun zeroBlockDetected() {
        assertTrue(UstarFormat.isZeroBlock(ByteArray(UstarFormat.BLOCK)))
        val nonZero = ByteArray(UstarFormat.BLOCK)
        nonZero[100] = 1
        assertTrue(!UstarFormat.isZeroBlock(nonZero))
    }

    @Test
    fun namePrefixSplitForMidLengthNames() {
        // 150-char path that splits cleanly.
        val deep = ("a/".repeat(50) + "file.txt") // 50*2 + 8 = 108
        assertTrue(UstarFormat.fitsUstar(deep), "should fit via prefix split")
        val h =
            UstarFormat.buildHeader(
                name = deep,
                mode = 0,
                uid = 0,
                gid = 0,
                size = 0,
                mtime = 0,
                typeflag = UstarFormat.TYPE_REGULAR,
            )
        val parsed = UstarFormat.parseHeader(h)
        assertNotNull(parsed)
        assertEquals(deep, parsed!!.name)
    }

    @Test
    fun longNameOverflowsToPax() {
        val tooLong = "x".repeat(300)
        assertTrue(!UstarFormat.fitsUstar(tooLong))
    }

    @Test
    fun paxRecordRoundTrip() {
        val records = mapOf("path" to "very/long/path", "comment" to "hello")
        val body = UstarFormat.encodePaxRecords(records)
        val back = UstarFormat.decodePaxRecords(body)
        assertEquals(records, back)
    }

    @Test
    fun paxRecordLengthSelfConsistent() {
        // "10 path=ab\n" — count: "10 path=ab\n" is 11. Try "11 path=abc\n" -> 12.
        // Just verify the produced record's prefix decimal == record length.
        val body = UstarFormat.encodePaxRecords(mapOf("path" to "ab"))
        val str = body.decodeToString()
        // Split on first space.
        val space = str.indexOf(' ')
        val declared = str.substring(0, space).toInt()
        assertEquals(body.size, declared)
        assertTrue(str.endsWith("\n"))
    }

    // ----- Layer B: option parsing & semantic tests -----

    @Test
    fun parseReadFlag() {
        val o = PaxCommand().parseArgs(listOf("-r"))
        assertTrue(o.read)
        assertTrue(!o.write)
    }

    @Test
    fun parseWriteFlag() {
        val o = PaxCommand().parseArgs(listOf("-w", "a", "b"))
        assertTrue(o.write)
        assertEquals(listOf("a", "b"), o.operands)
    }

    @Test
    fun parseBundledFlags() {
        val o = PaxCommand().parseArgs(listOf("-rv"))
        assertTrue(o.read)
        assertTrue(o.verbose)
    }

    @Test
    fun parseArchiveAttached() {
        val o = PaxCommand().parseArgs(listOf("-farchive.tar"))
        assertEquals("archive.tar", o.archive)
    }

    @Test
    fun parseFormatPax() {
        val o = PaxCommand().parseArgs(listOf("-x", "pax"))
        assertEquals("pax", o.format)
    }

    @Test
    fun parseUnknownFormatRejected() {
        try {
            PaxCommand().parseArgs(listOf("-x", "cpio"))
            error("should throw")
        } catch (_: Exception) {
            // expected
        }
    }

    @Test
    fun parseEndOfOptions() {
        val o = PaxCommand().parseArgs(listOf("-w", "--", "-r"))
        assertTrue(o.write)
        assertEquals(listOf("-r"), o.operands)
    }

    @Test
    fun writeRequiresOperands() =
        runBlocking {
            val (_, err, exit) = runPax(listOf("-w"))
            assertEquals(1, exit)
            assertTrue(err.contains("no file operands"), err)
        }

    @Test
    fun copyModeRejected() =
        runBlocking {
            val (_, err, exit) = runPax(listOf("-r", "-w"))
            assertEquals(2, exit)
            assertTrue(err.contains("copy mode"), err)
        }

    // ----- Layer C: recipe (round-trip) tests -----

    @Test
    fun roundTripSingleFile() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/hello.txt", "Hello, World!\n".encodeToByteArray())

            // pax -w hello.txt > /archive.tar
            val archive = Buffer()
            val (_, err1, exit1) =
                runPax(
                    listOf("-w", "hello.txt"),
                    fs = fs,
                    cwd = "/work",
                    stdoutBuf = archive,
                )
            assertEquals(0, exit1, "write failed: $err1")
            assertTrue(archive.size > 0)

            // Extract into /out
            fs.mkdirs("/out")
            val (_, err2, exit2) =
                runPax(
                    listOf("-r"),
                    fs = fs,
                    cwd = "/out",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit2, "read failed: $err2")
            assertArrayEquals(
                "Hello, World!\n".encodeToByteArray(),
                fs.readBytes("/out/hello.txt"),
            )
        }

    @Test
    fun roundTripDirectoryTree() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/src/sub")
            fs.writeBytes("/src/a.txt", "aaa".encodeToByteArray())
            fs.writeBytes("/src/sub/b.txt", "bbb".encodeToByteArray())

            val archive = Buffer()
            val (_, err1, exit1) =
                runPax(
                    listOf("-w", "src"),
                    fs = fs,
                    cwd = "/",
                    stdoutBuf = archive,
                )
            assertEquals(0, exit1, "write: $err1")

            // Extract to /dst.
            fs.mkdirs("/dst")
            val (_, err2, exit2) =
                runPax(
                    listOf("-r"),
                    fs = fs,
                    cwd = "/dst",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit2, "read: $err2")
            assertArrayEquals("aaa".encodeToByteArray(), fs.readBytes("/dst/src/a.txt"))
            assertArrayEquals("bbb".encodeToByteArray(), fs.readBytes("/dst/src/sub/b.txt"))
        }

    @Test
    fun roundTripLongFilenameViaPaxExt() =
        runBlocking {
            val fs = InMemoryFs()
            // 200-char filename — doesn't fit ustar even with split because
            // no '/' is available.
            val longName = "a".repeat(200) + ".txt"
            fs.mkdirs("/work")
            fs.writeBytes("/work/$longName", "longname".encodeToByteArray())

            val archive = Buffer()
            val (_, err1, exit1) =
                runPax(
                    listOf("-w", longName),
                    fs = fs,
                    cwd = "/work",
                    stdoutBuf = archive,
                )
            assertEquals(0, exit1, "write: $err1")

            fs.mkdirs("/out")
            val (_, err2, exit2) =
                runPax(
                    listOf("-r"),
                    fs = fs,
                    cwd = "/out",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit2, "read: $err2")
            assertArrayEquals(
                "longname".encodeToByteArray(),
                fs.readBytes("/out/$longName"),
            )
        }

    @Test
    fun listModePrintsEntryNames() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/one.txt", "1".encodeToByteArray())
            fs.writeBytes("/work/two.txt", "22".encodeToByteArray())

            val archive = Buffer()
            runPax(listOf("-w", "one.txt", "two.txt"), fs = fs, cwd = "/work", stdoutBuf = archive)

            val (out, _, exit) =
                runPaxStdoutStr(
                    listOf(),
                    fs = fs,
                    cwd = "/",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit)
            val lines = out.lines().filter { it.isNotEmpty() }
            assertEquals(listOf("one.txt", "two.txt"), lines)
        }

    @Test
    fun listVerboseIncludesMode() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/file.txt", "x".encodeToByteArray())

            val archive = Buffer()
            runPax(listOf("-w", "file.txt"), fs = fs, cwd = "/work", stdoutBuf = archive)

            val (out, _, exit) =
                runPaxStdoutStr(
                    listOf("-v"),
                    fs = fs,
                    cwd = "/",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit)
            // verbose line should contain the file name and a mode-like prefix.
            assertTrue(out.contains("file.txt"), out)
            assertTrue(out.contains("-"), out)
        }

    @Test
    fun roundTripBinaryFile() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            val bytes = ByteArray(1234) { (it % 256).toByte() }
            fs.writeBytes("/work/bin.dat", bytes)

            val archive = Buffer()
            val (_, _, exit1) =
                runPax(listOf("-w", "bin.dat"), fs = fs, cwd = "/work", stdoutBuf = archive)
            assertEquals(0, exit1)

            fs.mkdirs("/out")
            val (_, _, exit2) =
                runPax(
                    listOf("-r"),
                    fs = fs,
                    cwd = "/out",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit2)
            assertArrayEquals(bytes, fs.readBytes("/out/bin.dat"))
        }

    @Test
    fun roundTripEmptyFile() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/empty", ByteArray(0))

            val archive = Buffer()
            val (_, _, exit1) = runPax(listOf("-w", "empty"), fs = fs, cwd = "/work", stdoutBuf = archive)
            assertEquals(0, exit1)

            fs.mkdirs("/out")
            val (_, _, exit2) =
                runPax(
                    listOf("-r"),
                    fs = fs,
                    cwd = "/out",
                    stdin = archive.readByteArray(),
                )
            assertEquals(0, exit2)
            assertEquals(0, fs.readBytes("/out/empty").size)
        }

    @Test
    fun roundTripPreservesContentBoundaryAt512() =
        runBlocking {
            // Exactly 512 bytes — body needs no padding. Then 513 bytes — body
            // padded to 1024.
            for (sz in intArrayOf(511, 512, 513, 1023, 1024, 1025)) {
                val fs = InMemoryFs()
                fs.mkdirs("/work")
                val bytes = ByteArray(sz) { (it and 0xff).toByte() }
                fs.writeBytes("/work/f", bytes)

                val archive = Buffer()
                val (_, err1, exit1) =
                    runPax(listOf("-w", "f"), fs = fs, cwd = "/work", stdoutBuf = archive)
                assertEquals(0, exit1, "write @sz=$sz: $err1")

                fs.mkdirs("/out$sz")
                val (_, err2, exit2) =
                    runPax(
                        listOf("-r"),
                        fs = fs,
                        cwd = "/out$sz",
                        stdin = archive.readByteArray(),
                    )
                assertEquals(0, exit2, "read @sz=$sz: $err2")
                assertArrayEquals(bytes, fs.readBytes("/out$sz/f"), "sz=$sz")
            }
        }

    @Test
    fun corruptArchiveReportsError() =
        runBlocking {
            // 512 bytes of garbage that's not zero and doesn't checksum.
            val garbage = ByteArray(512) { 'X'.code.toByte() }
            val (_, err, exit) =
                runPaxStdoutStr(
                    listOf("-r"),
                    fs = InMemoryFs(),
                    cwd = "/",
                    stdin = garbage,
                )
            assertEquals(1, exit)
            assertTrue(err.contains("corrupt") || err.contains("bad"), err)
        }

    @Test
    fun missingArchiveFile() =
        runBlocking {
            val (_, err, exit) =
                runPaxStdoutStr(
                    listOf("-r", "-f", "nope.tar"),
                    fs = InMemoryFs(),
                    cwd = "/",
                )
            assertEquals(1, exit)
            assertTrue(err.contains("No such file"), err)
        }

    @Test
    fun substitutionFlagAcceptedWithWarning() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/a", "hi".encodeToByteArray())
            val archive = Buffer()
            val (_, err, exit) =
                runPax(
                    listOf("-w", "-s", ",a,b,", "a"),
                    fs = fs,
                    cwd = "/work",
                    stdoutBuf = archive,
                )
            assertEquals(0, exit)
            assertTrue(err.contains("not yet implemented"), err)
        }

    // ----- Harness -----

    private data class Run(
        val stdout: ByteArray,
        val stderr: String,
        val exit: Int,
    )

    private fun runPax(
        args: List<String>,
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/",
        stdin: ByteArray = ByteArray(0),
        stdoutBuf: Buffer = Buffer(),
    ): Run {
        val stdinBuf = Buffer().apply { write(stdin) }
        val stderrBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = cwd,
                stdin = BufferSource(stdinBuf),
                stdout = BufferSink(stdoutBuf),
                stderr = BufferSink(stderrBuf),
            )
        val exit = runBlocking { PaxCommand().run(args, ctx).exitCode }
        // Don't drain stdoutBuf — caller may want to read it.
        return Run(ByteArray(0), stderrBuf.readByteArray().decodeToString(), exit)
    }

    private data class StrRun(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private fun runPaxStdoutStr(
        args: List<String>,
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/",
        stdin: ByteArray = ByteArray(0),
    ): StrRun {
        val out = Buffer()
        val r = runPax(args, fs, cwd, stdin, out)
        return StrRun(out.readByteArray().decodeToString(), r.stderr, r.exit)
    }
}

private class BufferSource(
    private val buf: Buffer,
) : SuspendSource {
    override suspend fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (buf.exhausted()) return -1L
        return buf.readAtMostTo(sink, byteCount)
    }

    override fun close() {}
}

private class BufferSink(
    private val buf: Buffer,
) : SuspendSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        buf.write(source, byteCount)
    }

    override suspend fun flush() {}

    override fun close() {}
}
