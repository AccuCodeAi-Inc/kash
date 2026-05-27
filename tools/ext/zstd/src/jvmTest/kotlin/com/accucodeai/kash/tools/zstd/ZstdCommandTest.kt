package com.accucodeai.kash.tools.zstd

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import io.airlift.compress.zstd.ZstdOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class ZstdCommandTest {
    // --- Unit-ish: codec ---

    @Test
    fun streamRoundTripIdentity() =
        runBlocking {
            val original = "hello, zstd world!\nline two\n".toByteArray()
            val compressed = compressBytes(original)
            val decompressed = decompressBytes(compressed)
            assertArrayEquals(original, decompressed)
        }

    @Test
    fun decodeKnownGoodBlobMatchesPlaintext() =
        runBlocking {
            val plaintext = ByteArray(2048) { ((it * 31) and 0xff).toByte() }
            val blob = referenceCompress(plaintext) // built via aircompressor directly
            val out = decompressBytes(blob)
            assertArrayEquals(plaintext, out)
        }

    // --- Semantic: CLI flag parsing ---

    @Test
    fun zstdCompressStdinToStdout() =
        runBlocking {
            val input = "kash zstd test payload\n".toByteArray()
            val r = runCmd("zstd", emptyList(), stdinBytes = input)
            assertEquals(0, r.exit)
            assertTrue(r.stdout.isNotEmpty())
            assertNotEquals(input.toList(), r.stdout.toList()) // it's compressed
            // round-trip via unzstd
            val r2 = runCmd("unzstd", emptyList(), stdinBytes = r.stdout)
            assertEquals(0, r2.exit)
            assertArrayEquals(input, r2.stdout)
        }

    @Test
    fun unzstdDefaultsToDecompress() =
        runBlocking {
            val original = "payload\n".toByteArray()
            val compressed = referenceCompress(original)
            val r = runCmd("unzstd", emptyList(), stdinBytes = compressed)
            assertEquals(0, r.exit)
            assertArrayEquals(original, r.stdout)
        }

    @Test
    fun zstdcatDefaultsToStdoutDecompress() =
        runBlocking {
            val original = "cat me\n".toByteArray()
            val compressed = referenceCompress(original)
            val r = runCmd("zstdcat", emptyList(), stdinBytes = compressed)
            assertEquals(0, r.exit)
            assertArrayEquals(original, r.stdout)
        }

    @Test
    fun levelFlagAcceptedAndIgnored() =
        runBlocking {
            val input = "a".repeat(2000).toByteArray()
            val r = runCmd("zstd", listOf("-19"), stdinBytes = input)
            assertEquals(0, r.exit)
            // The compressed payload should round-trip even though level is ignored.
            val r2 = runCmd("unzstd", emptyList(), stdinBytes = r.stdout)
            assertEquals(0, r2.exit)
            assertArrayEquals(input, r2.stdout)
        }

    @Test
    fun invalidLevelRejected() =
        runBlocking {
            val r = runCmd("zstd", listOf("-99"), stdinBytes = ByteArray(0))
            assertEquals(2, r.exit)
            assertTrue(String(r.stderr).contains("invalid level"), "stderr=${String(r.stderr)}")
        }

    @Test
    fun unknownLongOptionUsageError() =
        runBlocking {
            val r = runCmd("zstd", listOf("--bogus"), stdinBytes = ByteArray(0))
            assertEquals(2, r.exit)
            assertTrue(String(r.stderr).contains("unknown option"))
        }

    @Test
    fun ultraIsRejectedNotSilentlyDowngraded() =
        runBlocking {
            val r = runCmd("zstd", listOf("--ultra"), stdinBytes = ByteArray(0))
            assertEquals(2, r.exit)
            assertTrue(String(r.stderr).contains("--ultra not supported"))
        }

    @Test
    fun emptyInputCompressesAndRoundTrips() =
        runBlocking {
            val r = runCmd("zstd", emptyList(), stdinBytes = ByteArray(0))
            assertEquals(0, r.exit)
            val r2 = runCmd("unzstd", emptyList(), stdinBytes = r.stdout)
            assertEquals(0, r2.exit)
            assertEquals(0, r2.stdout.size)
        }

    @Test
    fun dashFileIsStdin() =
        runBlocking {
            val input = "via-dash\n".toByteArray()
            val r = runCmd("zstd", listOf("-"), stdinBytes = input)
            assertEquals(0, r.exit)
            val r2 = runCmd("unzstd", listOf("-"), stdinBytes = r.stdout)
            assertEquals(0, r2.exit)
            assertArrayEquals(input, r2.stdout)
        }

    @Test
    fun clusteredShortFlagsDc() =
        runBlocking {
            val original = "cluster-test\n".toByteArray()
            val compressed = referenceCompress(original)
            val r = runCmd("zstd", listOf("-dc"), stdinBytes = compressed)
            assertEquals(0, r.exit)
            assertArrayEquals(original, r.stdout)
        }

    // --- Recipe: file I/O ---

    @Test
    fun recipeCompressFileWritesZst() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.txt", "hello file\n".toByteArray())
            val r = runCmd("zstd", listOf("data.txt"), fs = fs, cwd = "/work")
            assertEquals(0, r.exit)
            assertTrue(fs.exists("/work/data.txt.zst"))
            // decompress the written file
            val compressed = fs.readBytes("/work/data.txt.zst")
            val plain = decompressBytes(compressed)
            assertArrayEquals("hello file\n".toByteArray(), plain)
        }

    @Test
    fun recipeRefuseOverwriteWithoutForce() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.txt", "x".toByteArray())
            fs.writeBytes("/work/data.txt.zst", "existing".toByteArray())
            val r = runCmd("zstd", listOf("data.txt"), fs = fs, cwd = "/work")
            assertEquals(1, r.exit)
            assertTrue(String(r.stderr).contains("already exists"))
            // existing file untouched
            assertArrayEquals("existing".toByteArray(), fs.readBytes("/work/data.txt.zst"))
        }

    @Test
    fun recipeForceOverwrite() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.txt", "hello\n".toByteArray())
            fs.writeBytes("/work/data.txt.zst", "stale".toByteArray())
            val r = runCmd("zstd", listOf("-f", "data.txt"), fs = fs, cwd = "/work")
            assertEquals(0, r.exit)
            val out = decompressBytes(fs.readBytes("/work/data.txt.zst"))
            assertArrayEquals("hello\n".toByteArray(), out)
        }

    @Test
    fun recipeUnzstdFileStripsSuffix() =
        runBlocking {
            val fs = InMemoryFs()
            val compressed = referenceCompress("decoded contents\n".toByteArray())
            fs.writeBytes("/work/blob.zst", compressed)
            val r = runCmd("unzstd", listOf("blob.zst"), fs = fs, cwd = "/work")
            assertEquals(0, r.exit, "stderr=${String(r.stderr)}")
            assertTrue(fs.exists("/work/blob"))
            assertArrayEquals("decoded contents\n".toByteArray(), fs.readBytes("/work/blob"))
        }

    @Test
    fun recipeUnzstdWithoutZstSuffix() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/blob", "x".toByteArray())
            val r = runCmd("unzstd", listOf("blob"), fs = fs, cwd = "/work")
            assertEquals(1, r.exit)
            assertTrue(String(r.stderr).contains("unknown suffix"))
        }

    @Test
    fun recipeTestFlagOnValidBlob() =
        runBlocking {
            val fs = InMemoryFs()
            val blob = referenceCompress("verify me\n".toByteArray())
            fs.writeBytes("/work/good.zst", blob)
            val r = runCmd("zstd", listOf("-t", "good.zst"), fs = fs, cwd = "/work")
            assertEquals(0, r.exit, "stderr=${String(r.stderr)}")
            assertTrue(String(r.stdout).contains("good.zst") && String(r.stdout).contains("OK"))
        }

    @Test
    fun recipeTestFlagOnCorruptBlob() =
        runBlocking {
            val fs = InMemoryFs()
            // Random bytes — definitely not a valid zstd frame.
            fs.writeBytes("/work/bad.zst", ByteArray(64) { ((it * 7) xor 0x55).toByte() })
            val r = runCmd("zstd", listOf("-t", "bad.zst"), fs = fs, cwd = "/work")
            assertEquals(1, r.exit)
        }

    @Test
    fun recipeCompressViaPipeMatchesUnzstdViaFile() =
        runBlocking {
            // 1. compress via stdin/stdout
            val input = "pipeline payload\n".repeat(50).toByteArray()
            val r = runCmd("zstd", listOf("-c"), stdinBytes = input)
            assertEquals(0, r.exit)
            // 2. write compressed to FS, then unzstd via filename
            val fs = InMemoryFs()
            fs.writeBytes("/w/x.zst", r.stdout)
            val r2 = runCmd("unzstd", listOf("-c", "x.zst"), fs = fs, cwd = "/w")
            assertEquals(0, r2.exit)
            assertArrayEquals(input, r2.stdout)
        }

    @Test
    fun recipeMissingFileReportsError() =
        runBlocking {
            val fs = InMemoryFs()
            val r = runCmd("zstd", listOf("nope.txt"), fs = fs, cwd = "/")
            assertEquals(1, r.exit)
            assertTrue(String(r.stderr).contains("No such file"))
        }

    @Test
    fun recipeCompressFileWithDashCWritesToStdout() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/x.txt", "stdoutmode\n".toByteArray())
            val r = runCmd("zstd", listOf("-c", "x.txt"), fs = fs, cwd = "/work")
            assertEquals(0, r.exit)
            assertTrue(r.stdout.isNotEmpty())
            // file.zst NOT created
            assertTrue(!fs.exists("/work/x.txt.zst"))
            // and stdout round-trips
            val plain = decompressBytes(r.stdout)
            assertArrayEquals("stdoutmode\n".toByteArray(), plain)
        }

    @Test
    fun helpExitsZero() =
        runBlocking {
            val r = runCmd("zstd", listOf("--help"), stdinBytes = ByteArray(0))
            assertEquals(0, r.exit)
            assertTrue(String(r.stdout).contains("Usage"))
        }

    @Test
    fun versionExitsZero() =
        runBlocking {
            val r = runCmd("zstd", listOf("-V"), stdinBytes = ByteArray(0))
            assertEquals(0, r.exit)
        }

    @Test
    fun dashOWritesToNamedOutput() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/in.txt", "payload\n".toByteArray())
            val r = runCmd("zstd", listOf("-o", "out.zst", "in.txt"), fs = fs)
            assertEquals(0, r.exit, r.stderr.toString(Charsets.UTF_8))
            assertTrue(fs.exists("/out.zst"), "out.zst should have been created")
            // round-trip via -d -o
            val r2 = runCmd("zstd", listOf("-d", "-o", "back.txt", "out.zst"), fs = fs)
            assertEquals(0, r2.exit, r2.stderr.toString(Charsets.UTF_8))
            assertArrayEquals("payload\n".toByteArray(), fs.readBytes("/back.txt"))
        }

    @Test
    fun dashORefusesMultipleInputs() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/a", "A".toByteArray())
            fs.writeBytes("/b", "B".toByteArray())
            val r = runCmd("zstd", listOf("-o", "out.zst", "a", "b"), fs = fs)
            assertEquals(2, r.exit)
            assertTrue(r.stderr.toString(Charsets.UTF_8).contains("multiple"))
        }

    @Test
    fun largeRoundTrip() =
        runBlocking {
            // 256 KiB of mixed-ish data
            val rnd = java.util.Random(42)
            val original = ByteArray(256 * 1024).also { rnd.nextBytes(it) }
            val r = runCmd("zstd", emptyList(), stdinBytes = original)
            assertEquals(0, r.exit)
            val r2 = runCmd("unzstd", emptyList(), stdinBytes = r.stdout)
            assertEquals(0, r2.exit)
            assertArrayEquals(original, r2.stdout)
        }

    // --- harness ---

    private data class CmdResult(
        val stdout: ByteArray,
        val stderr: ByteArray,
        val exit: Int,
    )

    private fun runCmd(
        name: String,
        args: List<String>,
        stdinBytes: ByteArray = ByteArray(0),
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/",
    ): CmdResult {
        val stdin = BufferSource(Buffer().apply { write(stdinBytes) })
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = cwd,
                stdin = stdin,
                stdout = BufferSink(out),
                stderr = BufferSink(err),
            )
        val cmd = zstdCommands.first { it.name == name }.command!!
        val exit = runBlocking { cmd.run(args, ctx).exitCode }
        return CmdResult(out.readByteArray(), err.readByteArray(), exit)
    }

    private suspend fun compressBytes(b: ByteArray): ByteArray {
        val src = BufferSource(Buffer().apply { write(b) })
        val out = Buffer()
        zstdCompress(src, BufferSink(out), level = 3)
        return out.readByteArray()
    }

    private suspend fun decompressBytes(b: ByteArray): ByteArray {
        val src = BufferSource(Buffer().apply { write(b) })
        val out = Buffer()
        zstdDecompress(src, BufferSink(out))
        return out.readByteArray()
    }

    /** Compress using aircompressor directly — provides a known-good blob. */
    private fun referenceCompress(b: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        val z = ZstdOutputStream(baos)
        z.write(b)
        z.close()
        return baos.toByteArray()
    }
}

// --- shared adapters ---

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

@Suppress("UNUSED")
private fun unusedReferences() {
    // keep import for writeString hygiene
    Buffer().writeString("")
}
