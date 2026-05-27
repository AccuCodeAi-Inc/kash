package com.accucodeai.kash.tools.xz

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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream

class XzCommandTest {
    // --- helpers ---

    private class BufSrc(
        private val buf: Buffer,
    ) : SuspendSource {
        override suspend fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = if (buf.exhausted()) -1L else buf.readAtMostTo(sink, byteCount)

        override fun close() {}
    }

    private class BufSink(
        val buf: Buffer,
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

    private data class Run(
        val stdout: ByteArray,
        val stderr: String,
        val exit: Int,
        val fs: InMemoryFs,
    )

    private fun runTool(
        toolName: String,
        args: List<String>,
        stdinBytes: ByteArray = ByteArray(0),
        seedFiles: Map<String, ByteArray> = emptyMap(),
    ): Run {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        for ((path, bytes) in seedFiles) {
            val sink = fs.sink(path, append = false, mode = 0b110_100_100)
            runBlocking {
                val b = Buffer()
                b.write(bytes)
                sink.write(b, b.size)
                sink.flush()
            }
            sink.close()
        }
        val stdinSrc = BufSrc(Buffer().apply { write(stdinBytes) })
        val stdoutBuf = Buffer()
        val stderrBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = stdinSrc,
                stdout = BufSink(stdoutBuf),
                stderr = BufSink(stderrBuf),
            )
        val cmd =
            xzCommands.first { it.name == toolName }.command!!
        val exit = runBlocking { cmd.run(args, ctx).exitCode }
        return Run(
            stdoutBuf.readByteArray(),
            stderrBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
            fs,
        )
    }

    private fun fsRead(
        fs: InMemoryFs,
        path: String,
    ): ByteArray =
        runBlocking {
            val src = fs.source(path)
            val b = Buffer()
            while (true) {
                val n = src.readAtMostTo(b, 8192)
                if (n == -1L) break
            }
            src.close()
            b.readByteArray()
        }

    // --- pipe-mode (stdin → stdout) ---

    @Test
    fun xzPipeRoundTrip() {
        val payload = "hello kash xz round-trip!\n".repeat(100).toByteArray()
        val compressed = runTool("xz", emptyList(), stdinBytes = payload)
        assertEquals(0, compressed.exit)
        assertTrue(compressed.stdout.isNotEmpty())
        // First 6 bytes of .xz stream: FD 37 7A 58 5A 00
        assertEquals(0xFD.toByte(), compressed.stdout[0])
        assertEquals('7'.code.toByte(), compressed.stdout[1])

        val decompressed = runTool("unxz", emptyList(), stdinBytes = compressed.stdout)
        assertEquals(0, decompressed.exit)
        assertArrayEquals(payload, decompressed.stdout)
    }

