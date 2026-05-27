package com.accucodeai.kash.tools.gzip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class GzipCommandTest {
    // ---- core codec round-trip ----

    @Test
    fun roundTripSimple() =
        runBlocking {
            val input = "hello world\n"
            val (out, _, exit) = runCmdBytes("gzip", emptyList(), stdinBytes = input.toByteArray())
            assertEquals(0, exit)
            assertTrue(out.isNotEmpty())
            assertEquals(0x1f.toByte(), out[0])
            assertEquals(0x8b.toByte(), out[1])
            // Decompress via JVM ground truth.
            val back = GZIPInputStream(ByteArrayInputStream(out)).readBytes()
            assertEquals(input, String(back))
        }

    @Test
    fun pipeRoundTrip() =
        runBlocking {
            val input = "hi"
            val (compressed, _, e1) = runCmdBytes("gzip", emptyList(), stdinBytes = input.toByteArray())
            assertEquals(0, e1)
            val (back, _, e2) = runCmdBytes("gunzip", emptyList(), stdinBytes = compressed)
            assertEquals(0, e2)
            assertArrayEquals(input.toByteArray(), back)
        }

    @Test
    fun zcatEqualsGunzipC() =
        runBlocking {
            val input = "kash gzip test\n"
            val (compressed, _, _) = runCmdBytes("gzip", emptyList(), stdinBytes = input.toByteArray())
            val (out, _, exit) = runCmdBytes("zcat", emptyList(), stdinBytes = compressed)
            assertEquals(0, exit)
            assertArrayEquals(input.toByteArray(), out)
        }

    @Test
    fun decodeKnownGoodBlob() =
        runBlocking {
            // Create a known-good gzip blob via JVM, then decompress through gunzip.
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("from-jvm\n".toByteArray()) }
            val blob = bos.toByteArray()
            val (out, _, exit) = runCmdBytes("gunzip", emptyList(), stdinBytes = blob)
            assertEquals(0, exit)
            assertArrayEquals("from-jvm\n".toByteArray(), out)
        }

    @Test
    fun gzipDOptionDecompresses() =
        runBlocking {
            val input = "abc"
            val (compressed, _, _) = runCmdBytes("gzip", emptyList(), stdinBytes = input.toByteArray())
            val (out, _, exit) = runCmdBytes("gzip", listOf("-d"), stdinBytes = compressed)
            assertEquals(0, exit)
            assertArrayEquals(input.toByteArray(), out)
        }

    @Test
    fun emptyInputRoundTrips() =
        runBlocking {
            val (compressed, _, e1) = runCmdBytes("gzip", emptyList(), stdinBytes = ByteArray(0))
            assertEquals(0, e1)
            // Should still be a valid gzip frame.
            val back = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
            assertEquals(0, back.size)
            val (out, _, e2) = runCmdBytes("gunzip", emptyList(), stdinBytes = compressed)
            assertEquals(0, e2)
            assertEquals(0, out.size)
        }

    @Test
    fun largeRoundTrip() =
        runBlocking {
            // 64 KiB to ensure we cross our 8 KiB chunk boundary multiple times.
            val payload = ByteArray(64 * 1024) { (it * 37 % 251).toByte() }
            val (compressed, _, e1) = runCmdBytes("gzip", emptyList(), stdinBytes = payload)
            assertEquals(0, e1)
            // Random-ish data should still compress to something — and not equal input.
            assertNotEquals(payload.size, compressed.size)
            val (back, _, e2) = runCmdBytes("gunzip", emptyList(), stdinBytes = compressed)
            assertEquals(0, e2)
            assertArrayEquals(payload, back)
        }

    // ---- flags ----

    @Test
    fun testFlagOnGoodData() =
        runBlocking {
            val (compressed, _, _) = runCmdBytes("gzip", emptyList(), stdinBytes = "hello".toByteArray())
            val (_, _, exit) = runCmdBytes("gzip", listOf("-t"), stdinBytes = compressed)
            assertEquals(0, exit)
        }

    @Test
    fun testFlagOnBadDataFails() =
        runBlocking {
            val (_, err, exit) = runCmd("gzip", listOf("-t"), stdin = "not gzip at all")
            assertEquals(1, exit)
            assertTrue(err.isNotEmpty())
        }

    @Test
    fun decompressBadDataFails() =
        runBlocking {
            val (_, err, exit) = runCmd("gunzip", emptyList(), stdin = "not gzip")
            assertEquals(1, exit)
            assertTrue(err.isNotEmpty())
        }

    @Test
    fun levelFlagAccepted() =
        runBlocking {
            for (lvl in 1..9) {
                val (compressed, _, e1) =
                    runCmdBytes(
                        "gzip",
                        listOf("-$lvl"),
                        stdinBytes = "level test data".toByteArray(),
                    )
                assertEquals(0, e1, "level $lvl compress failed")
                val (back, _, e2) = runCmdBytes("gunzip", emptyList(), stdinBytes = compressed)
                assertEquals(0, e2)
                assertEquals("level test data", String(back))
            }
        }

    @Test
    fun fastBestAliases() =
        runBlocking {
            val (c1, _, e1) = runCmd("gzip", listOf("--fast"), stdin = "x")
            assertEquals(0, e1)
            val (c2, _, e2) = runCmd("gzip", listOf("--best"), stdin = "x")
            assertEquals(0, e2)
            assertTrue(c1.isNotEmpty())
            assertTrue(c2.isNotEmpty())
        }

    @Test
    fun unknownLongOption() =
        runBlocking {
            val (_, err, exit) = runCmd("gzip", listOf("--nope"), stdin = "x")
            assertEquals(2, exit)
            assertTrue(err.contains("unknown option"))
        }

    @Test
    fun unknownShortOption() =
        runBlocking {
            val (_, err, exit) = runCmd("gzip", listOf("-X"), stdin = "x")
            assertEquals(2, exit)
            assertTrue(err.contains("unknown option"))
        }

    @Test
    fun helpFlag() =
        runBlocking {
            val (out, _, exit) = runCmd("gzip", listOf("-h"), stdin = "")
            assertEquals(0, exit)
            assertTrue(out.contains("Usage"))
        }

    @Test
    fun versionFlag() =
        runBlocking {
            val (out, _, exit) = runCmd("gzip", listOf("--version"), stdin = "")
            assertEquals(0, exit)
            assertTrue(out.contains("gzip"))
        }

    @Test
    fun recursiveNotImplemented() =
        runBlocking {
            val (_, err, exit) = runCmd("gzip", listOf("-r"), stdin = "")
            assertEquals(2, exit)
            assertTrue(err.contains("not implemented"))
        }

    @Test
    fun clusterFlags() =
        runBlocking {
            // -ckn = compress to stdout, keep, no-name
            val input = "data"
            val fs = WriteableFakeFs(mutableMapOf("/work/file.txt" to input.toByteArray()))
            val (out, _, exit) = runCmdBytes("gzip", listOf("-ckn", "file.txt"), fs = fs)
            assertEquals(0, exit)
            // Original file still there
            assertTrue(fs.files.containsKey("/work/file.txt"))
            // No .gz file written
            assertTrue(!fs.files.containsKey("/work/file.txt.gz"))
            // out should decompress back to input
            val back = GZIPInputStream(ByteArrayInputStream(out)).readBytes()
            assertArrayEquals(input.toByteArray(), back)
        }

    // ---- file operands ----

    @Test
    fun fileCompressInPlaceRemovesOriginal() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.txt" to "hello".toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("foo.txt"), fs = fs)
            assertEquals(0, exit)
            assertTrue(!fs.files.containsKey("/work/foo.txt"), "original should be removed")
            assertTrue(fs.files.containsKey("/work/foo.txt.gz"), "output should exist; files=${fs.files.keys}")
            val gz = fs.files["/work/foo.txt.gz"]!!
            val back = GZIPInputStream(ByteArrayInputStream(gz)).readBytes()
            assertEquals("hello", String(back))
        }

    @Test
    fun fileCompressKeepOption() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.txt" to "hello".toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("-k", "foo.txt"), fs = fs)
            assertEquals(0, exit)
            assertTrue(fs.files.containsKey("/work/foo.txt"))
            assertTrue(fs.files.containsKey("/work/foo.txt.gz"))
        }

    @Test
    fun fileCompressToStdout() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.txt" to "hi".toByteArray()))
            val (out, _, exit) = runCmdBytes("gzip", listOf("-c", "foo.txt"), fs = fs)
            assertEquals(0, exit)
            // input preserved; no .gz file
            assertTrue(fs.files.containsKey("/work/foo.txt"))
            assertTrue(!fs.files.containsKey("/work/foo.txt.gz"))
            val back = GZIPInputStream(ByteArrayInputStream(out)).readBytes()
            assertEquals("hi", String(back))
        }

    @Test
    fun fileRefuseDoubleGz() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.gz" to "x".toByteArray()))
            val (_, err, exit) = runCmd("gzip", listOf("foo.gz"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("already has .gz suffix"), "stderr: $err")
        }

    @Test
    fun fileForceOverwritesDoubleGz() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.gz" to "x".toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("-f", "foo.gz"), fs = fs)
            assertEquals(0, exit)
            assertTrue(fs.files.containsKey("/work/foo.gz.gz"))
        }

    @Test
    fun fileRefuseExistingOutput() =
        runBlocking {
            val fs =
                WriteableFakeFs(
                    mutableMapOf(
                        "/work/foo.txt" to "x".toByteArray(),
                        "/work/foo.txt.gz" to "stale".toByteArray(),
                    ),
                )
            val (_, err, exit) = runCmd("gzip", listOf("foo.txt"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("already exists"), "stderr: $err")
        }

    @Test
    fun fileMissing() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf())
            val (_, err, exit) = runCmd("gzip", listOf("nope.txt"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("No such file"), "stderr: $err")
        }

    // ---- decompress file operations ----

    @Test
    fun gunzipFileInPlaceStripsGz() =
        runBlocking {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("payload\n".toByteArray()) }
            val fs = WriteableFakeFs(mutableMapOf("/work/data.gz" to bos.toByteArray()))
            val (_, _, exit) = runCmd("gunzip", listOf("data.gz"), fs = fs)
            assertEquals(0, exit)
            assertTrue(!fs.files.containsKey("/work/data.gz"))
            assertEquals("payload\n", String(fs.files["/work/data"]!!))
        }

    @Test
    fun gunzipTgzSuffix() =
        runBlocking {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("tarball".toByteArray()) }
            val fs = WriteableFakeFs(mutableMapOf("/work/archive.tgz" to bos.toByteArray()))
            val (_, _, exit) = runCmd("gunzip", listOf("archive.tgz"), fs = fs)
            assertEquals(0, exit)
            assertEquals("tarball", String(fs.files["/work/archive.tar"]!!))
        }

    @Test
    fun gunzipUnknownSuffix() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/data" to "x".toByteArray()))
            val (_, err, exit) = runCmd("gunzip", listOf("data"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("unknown suffix") || err.contains("not in gzip"), "stderr: $err")
        }

    @Test
    fun gunzipFileToStdoutKeepsOriginal() =
        runBlocking {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("kept".toByteArray()) }
            val fs = WriteableFakeFs(mutableMapOf("/work/x.gz" to bos.toByteArray()))
            val (out, _, exit) = runCmdBytes("gunzip", listOf("-c", "x.gz"), fs = fs)
            assertEquals(0, exit)
            assertEquals("kept", String(out))
            assertTrue(fs.files.containsKey("/work/x.gz"))
        }

    @Test
    fun zcatFileToStdout() =
        runBlocking {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("zcat-data".toByteArray()) }
            val fs = WriteableFakeFs(mutableMapOf("/work/z.gz" to bos.toByteArray()))
            val (out, _, exit) = runCmdBytes("zcat", listOf("z.gz"), fs = fs)
            assertEquals(0, exit)
            assertEquals("zcat-data", String(out))
            assertTrue(fs.files.containsKey("/work/z.gz"))
        }

    @Test
    fun testCommandOnFile() =
        runBlocking {
            val bos = java.io.ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write("verify".toByteArray()) }
            val fs = WriteableFakeFs(mutableMapOf("/work/good.gz" to bos.toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("-t", "good.gz"), fs = fs)
            assertEquals(0, exit)
        }

    @Test
    fun testCommandOnCorruptFile() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/bad.gz" to "not gzip".toByteArray()))
            val (_, err, exit) = runCmd("gzip", listOf("-t", "bad.gz"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("bad.gz"))
        }

    @Test
    fun dashOperandReadsStdin() =
        runBlocking {
            val (out, _, exit) = runCmdBytes("gzip", listOf("-"), stdinBytes = "stdin-data".toByteArray())
            assertEquals(0, exit)
            val back = GZIPInputStream(ByteArrayInputStream(out)).readBytes()
            assertEquals("stdin-data", String(back))
        }

    @Test
    fun multipleFilesCompress() =
        runBlocking {
            val fs =
                WriteableFakeFs(
                    mutableMapOf(
                        "/work/a.txt" to "AAA".toByteArray(),
                        "/work/b.txt" to "BBB".toByteArray(),
                    ),
                )
            val (_, _, exit) = runCmd("gzip", listOf("a.txt", "b.txt"), fs = fs)
            assertEquals(0, exit)
            assertTrue(fs.files.containsKey("/work/a.txt.gz"))
            assertTrue(fs.files.containsKey("/work/b.txt.gz"))
            assertTrue(!fs.files.containsKey("/work/a.txt"))
            assertTrue(!fs.files.containsKey("/work/b.txt"))
        }

    @Test
    fun multipleFilesOneMissingKeepsGoing() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/a.txt" to "A".toByteArray()))
            val (_, err, exit) = runCmd("gzip", listOf("a.txt", "missing.txt"), fs = fs)
            assertEquals(1, exit)
            assertTrue(fs.files.containsKey("/work/a.txt.gz"), "a should be compressed even if b is missing")
            assertTrue(err.contains("missing.txt"))
        }

    // ---- header / -n behavior ----

    @Test
    fun noNameOmitsFname() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.txt" to "hi".toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("-n", "foo.txt"), fs = fs)
            assertEquals(0, exit)
            val gz = fs.files["/work/foo.txt.gz"]!!
            // FLG byte is at offset 3.
            assertEquals(0, (gz[3].toInt() and 0x08), "FNAME flag should be clear with -n")
            // MTIME bytes 4..7 should all be zero.
            for (i in 4..7) {
                assertEquals(0.toByte(), gz[i], "MTIME byte $i should be zero")
            }
        }

    @Test
    fun nameWrittenByDefault() =
        runBlocking {
            val fs = WriteableFakeFs(mutableMapOf("/work/foo.txt" to "hi".toByteArray()))
            val (_, _, exit) = runCmd("gzip", listOf("foo.txt"), fs = fs)
            assertEquals(0, exit)
            val gz = fs.files["/work/foo.txt.gz"]!!
            assertEquals(0x08, (gz[3].toInt() and 0x08), "FNAME flag should be set without -n")
            // basename "foo.txt" should appear NUL-terminated after the 10-byte fixed header.
            val nameBytes = gz.copyOfRange(10, 10 + "foo.txt".length)
            assertArrayEquals("foo.txt".toByteArray(), nameBytes)
            assertEquals(0.toByte(), gz[10 + "foo.txt".length])
        }

    // ---- test harness ----

    private data class Run(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private data class RunBytes(
        val stdout: ByteArray,
        val stderr: String,
        val exit: Int,
    )

    private fun command(name: String): com.accucodeai.kash.api.Command =
        gzipCommands.first { it.name == name }.command!!

    private fun runCmd(
        name: String,
        args: List<String>,
        stdin: String = "",
        fs: FileSystem = WriteableFakeFs(mutableMapOf()),
    ): Run {
        val stdinSrc = BufferSource(Buffer().apply { writeString(stdin) })
        val outBuf = Buffer()
        val errBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = stdinSrc,
                stdout = BufferSink(outBuf),
                stderr = BufferSink(errBuf),
            )
        val exit = runBlocking { command(name).run(args, ctx).exitCode }
        return Run(
            outBuf.readByteArray().toString(Charsets.UTF_8),
            errBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
        )
    }

    private fun runCmdBytes(
        name: String,
        args: List<String>,
        stdinBytes: ByteArray = ByteArray(0),
        fs: FileSystem = WriteableFakeFs(mutableMapOf()),
    ): RunBytes {
        val src = Buffer().apply { write(stdinBytes) }
        val stdinSrc = BufferSource(src)
        val outBuf = Buffer()
        val errBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = stdinSrc,
                stdout = BufferSink(outBuf),
                stderr = BufferSink(errBuf),
            )
        val exit = runBlocking { command(name).run(args, ctx).exitCode }
        return RunBytes(
            outBuf.readByteArray(),
            errBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
        )
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

internal class WriteableFakeFs(
    val files: MutableMap<String, ByteArray>,
) : FileSystem {
    override fun exists(path: String): Boolean = files.containsKey(normalize(path))

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource {
        val bytes = files[normalize(path)] ?: throw FileNotFound(path)
        val b = Buffer()
        b.write(bytes)
        return BufferSource(b)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink {
        val key = normalize(path)
        val buf = Buffer()
        if (append && files.containsKey(key)) buf.write(files[key]!!)
        return object : SuspendSink {
            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                buf.write(source, byteCount)
            }

            override suspend fun flush() { /* in-memory */ }

            override fun close() {
                files[key] = buf.copy().readByteArray()
            }
        }
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        // no-op for tests
    }

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {
        files.remove(normalize(path))
    }

    private fun normalize(path: String): String {
        val parts = mutableListOf<String>()
        for (s in path.split('/')) {
            when (s) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += s
            }
        }
        return "/" + parts.joinToString("/")
    }
}
