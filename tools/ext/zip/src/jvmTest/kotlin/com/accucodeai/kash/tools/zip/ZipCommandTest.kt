package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class ZipCommandTest {
    // --- Unit / semantic tests ---------------------------------------------

    @Test
    fun canonicalEntryNameStripsLeadingDotSlash() {
        assertEquals("foo/bar", canonicalEntryName("./foo/bar"))
    }

    @Test
    fun canonicalEntryNameStripsLeadingSlash() {
        assertEquals("foo/bar", canonicalEntryName("/foo/bar"))
    }

    @Test
    fun canonicalEntryNameStripsTrailingSlash() {
        assertEquals("foo", canonicalEntryName("foo/"))
    }

    @Test
    fun formatEpochUtcStable() {
        // 1234567890 = 2009-02-13 23:31 UTC.
        assertEquals("2009-02-13 23:31", formatEpochUtc(1234567890L))
        assertEquals("1970-01-01 00:00", formatEpochUtc(0L))
    }

    @Test
    fun resolvePathAbsolutePassthrough() {
        assertEquals("/a", resolvePath("/work", "/a"))
    }

    @Test
    fun resolvePathRelativeJoin() {
        assertEquals("/work/a", resolvePath("/work", "a"))
    }

    // --- ZipCommand tests --------------------------------------------------

    @Test
    fun zipNoArgsErrors() =
        runBlocking {
            val r = runZip(emptyList())
            assertEquals(2, r.exit)
            assertTrue(r.stderr.contains("missing archive"))
        }

    @Test
    fun zipMissingFileReportsError() =
        runBlocking {
            val fs = InMemoryFs()
            val r = runZip(listOf("out.zip", "nope.txt"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.stderr.contains("No such file"), "stderr: ${r.stderr}")
        }

    @Test
    fun zipDirectoryWithoutRDashErrors() =
        runBlocking {
            val fs =
                InMemoryFs().also {
                    it.mkdirs("/work/d")
                    runBlocking { it.writeBytes("/work/d/a.txt", "A".toByteArray()) }
                }
            val r = runZip(listOf("out.zip", "d"), fs = fs)
            assertEquals(1, r.exit)
            assertTrue(r.stderr.contains("is a directory"), "stderr: ${r.stderr}")
        }

    @Test
    fun zipSingleFileRoundTrips() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "hello\n".toByteArray())
            val r = runZip(listOf("out.zip", "a.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue(fs.exists("/work/out.zip"))
            val entries = readZipEntries(fs.readBytes("/work/out.zip"))
            assertEquals(1, entries.size)
            assertEquals("a.txt", entries[0].first)
            assertArrayEquals("hello\n".toByteArray(), entries[0].second)
        }

    @Test
    fun zipRecursivePreservesTree() =
        runBlocking {
            val fs = InMemoryFs()
            fs.mkdirs("/work/d/sub")
            fs.writeBytes("/work/d/a.txt", "A".toByteArray())
            fs.writeBytes("/work/d/sub/b.txt", "B".toByteArray())
            val r = runZip(listOf("-r", "out.zip", "d"), fs = fs)
            assertEquals(0, r.exit)
            val entries = readZipEntries(fs.readBytes("/work/out.zip")).map { it.first }.toSet()
            assertTrue(entries.contains("d/"), "missing dir entry: $entries")
            assertTrue(entries.contains("d/a.txt"), "missing a.txt: $entries")
            assertTrue(entries.contains("d/sub/b.txt"), "missing nested b.txt: $entries")
        }

    @Test
    fun zipLevelZeroStored() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "x".repeat(200).toByteArray())
            val r = runZip(listOf("-0", "out.zip", "a.txt"), fs = fs)
            assertEquals(0, r.exit)
            val bytes = fs.readBytes("/work/out.zip")
            // STORED entries embed the payload bytes literally — search for 200 'x'.
            val payload = "x".repeat(200).toByteArray()
            assertTrue(bytes.indexedOf(payload) >= 0, "expected literal payload in stored archive")
        }

    @Test
    fun zipQuietSuppressesProgress() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "A".toByteArray())
            val r = runZip(listOf("-q", "out.zip", "a.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertFalse(r.stderr.contains("adding:"), "expected no 'adding:' line: ${r.stderr}")
        }

    @Test
    fun zipUnknownOptionUsageError() =
        runBlocking {
            val r = runZip(listOf("--bogus", "out.zip", "a"))
            assertEquals(2, r.exit)
            assertTrue(r.stderr.contains("unknown option"))
        }

    @Test
    fun zipDashWritesArchiveToStdout() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "hi".toByteArray())
            val r = runZip(listOf("-", "a.txt"), fs = fs)
            assertEquals(0, r.exit)
            val entries = readZipEntries(r.stdoutBytes)
            assertEquals(1, entries.size)
            assertEquals("a.txt", entries[0].first)
            assertArrayEquals("hi".toByteArray(), entries[0].second)
        }

    // --- UnzipCommand tests ------------------------------------------------

    @Test
    fun unzipListShowsEntries() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "hello".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            val r = runUnzip(listOf("-l", "out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue(r.stdout.contains("a.txt"), "stdout: ${r.stdout}")
            assertTrue(r.stdout.contains("Archive:"), "stdout: ${r.stdout}")
            assertTrue(r.stdout.contains("1 file"), "stdout: ${r.stdout}")
        }

    @Test
    fun unzipPipeStdoutEmitsContents() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "hello world\n".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            val r = runUnzip(listOf("-p", "out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertArrayEquals("hello world\n".toByteArray(), r.stdoutBytes)
        }

    @Test
    fun unzipExtractsToCwd() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            fs.remove("/work/a.txt")
            val r = runUnzip(listOf("out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertArrayEquals("AAA".toByteArray(), fs.readBytes("/work/a.txt"))
        }

    @Test
    fun unzipExtractsToDestDir() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            val r = runUnzip(listOf("-d", "outdir", "out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertArrayEquals("AAA".toByteArray(), fs.readBytes("/work/outdir/a.txt"))
        }

    @Test
    fun unzipSkipsExistingWithoutOverwrite() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            // Make destination already exist with different content.
            fs.writeBytes("/work/a.txt", "OLD".toByteArray())
            val r = runUnzip(listOf("out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertTrue(r.stderr.contains("already exists"), "stderr: ${r.stderr}")
            assertArrayEquals("OLD".toByteArray(), fs.readBytes("/work/a.txt"))
        }

    @Test
    fun unzipOverwriteFlagReplaces() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            runZip(listOf("out.zip", "a.txt"), fs = fs)
            fs.writeBytes("/work/a.txt", "OLD".toByteArray())
            val r = runUnzip(listOf("-o", "out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertArrayEquals("AAA".toByteArray(), fs.readBytes("/work/a.txt"))
        }

    @Test
    fun unzipFilterPicksMember() =
        runBlocking {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a.txt", "AAA".toByteArray())
            fs.writeBytes("/work/b.txt", "BBB".toByteArray())
            runZip(listOf("out.zip", "a.txt", "b.txt"), fs = fs)
            fs.remove("/work/a.txt")
            fs.remove("/work/b.txt")
            val r = runUnzip(listOf("out.zip", "b.txt"), fs = fs)
            assertEquals(0, r.exit)
            assertFalse(fs.exists("/work/a.txt"))
            assertArrayEquals("BBB".toByteArray(), fs.readBytes("/work/b.txt"))
        }

    @Test
    fun unzipRejectsPathTraversal() =
        runBlocking {
            // Hand-craft an archive containing ../etc/passwd-style entry.
            val raw = handCraftArchiveWithEntry("../escape.txt", "evil".toByteArray())
            val fs = InMemoryFs()
            fs.writeBytes("/work/evil.zip", raw)
            val r = runUnzip(listOf("evil.zip"), fs = fs)
            // Should refuse to write outside dest dir; entry skipped.
            assertEquals(0, r.exit)
            assertTrue(r.stderr.contains("unsafe entry"), "stderr: ${r.stderr}")
            assertFalse(fs.exists("/work/../escape.txt"))
        }

    @Test
    fun unzipMissingArchiveError() =
        runBlocking {
            val fs = InMemoryFs()
            val r = runUnzip(listOf("nope.zip"), fs = fs)
            assertEquals(9, r.exit)
            assertTrue(r.stderr.contains("cannot find"), "stderr: ${r.stderr}")
        }

    @Test
    fun unzipUnknownOptionUsageError() =
        runBlocking {
            val r = runUnzip(listOf("-Z", "out.zip"))
            assertEquals(2, r.exit)
            assertTrue(r.stderr.contains("unknown option"))
        }

    // --- Recipe tests ------------------------------------------------------

    @Test
    fun recipeCreateThenList() =
        runBlocking {
            // "zip out.zip a b c | unzip -l out.zip"
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "AA".toByteArray())
            fs.writeBytes("/work/b", "BB".toByteArray())
            fs.writeBytes("/work/c", "CC".toByteArray())
            val r1 = runZip(listOf("out.zip", "a", "b", "c"), fs = fs)
            assertEquals(0, r1.exit)
            val r2 = runUnzip(listOf("-l", "out.zip"), fs = fs)
            assertEquals(0, r2.exit)
            assertTrue(r2.stdout.contains("a") && r2.stdout.contains("b") && r2.stdout.contains("c"))
            assertTrue(r2.stdout.contains("3 files"), "stdout: ${r2.stdout}")
        }

    @Test
    fun recipeRoundTripTree() =
        runBlocking {
            // zip -r tree.zip src && unzip tree.zip -d /out
            val fs = InMemoryFs()
            fs.mkdirs("/work/src/lib")
            fs.writeBytes("/work/src/main.kt", "fun main(){}".toByteArray())
            fs.writeBytes("/work/src/lib/util.kt", "object Util".toByteArray())
            val r1 = runZip(listOf("-r", "tree.zip", "src"), fs = fs)
            assertEquals(0, r1.exit)
            val r2 = runUnzip(listOf("-d", "out", "tree.zip"), fs = fs)
            assertEquals(0, r2.exit)
            assertArrayEquals("fun main(){}".toByteArray(), fs.readBytes("/work/out/src/main.kt"))
            assertArrayEquals("object Util".toByteArray(), fs.readBytes("/work/out/src/lib/util.kt"))
        }

    @Test
    fun recipeUnzipPipeIntoConsumer() =
        runBlocking {
            // unzip -p out.zip | (count bytes)
            val fs = InMemoryFs()
            val payload = ByteArray(500) { (it % 256).toByte() }
            fs.writeBytes("/work/blob.bin", payload)
            runZip(listOf("out.zip", "blob.bin"), fs = fs)
            val r = runUnzip(listOf("-p", "out.zip"), fs = fs)
            assertEquals(0, r.exit)
            assertArrayEquals(payload, r.stdoutBytes)
        }

    @Test
    fun recipeOverwriteWorkflow() =
        runBlocking {
            // Build archive v1, extract, modify file, extract v2 with -o.
            val fs = InMemoryFs()
            fs.writeBytes("/work/conf.cfg", "v1".toByteArray())
            runZip(listOf("v1.zip", "conf.cfg"), fs = fs)
            fs.writeBytes("/work/conf.cfg", "v2".toByteArray())
            runZip(listOf("v2.zip", "conf.cfg"), fs = fs)
            // Extract v1 first.
            fs.remove("/work/conf.cfg")
            assertEquals(0, runUnzip(listOf("v1.zip"), fs = fs).exit)
            assertArrayEquals("v1".toByteArray(), fs.readBytes("/work/conf.cfg"))
            // Now overwrite with v2.
            assertEquals(0, runUnzip(listOf("-o", "v2.zip"), fs = fs).exit)
            assertArrayEquals("v2".toByteArray(), fs.readBytes("/work/conf.cfg"))
        }

    @Test
    fun recipeArchiveStreamedViaStdout() =
        runBlocking {
            // zip - file | unzip -l - : pipe a stdout archive into unzip.
            val fs = InMemoryFs()
            fs.writeBytes("/work/payload.dat", "data!".toByteArray())
            val r1 = runZip(listOf("-", "payload.dat"), fs = fs)
            assertEquals(0, r1.exit)
            // Feed those bytes to `unzip -l -`.
            val r2 = runUnzip(listOf("-l", "-"), fs = fs, stdinBytes = r1.stdoutBytes)
            assertEquals(0, r2.exit)
            assertTrue(r2.stdout.contains("payload.dat"), "stdout: ${r2.stdout}")
        }

    @Test
    fun recipeBinaryRoundTripExact() =
        runBlocking {
            // Round-trip a 256-byte binary payload exactly through zip/unzip.
            val fs = InMemoryFs()
            val payload = ByteArray(256) { it.toByte() }
            fs.writeBytes("/work/bin.dat", payload)
            assertEquals(0, runZip(listOf("out.zip", "bin.dat"), fs = fs).exit)
            fs.remove("/work/bin.dat")
            assertEquals(0, runUnzip(listOf("out.zip"), fs = fs).exit)
            assertArrayEquals(payload, fs.readBytes("/work/bin.dat"))
        }

    @Test
    fun recipeAppendNeverOverwritesWithoutFlag() =
        runBlocking {
            // Defensive workflow: extract twice, first creates files, second
            // refuses (no -o) — output remains unchanged.
            val fs = InMemoryFs()
            fs.writeBytes("/work/note.txt", "NOTE".toByteArray())
            runZip(listOf("out.zip", "note.txt"), fs = fs)
            fs.remove("/work/note.txt")
            // First extract: creates the file.
            assertEquals(0, runUnzip(listOf("out.zip"), fs = fs).exit)
            assertArrayEquals("NOTE".toByteArray(), fs.readBytes("/work/note.txt"))
            // Second extract: file exists; refused without -o.
            val r2 = runUnzip(listOf("out.zip"), fs = fs)
            assertEquals(0, r2.exit)
            assertTrue(r2.stderr.contains("already exists"))
        }

    // --- Harness -----------------------------------------------------------

    private data class Run(
        val stdout: String,
        val stdoutBytes: ByteArray,
        val stderr: String,
        val exit: Int,
    )

    private fun runZip(
        args: List<String>,
        fs: InMemoryFs = InMemoryFs(),
        stdinBytes: ByteArray = ByteArray(0),
    ): Run = runCmd(ZipCommand(), args, fs, stdinBytes)

    private fun runUnzip(
        args: List<String>,
        fs: InMemoryFs = InMemoryFs(),
        stdinBytes: ByteArray = ByteArray(0),
    ): Run = runCmd(UnzipCommand(), args, fs, stdinBytes)

    private fun runCmd(
        cmd: com.accucodeai.kash.api.Command,
        args: List<String>,
        fs: InMemoryFs,
        stdinBytes: ByteArray,
    ): Run {
        if (!fs.exists("/work")) fs.mkdirs("/work")
        val stdinSrc = BufferSource(Buffer().apply { write(stdinBytes) })
        val stdoutBuf = Buffer()
        val stderrBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = stdinSrc,
                stdout = BufferSink(stdoutBuf),
                stderr = BufferSink(stderrBuf),
            )
        val exit = runBlocking { cmd.run(args, ctx).exitCode }
        val stdoutBytes = stdoutBuf.readByteArray()
        return Run(
            stdout = String(stdoutBytes, Charsets.UTF_8),
            stdoutBytes = stdoutBytes,
            stderr = stderrBuf.readByteArray().toString(Charsets.UTF_8),
            exit = exit,
        )
    }

    private fun readZipEntries(archive: ByteArray): List<Pair<String, ByteArray>> {
        val zin = ZipInputStream(ByteArrayInputStream(archive))
        val out = mutableListOf<Pair<String, ByteArray>>()
        while (true) {
            val e = zin.nextEntry ?: break
            val buf = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(1024)
            while (true) {
                val n = zin.read(tmp)
                if (n <= 0) break
                buf.write(tmp, 0, n)
            }
            out += (e.name to buf.toByteArray())
        }
        zin.close()
        return out
    }

    private fun handCraftArchiveWithEntry(
        name: String,
        body: ByteArray,
    ): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        val zo = java.util.zip.ZipOutputStream(baos)
        val e = java.util.zip.ZipEntry(name)
        zo.putNextEntry(e)
        zo.write(body)
        zo.closeEntry()
        zo.close()
        return baos.toByteArray()
    }
}

private fun ByteArray.indexedOf(needle: ByteArray): Int {
    outer@ for (i in 0..(this.size - needle.size)) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
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