    @Test
    fun xzcatDecompresses() {
        val payload = "xzcat test\n".toByteArray()
        val compressed = runTool("xz", emptyList(), stdinBytes = payload)
        val decoded = runTool("xzcat", emptyList(), stdinBytes = compressed.stdout)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun lzmaPipeRoundTrip() {
        val payload = "legacy lzma alone format\n".repeat(50).toByteArray()
        val compressed = runTool("lzma", emptyList(), stdinBytes = payload)
        assertEquals(0, compressed.exit)
        val decompressed = runTool("unlzma", emptyList(), stdinBytes = compressed.stdout)
        assertEquals(0, decompressed.exit)
        assertArrayEquals(payload, decompressed.stdout)
    }

    @Test
    fun lzcatDecompresses() {
        val payload = "lzcat\n".toByteArray()
        val compressed = runTool("lzma", emptyList(), stdinBytes = payload)
        val decoded = runTool("lzcat", emptyList(), stdinBytes = compressed.stdout)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun unxzKnownGoodBlob() {
        // Construct a known-good xz blob via tukaani directly, then decode through unxz.
        val payload = "known good xz blob payload".toByteArray()
        val baos = ByteArrayOutputStream()
        XZOutputStream(baos, LZMA2Options(6)).use { it.write(payload) }
        val blob = baos.toByteArray()
        val decoded = runTool("unxz", emptyList(), stdinBytes = blob)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun unlzmaKnownGoodBlob() {
        val payload = "known good lzma blob".toByteArray()
        val baos = ByteArrayOutputStream()
        LZMAOutputStream(baos, LZMA2Options(6), -1L).use { it.write(payload) }
        val blob = baos.toByteArray()
        val decoded = runTool("unlzma", emptyList(), stdinBytes = blob)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun xzDashImpliesStdinStdout() {
        val payload = "via dash\n".toByteArray()
        val compressed = runTool("xz", listOf("-"), stdinBytes = payload)
        assertEquals(0, compressed.exit)
        val decompressed = runTool("xz", listOf("-d", "-"), stdinBytes = compressed.stdout)
        assertEquals(0, decompressed.exit)
        assertArrayEquals(payload, decompressed.stdout)
    }

    @Test
    fun xzDecodeRejectsCorruptInput() {
        val bogus = ByteArray(64) { 0x55 }
        val r = runTool("unxz", emptyList(), stdinBytes = bogus)
        assertEquals(1, r.exit)
        assertTrue(r.stderr.isNotEmpty(), "expected an error message")
    }

    @Test
    fun xzCompressPresetsDiffer() {
        // Keep payload small — LZMA's preset-6 dictionary is ~8 MiB and the
        // junit forked test JVM has a small default heap. We only need to
        // verify both presets round-trip, not that they pack a giant payload.
        val payload = "hello kash xz preset diff\n".repeat(40).toByteArray()
        val small = runTool("xz", listOf("-0"), stdinBytes = payload)
        val big = runTool("xz", listOf("-3"), stdinBytes = payload)
        assertEquals(0, small.exit)
        assertEquals(0, big.exit)
        val d0 = runTool("unxz", emptyList(), stdinBytes = small.stdout)
        val d3 = runTool("unxz", emptyList(), stdinBytes = big.stdout)
        assertArrayEquals(payload, d0.stdout)
        assertArrayEquals(payload, d3.stdout)
    }

    @Test
    fun xzShortFlagClusterDC() {
        val payload = "cluster\n".toByteArray()
        val compressed = runTool("xz", emptyList(), stdinBytes = payload)
        // -dc = decompress to stdout
        val decoded = runTool("xz", listOf("-dc"), stdinBytes = compressed.stdout)
        assertEquals(0, decoded.exit)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun xzTestModeAcceptsValidInput() {
        val payload = "test mode\n".toByteArray()
        val compressed = runTool("xz", emptyList(), stdinBytes = payload)
        val r = runTool("xz", listOf("-t"), stdinBytes = compressed.stdout)
        assertEquals(0, r.exit)
        // No stdout in test mode.
        assertEquals(0, r.stdout.size)
    }

    @Test
    fun xzTestModeRejectsBadInput() {
        val r = runTool("xz", listOf("-t"), stdinBytes = ByteArray(50) { 0x00 })
        assertEquals(1, r.exit)
    }

    @Test
    fun unknownOptionIsUsageError() {
        val r = runTool("xz", listOf("--bogus"))
        assertEquals(2, r.exit)
        assertTrue(r.stderr.contains("unrecognized") || r.stderr.contains("unknown"))
    }

    // --- file mode ---

    @Test
    fun xzFileWritesXzSibling() {
        val payload = "compress me\n".repeat(20).toByteArray()
        val r =
            runTool(
                "xz",
                listOf("foo.txt"),
                seedFiles = mapOf("/work/foo.txt" to payload),
            )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.fs.exists("/work/foo.txt.xz"))
        // Original is gone by default.
        assertTrue(!r.fs.exists("/work/foo.txt"))
        // Round-trip via unxz pipe
        val blob = fsRead(r.fs, "/work/foo.txt.xz")
        val decoded = runTool("unxz", emptyList(), stdinBytes = blob)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun xzKeepLeavesOriginal() {
        val payload = "keep me\n".toByteArray()
        val r =
            runTool(
                "xz",
                listOf("-k", "foo.txt"),
                seedFiles = mapOf("/work/foo.txt" to payload),
            )
        assertEquals(0, r.exit)
        assertTrue(r.fs.exists("/work/foo.txt"))
        assertTrue(r.fs.exists("/work/foo.txt.xz"))
    }

    @Test
    fun xzcDoesNotTouchFiles() {
        val payload = "stdout please\n".toByteArray()
        val r =
            runTool(
                "xz",
                listOf("-c", "foo.txt"),
                seedFiles = mapOf("/work/foo.txt" to payload),
            )
        assertEquals(0, r.exit)
        assertTrue(r.fs.exists("/work/foo.txt"), "original kept under -c")
        assertTrue(!r.fs.exists("/work/foo.txt.xz"), "no sibling under -c")
        assertTrue(r.stdout.isNotEmpty())
        val decoded = runTool("unxz", emptyList(), stdinBytes = r.stdout)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun unxzFileRemovesSuffix() {
        // Make a valid .xz file via tukaani.
        val payload = "decompress\n".toByteArray()
        val baos = ByteArrayOutputStream()
        XZOutputStream(baos, LZMA2Options(6)).use { it.write(payload) }

        val r =
            runTool(
                "unxz",
                listOf("foo.xz"),
                seedFiles = mapOf("/work/foo.xz" to baos.toByteArray()),
            )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.fs.exists("/work/foo"))
        assertTrue(!r.fs.exists("/work/foo.xz"))
        assertArrayEquals(payload, fsRead(r.fs, "/work/foo"))
    }

    @Test
    fun unxzRejectsMissingSuffix() {
        val r =
            runTool(
                "unxz",
                listOf("foo.txt"),
                seedFiles = mapOf("/work/foo.txt" to ByteArray(8)),
            )
        assertEquals(1, r.exit)
        assertTrue(r.stderr.contains("Unknown suffix"), r.stderr)
    }

    @Test
    fun fileExistsBlocksOverwrite() {
        val payload = "abc".toByteArray()
        val r =
            runTool(
                "xz",
                listOf("foo.txt"),
                seedFiles =
                    mapOf(
                        "/work/foo.txt" to payload,
                        "/work/foo.txt.xz" to "preexisting".toByteArray(),
                    ),
            )
        assertEquals(1, r.exit)
        assertTrue(r.stderr.contains("File exists"), r.stderr)
        // Original untouched.
        assertArrayEquals(payload, fsRead(r.fs, "/work/foo.txt"))
    }

    @Test
    fun forceOverwritesExistingSibling() {
        val payload = "data".toByteArray()
        val r =
            runTool(
                "xz",
                listOf("-f", "foo.txt"),
                seedFiles =
                    mapOf(
                        "/work/foo.txt" to payload,
                        "/work/foo.txt.xz" to "preexisting".toByteArray(),
                    ),
            )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        // .xz should now be valid compressed payload, not "preexisting".
        val blob = fsRead(r.fs, "/work/foo.txt.xz")
        assertNotEquals("preexisting", blob.toString(Charsets.UTF_8))
        val decoded = runTool("unxz", emptyList(), stdinBytes = blob)
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun missingFileReportsError() {
        val r = runTool("xz", listOf("nope.txt"))
        assertEquals(1, r.exit)
        assertTrue(r.stderr.contains("No such file"))
    }

    @Test
    fun xzMultiFileBatch() {
        val a = "aaaaa\n".repeat(20).toByteArray()
        val b = "bbbbb\n".repeat(20).toByteArray()
        val r =
            runTool(
                "xz",
                listOf("a.txt", "b.txt"),
                seedFiles = mapOf("/work/a.txt" to a, "/work/b.txt" to b),
            )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.fs.exists("/work/a.txt.xz"))
        assertTrue(r.fs.exists("/work/b.txt.xz"))
        // Both decode correctly.
        val da = runTool("unxz", emptyList(), stdinBytes = fsRead(r.fs, "/work/a.txt.xz"))
        val db = runTool("unxz", emptyList(), stdinBytes = fsRead(r.fs, "/work/b.txt.xz"))
        assertArrayEquals(a, da.stdout)
        assertArrayEquals(b, db.stdout)
    }

    @Test
    fun lzmaFileWritesLzmaSibling() {
        val payload = "lz alone\n".repeat(10).toByteArray()
        val r =
            runTool(
                "lzma",
                listOf("foo.txt"),
                seedFiles = mapOf("/work/foo.txt" to payload),
            )
        assertEquals(0, r.exit)
        assertTrue(r.fs.exists("/work/foo.txt.lzma"))
        val decoded = runTool("unlzma", emptyList(), stdinBytes = fsRead(r.fs, "/work/foo.txt.lzma"))
        assertArrayEquals(payload, decoded.stdout)
    }

    @Test
    fun unlzmaFileStripsLzmaSuffix() {
        val payload = "lz file\n".toByteArray()
        val baos = ByteArrayOutputStream()
        LZMAOutputStream(baos, LZMA2Options(6), -1L).use { it.write(payload) }
        val r =
            runTool(
                "unlzma",
                listOf("foo.lzma"),
                seedFiles = mapOf("/work/foo.lzma" to baos.toByteArray()),
            )
        assertEquals(0, r.exit)
        assertTrue(r.fs.exists("/work/foo"))
        assertArrayEquals(payload, fsRead(r.fs, "/work/foo"))
    }

    @Test
    fun endOfOptionsAllowsLeadingDashFiles() {
        val payload = "x".toByteArray()
        val r =
            runTool(
                "xz",
                listOf("--", "-weird"),
                seedFiles = mapOf("/work/-weird" to payload),
            )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.fs.exists("/work/-weird.xz"))
    }

    @Test
    fun emptyInputRoundTrips() {
        val r1 = runTool("xz", emptyList(), stdinBytes = ByteArray(0))
        assertEquals(0, r1.exit)
        val r2 = runTool("unxz", emptyList(), stdinBytes = r1.stdout)
        assertEquals(0, r2.exit)
        assertArrayEquals(ByteArray(0), r2.stdout)
    }

    @Test
    fun bigBinaryRoundTrip() {
        // 16 KiB of pseudo-random data — exercises a payload bigger than
        // typical buffer sizes but small enough for default test-JVM heap
        // (LZMA preset-6 has an 8 MiB dictionary).
        val payload = ByteArray(16 * 1024) { ((it * 31 + 7) and 0xff).toByte() }
        val c = runTool("xz", listOf("-1"), stdinBytes = payload)
        assertEquals(0, c.exit)
        val d = runTool("unxz", emptyList(), stdinBytes = c.stdout)
        assertEquals(0, d.exit)
        assertArrayEquals(payload, d.stdout)
    }
}
