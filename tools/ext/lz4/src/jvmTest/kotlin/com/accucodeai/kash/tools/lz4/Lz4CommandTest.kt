package com.accucodeai.kash.tools.lz4

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class Lz4CommandTest {
    private fun refCompress(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        FramedLZ4CompressorOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun refDecompress(bytes: ByteArray): ByteArray =
        FramedLZ4CompressorInputStream(ByteArrayInputStream(bytes), true).use { it.readBytes() }

    private data class Run(
        val out: ByteArray,
        val err: String,
        val exit: Int,
    )

    private suspend fun run(
        cmd: String,
        args: List<String>,
        stdin: ByteArray = ByteArray(0),
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/work",
    ): Run {
        val stdinBuf = Buffer().apply { write(stdin) }
        val outBuf = Buffer()
        val errBuf = Buffer()
        fs.mkdirs(cwd)
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = cwd,
                stdin = BufferSrc(stdinBuf),
                stdout = BufferSnk(outBuf),
                stderr = BufferSnk(errBuf),
            )
        val command =
            when (cmd) {
                "lz4" -> Lz4Command("lz4", Lz4Mode.COMPRESS)
                "unlz4" -> Lz4Command("unlz4", Lz4Mode.DECOMPRESS)
                "lz4cat" -> Lz4Command("lz4cat", Lz4Mode.DECOMPRESS_TO_STDOUT)
                else -> error("unknown cmd $cmd")
            }
        val exit = command.run(args, ctx).exitCode
        return Run(
            outBuf.readByteArray(),
            errBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
        )
    }

    // ----- streaming -----

    @Test
    fun roundTripStdin() =
        runTest {
            val payload = "hello world\n".repeat(20).toByteArray()
            val encoded = run("lz4", emptyList(), stdin = payload)
            assertEquals(0, encoded.exit, encoded.err)
            assertTrue(encoded.out.isNotEmpty())
            assertArrayEquals(payload, refDecompress(encoded.out))
            val decoded = run("unlz4", emptyList(), stdin = encoded.out)
            assertEquals(0, decoded.exit, decoded.err)
            assertArrayEquals(payload, decoded.out)
        }

    @Test
    fun lz4catDefaultsToDecompress() =
        runTest {
            val payload = "abc".repeat(50).toByteArray()
            val r = run("lz4cat", emptyList(), stdin = refCompress(payload))
            assertEquals(0, r.exit)
            assertArrayEquals(payload, r.out)
        }

    @Test
    fun lz4DashDDecompresses() =
        runTest {
            val payload = "xyz".toByteArray()
            val r = run("lz4", listOf("-d"), stdin = refCompress(payload))
            assertEquals(0, r.exit)
            assertArrayEquals(payload, r.out)
        }

    @Test
    fun unlz4DashZCompresses() =
        runTest {
            val payload = "compress me".toByteArray()
            val r = run("unlz4", listOf("-z"), stdin = payload)
            assertEquals(0, r.exit)
            assertArrayEquals(payload, refDecompress(r.out))
        }

    @Test
    fun decodeKnownGoodBlob() =
        runTest {
            val payload = "kash lz4 fixture\n".toByteArray()
            val blob = refCompress(payload)
            val r = run("unlz4", emptyList(), stdin = blob)
            assertEquals(0, r.exit)
            assertArrayEquals(payload, r.out)
        }

    @Test
    fun emptyInputRoundTrip() =
        runTest {
            val encoded = run("lz4", emptyList(), stdin = ByteArray(0))
            assertEquals(0, encoded.exit)
            assertTrue(encoded.out.isNotEmpty())
            val decoded = run("unlz4", emptyList(), stdin = encoded.out)
            assertEquals(0, decoded.exit)
            assertEquals(0, decoded.out.size)
        }

    @Test
    fun testFlagValidates() =
        runTest {
            val payload = "test me".toByteArray()
            val good = refCompress(payload)
            val r = run("lz4", listOf("-t"), stdin = good)
            assertEquals(0, r.exit)
            assertEquals(0, r.out.size)
        }

    @Test
    fun testFlagRejectsCorrupt() =
        runTest {
            val bogus = ByteArray(50) { 0x42 }
            val r = run("lz4", listOf("-t"), stdin = bogus)
            assertEquals(2, r.exit)
        }

    @Test
    fun unknownLongOption() =
        runTest {
            val r = run("lz4", listOf("--no-such-option"), stdin = ByteArray(0))
            assertEquals(2, r.exit)
            assertTrue(r.err.contains("unrecognized"))
        }

    @Test
    fun levelFlagsAreAccepted() =
        runTest {
            for (lvl in listOf("-1", "-9", "-12")) {
                val r = run("lz4", listOf(lvl), stdin = "x".toByteArray())
                assertEquals(0, r.exit, "$lvl: ${r.err}")
            }
        }

    @Test
    fun clusteredShortOptions() =
        runTest {
            // -dc: decompress to stdout (since `lz4cat` would default-decompress anyway).
            val payload = "cluster".toByteArray()
            val r = run("lz4", listOf("-dc"), stdin = refCompress(payload))
            assertEquals(0, r.exit, r.err)
            assertArrayEquals(payload, r.out)
        }

    @Test
    fun help() =
        runTest {
            val r = run("lz4", listOf("--help"), stdin = ByteArray(0))
            assertEquals(0, r.exit)
            assertTrue(r.out.toString(Charsets.UTF_8).contains("Usage"))
        }

    @Test
    fun version() =
        runTest {
            val r = run("lz4", listOf("--version"), stdin = ByteArray(0))
            assertEquals(0, r.exit)
            assertTrue(r.out.toString(Charsets.UTF_8).contains("kash lz4"))
        }

    // ----- file ops -----

    @Test
    fun fileCompressInPlaceCreatesLz4AndRemovesOriginal() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/data.txt", "payload\n".toByteArray())
            val r = run("lz4", listOf("data.txt"), fs = fs)
            assertEquals(0, r.exit, r.err)
            assertTrue(fs.exists("/work/data.txt.lz4"))
            assertFalse(fs.exists("/work/data.txt"))
            // Round-trip through ref decoder.
            assertArrayEquals("payload\n".toByteArray(), refDecompress(fs.readBytes("/work/data.txt.lz4")))
        }

    @Test
    fun fileCompressKeep() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/data.txt", "P".toByteArray())
            val r = run("lz4", listOf("-k", "data.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue(fs.exists("/work/data.txt"))
            assertTrue(fs.exists("/work/data.txt.lz4"))
        }

    @Test
    fun fileDecompressStripsSuffix() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/foo.lz4", refCompress("FOO".toByteArray()))
            val r = run("unlz4", listOf("foo.lz4"), fs = fs)
            assertEquals(0, r.exit, r.err)
            assertTrue(fs.exists("/work/foo"))
            assertFalse(fs.exists("/work/foo.lz4"))
            assertArrayEquals("FOO".toByteArray(), fs.readBytes("/work/foo"))
        }

    @Test
    fun fileRefuseDoubleLz4Suffix() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/already.lz4", "X".toByteArray())
            val r = run("lz4", listOf("already.lz4"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains(".lz4 suffix"))
        }

    @Test
    fun fileForceOverwritesExistingOutput() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/x", "X".toByteArray())
            fs.writeBytes("/work/x.lz4", "stale".toByteArray())
            // Default refuses.
            val refused = run("lz4", listOf("x"), fs = fs)
            assertEquals(1, refused.exit)
            // -f overwrites.
            val ok = run("lz4", listOf("-f", "x"), fs = fs)
            assertEquals(0, ok.exit, ok.err)
            assertArrayEquals("X".toByteArray(), refDecompress(fs.readBytes("/work/x.lz4")))
        }

    @Test
    fun fileMissing() =
        runTest {
            val fs = InMemoryFs()
            val r = run("lz4", listOf("nope"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.err.contains("No such file"))
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
