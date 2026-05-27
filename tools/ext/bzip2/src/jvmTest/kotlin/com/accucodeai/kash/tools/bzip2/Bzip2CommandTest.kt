package com.accucodeai.kash.tools.bzip2

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Bzip2CommandTest {
    // ----- self-contained reference encoder/decoder via commons-compress -----

    private fun refCompress(
        bytes: ByteArray,
        block: Int = 9,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        BZip2CompressorOutputStream(out, block).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun refDecompress(bytes: ByteArray): ByteArray =
        BZip2CompressorInputStream(ByteArrayInputStream(bytes), true).use {
            it.readBytes()
        }

    // ----- helpers -----

    private data class Run(
        val out: ByteArray,
        val err: String,
        val exit: Int,
    )

    private fun run(
        cmd: String,
        args: List<String>,
        stdin: ByteArray = ByteArray(0),
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/work",
    ): Run {
        val stdinBuf = Buffer().apply { write(stdin) }
        val stdinSrc = BufferSrc(stdinBuf)
        val outBuf = Buffer()
        val errBuf = Buffer()
        // Ensure cwd dir exists.
        fs.mkdirs(cwd)
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = cwd,
                stdin = stdinSrc,
                stdout = BufferSnk(outBuf),
                stderr = BufferSnk(errBuf),
            )
        val command =
            when (cmd) {
                "bzip2" -> Bzip2Command("bzip2", Bzip2Mode.COMPRESS)
                "bunzip2" -> Bzip2Command("bunzip2", Bzip2Mode.DECOMPRESS)
                "bzcat" -> Bzip2Command("bzcat", Bzip2Mode.DECOMPRESS_TO_STDOUT)
                else -> error("unknown cmd $cmd")
            }
        val exit = runBlocking { command.run(args, ctx).exitCode }
        return Run(
            outBuf.readByteArray(),
            errBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
        )
    }

    // ----- streaming tests (P0) -----

    @Test
    fun roundTripStdin() {
        val payload = "hello world\n".repeat(10).toByteArray()
        val encoded = run("bzip2", emptyList(), stdin = payload)
        assertEquals(0, encoded.exit, "compress stderr: ${encoded.err}")
        assertTrue(encoded.out.isNotEmpty())
        // Reference-decode it ourselves.
        assertArrayEquals(payload, refDecompress(encoded.out))
        // Now decompress through our command.
        val decoded = run("bunzip2", emptyList(), stdin = encoded.out)
        assertEquals(0, decoded.exit, "decompress stderr: ${decoded.err}")
        assertArrayEquals(payload, decoded.out)
    }

    @Test
    fun bzcatDefaultsToDecompress() {
        val payload = "abc".repeat(50).toByteArray()
        val encoded = refCompress(payload)
        val r = run("bzcat", emptyList(), stdin = encoded)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, r.out)
    }

    @Test
    fun bzip2DashDDecompresses() {
        val payload = "xyz".toByteArray()
        val encoded = refCompress(payload)
        val r = run("bzip2", listOf("-d"), stdin = encoded)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, r.out)
    }

    @Test
    fun bunzip2DashZCompresses() {
        val payload = "compress me".toByteArray()
        val r = run("bunzip2", listOf("-z"), stdin = payload)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, refDecompress(r.out))
    }

    @Test
    fun decompressKnownGoodBlob() {
        // Build a fixture by piping through the reference encoder, then feed
        // the bytes to our decompress path. Self-contained: no external file.
        val payload = "kash bzip2 fixture\n".toByteArray()
        val blob = refCompress(payload, block = 1)
        val r = run("bunzip2", emptyList(), stdin = blob)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, r.out)
    }

    @Test
    fun emptyInputCompressDecompressRoundTrip() {
        val encoded = run("bzip2", emptyList(), stdin = ByteArray(0))
        assertEquals(0, encoded.exit)
        // bzip2 still emits a header even for empty input.
        assertTrue(encoded.out.isNotEmpty())
        val decoded = run("bunzip2", emptyList(), stdin = encoded.out)
        assertEquals(0, decoded.exit)
        assertEquals(0, decoded.out.size)
    }

    @Test
    fun binaryRoundTripAcross256ByteValues() {
        val payload = ByteArray(1024) { (it % 256).toByte() }
        val encoded = run("bzip2", emptyList(), stdin = payload)
        assertEquals(0, encoded.exit)
        val decoded = run("bunzip2", emptyList(), stdin = encoded.out)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.out)
    }

    @Test
    fun blockSizeFlagAccepted() {
        val payload = "block size test".toByteArray()
        for (b in 1..9) {
            val r = run("bzip2", listOf("-$b"), stdin = payload)
            assertEquals(0, r.exit, "block -$b failed: ${r.err}")
            assertArrayEquals(payload, refDecompress(r.out))
        }
    }

    @Test
    fun bundledShortOptions() {
        // -dk would be invalid combined with stdin but here we just check
        // -ck on stdin path: -c implied by stdin anyway.
        val payload = "bundled".toByteArray()
        val r = run("bzip2", listOf("-ck"), stdin = payload)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, refDecompress(r.out))
    }

    @Test
    fun testFlagValidatesGoodInput() {
        val payload = "validate me".toByteArray()
        val encoded = refCompress(payload)
        val r = run("bzip2", listOf("-t"), stdin = encoded)
        assertEquals(0, r.exit)
        assertEquals(0, r.out.size, "test mode must not write output")
    }

    @Test
    fun testFlagRejectsCorruptInput() {
        val garbage = "this is not bzip2".toByteArray()
        val r = run("bzip2", listOf("-t"), stdin = garbage)
        assertNotEquals(0, r.exit)
        assertTrue(r.err.contains("bzip2"))
    }

    @Test
    fun decompressGarbageReportsError() {
        val r = run("bunzip2", emptyList(), stdin = "nope".toByteArray())
        assertNotEquals(0, r.exit)
        assertTrue(r.err.isNotEmpty())
    }

    @Test
    fun unknownLongOptionUsageError() {
        val r = run("bzip2", listOf("--bogus"), stdin = ByteArray(0))
        assertEquals(2, r.exit)
        assertTrue(r.err.contains("unrecognized") || r.err.contains("unknown"))
    }

    @Test
    fun unknownShortOptionUsageError() {
        val r = run("bzip2", listOf("-X"), stdin = ByteArray(0))
        assertEquals(2, r.exit)
    }

    @Test
    fun helpExitsZero() {
        val r = run("bzip2", listOf("--help"), stdin = ByteArray(0))
        assertEquals(0, r.exit)
        assertTrue(r.out.toString(Charsets.UTF_8).contains("Usage"))
    }

    @Test
    fun versionExitsZero() {
        val r = run("bzip2", listOf("--version"), stdin = ByteArray(0))
        assertEquals(0, r.exit)
    }

    @Test
    fun endOfOptions() {
        // `--` should let `-` be treated as stdin operand.
        val payload = "hi".toByteArray()
        val r = run("bzip2", listOf("--", "-"), stdin = payload)
        assertEquals(0, r.exit)
        assertArrayEquals(payload, refDecompress(r.out))
    }

    // ----- file mode tests -----

    @Test
    fun fileModeCompressWritesBz2AndRemovesOriginal() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking { fs.writeBytes("/work/data.txt", "abcdef\n".toByteArray()) }
        val r = run("bzip2", listOf("data.txt"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        assertFalse(fs.exists("/work/data.txt"), "original should be removed")
        assertTrue(fs.exists("/work/data.txt.bz2"))
        val compressed = runBlocking { fs.readBytes("/work/data.txt.bz2") }
        assertArrayEquals("abcdef\n".toByteArray(), refDecompress(compressed))
    }

    @Test
    fun fileModeKeepRetainsOriginal() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking { fs.writeBytes("/work/data.txt", "abc".toByteArray()) }
        val r = run("bzip2", listOf("-k", "data.txt"), fs = fs)
        assertEquals(0, r.exit)
        assertTrue(fs.exists("/work/data.txt"))
        assertTrue(fs.exists("/work/data.txt.bz2"))
    }

    @Test
    fun fileModeDecompressDropsBz2Suffix() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val payload = "decompressed\n".toByteArray()
        runBlocking { fs.writeBytes("/work/data.txt.bz2", refCompress(payload)) }
        val r = run("bunzip2", listOf("data.txt.bz2"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        assertTrue(fs.exists("/work/data.txt"))
        assertFalse(fs.exists("/work/data.txt.bz2"))
        assertArrayEquals(payload, runBlocking { fs.readBytes("/work/data.txt") })
    }

    @Test
    fun fileModeDecompressTbz2TranslatesToTar() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val payload = "tarball".toByteArray()
        runBlocking { fs.writeBytes("/work/archive.tbz2", refCompress(payload)) }
        val r = run("bunzip2", listOf("archive.tbz2"), fs = fs)
        assertEquals(0, r.exit)
        assertTrue(fs.exists("/work/archive.tar"))
    }

    @Test
    fun fileModeStdoutFlagKeepsInput() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val payload = "stdout flag".toByteArray()
        runBlocking { fs.writeBytes("/work/data.txt", payload) }
        val r = run("bzip2", listOf("-c", "data.txt"), fs = fs)
        assertEquals(0, r.exit)
        // Original retained because of -c.
        assertTrue(fs.exists("/work/data.txt"))
        assertFalse(fs.exists("/work/data.txt.bz2"))
        assertArrayEquals(payload, refDecompress(r.out))
    }

    @Test
    fun fileModeBzcatConcatenatesToStdout() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val p1 = "alpha\n".toByteArray()
        val p2 = "beta\n".toByteArray()
        runBlocking {
            fs.writeBytes("/work/a.bz2", refCompress(p1))
            fs.writeBytes("/work/b.bz2", refCompress(p2))
        }
        val r = run("bzcat", listOf("a.bz2", "b.bz2"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        assertArrayEquals(p1 + p2, r.out)
    }

    @Test
    fun fileModeRefuseExistingOutputWithoutForce() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking {
            fs.writeBytes("/work/data.txt", "x".toByteArray())
            fs.writeBytes("/work/data.txt.bz2", ByteArray(0))
        }
        val r = run("bzip2", listOf("data.txt"), fs = fs)
        assertNotEquals(0, r.exit)
        assertTrue(r.err.contains("already exists"), "stderr: ${r.err}")
        // Original still there.
        assertTrue(fs.exists("/work/data.txt"))
    }

    @Test
    fun fileModeForceOverwritesExistingOutput() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking {
            fs.writeBytes("/work/data.txt", "real".toByteArray())
            fs.writeBytes("/work/data.txt.bz2", "stale".toByteArray())
        }
        val r = run("bzip2", listOf("-f", "data.txt"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        val replaced = runBlocking { fs.readBytes("/work/data.txt.bz2") }
        assertArrayEquals("real".toByteArray(), refDecompress(replaced))
    }

    @Test
    fun fileModeRefuseCompressBz2Suffix() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking { fs.writeBytes("/work/data.txt.bz2", "x".toByteArray()) }
        val r = run("bzip2", listOf("data.txt.bz2"), fs = fs)
        assertNotEquals(0, r.exit)
        assertTrue(r.err.contains(".bz2") || r.err.contains("suffix"))
    }

    @Test
    fun fileModeMissingFileError() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val r = run("bzip2", listOf("missing.txt"), fs = fs)
        assertNotEquals(0, r.exit)
        assertTrue(r.err.contains("No such file"))
    }

    @Test
    fun fileModeDirectoryError() {
        val fs = InMemoryFs()
        fs.mkdirs("/work/somedir")
        val r = run("bzip2", listOf("somedir"), fs = fs)
        assertNotEquals(0, r.exit)
        assertTrue(r.err.contains("directory"))
    }

    @Test
    fun fileModeTestFlag() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val payload = "verify".toByteArray()
        runBlocking { fs.writeBytes("/work/d.bz2", refCompress(payload)) }
        val r = run("bzip2", listOf("-t", "d.bz2"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        // -t never writes output to stdout and never modifies files.
        assertEquals(0, r.out.size)
        assertTrue(fs.exists("/work/d.bz2"))
    }

    // ----- recipe-style tests (P0+P1 interactions) -----

    @Test
    fun recipePipelineCompressThenDecompress() {
        // `echo HELLO | bzip2 | bunzip2` round-trips
        val payload = "HELLO\n".toByteArray()
        val a = run("bzip2", emptyList(), stdin = payload)
        assertEquals(0, a.exit)
        val b = run("bunzip2", emptyList(), stdin = a.out)
        assertEquals(0, b.exit)
        assertArrayEquals(payload, b.out)
    }

    @Test
    fun recipeMultiFileCompressKeep() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking {
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            fs.writeBytes("/work/b.txt", "BBB".toByteArray())
        }
        val r = run("bzip2", listOf("-k", "a.txt", "b.txt"), fs = fs)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        assertTrue(fs.exists("/work/a.txt") && fs.exists("/work/a.txt.bz2"))
        assertTrue(fs.exists("/work/b.txt") && fs.exists("/work/b.txt.bz2"))
    }

    @Test
    fun recipeConcatenatedStreamsDecompress() {
        // bunzip2 must handle two bzip2 streams concatenated together.
        val p1 = "stream-one\n".toByteArray()
        val p2 = "stream-two\n".toByteArray()
        val concat = refCompress(p1) + refCompress(p2)
        val r = run("bunzip2", emptyList(), stdin = concat)
        assertEquals(0, r.exit, "stderr: ${r.err}")
        assertArrayEquals(p1 + p2, r.out)
    }

    @Test
    fun recipeBzcatEquivalentToBzip2Dc() {
        val payload = "equiv\n".toByteArray()
        val blob = refCompress(payload)
        val a = run("bzcat", emptyList(), stdin = blob)
        val b = run("bzip2", listOf("-dc"), stdin = blob)
        assertEquals(0, a.exit)
        assertEquals(0, b.exit)
        assertArrayEquals(a.out, b.out)
    }

    @Test
    fun recipeLargeBufferRoundTrip() {
        // 64 KiB of pseudo-random-ish data — exercises multi-chunk paths
        // through both the SuspendSource/Sink bridges and the codec.
        val payload = ByteArray(64 * 1024) { ((it * 31 + 7) % 256).toByte() }
        val encoded = run("bzip2", emptyList(), stdin = payload)
        assertEquals(0, encoded.exit)
        val decoded = run("bunzip2", emptyList(), stdin = encoded.out)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.out)
    }
}

private class BufferSrc(
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

private class BufferSnk(
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
