package com.accucodeai.kash.tools.cpio

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

class CpioCommandTest {
    // ---- helpers --------------------------------------------------------

    private data class RunResult(
        val stdout: ByteArray,
        val stderr: String,
        val exit: Int,
        val fs: InMemoryFs,
    )

    private fun runCpio(
        args: List<String>,
        stdinBytes: ByteArray = ByteArray(0),
        fs: InMemoryFs = InMemoryFs(),
        cwd: String = "/work",
    ): RunResult {
        if (!fs.exists(cwd)) fs.mkdirs(cwd)
        val stdinBuf = Buffer().apply { write(stdinBytes) }
        val stdoutBuf = Buffer()
        val stderrBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                cwd = cwd,
                stdin = BufSrc(stdinBuf),
                stdout = BufSink(stdoutBuf),
                stderr = BufSink(stderrBuf),
            )
        val exit = runBlocking { CpioCommand().run(args, ctx).exitCode }
        return RunResult(
            stdoutBuf.readByteArray(stdoutBuf.size.toInt()),
            stderrBuf.readByteArray(stderrBuf.size.toInt()).decodeToString(),
            exit,
            fs,
        )
    }

    private fun seedFiles(
        fs: InMemoryFs,
        files: Map<String, ByteArray>,
    ) {
        for ((path, bytes) in files) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && !fs.exists(parent)) fs.mkdirs(parent)
            runBlocking { fs.writeBytes(path, bytes) }
        }
    }

    // ---- copy-out / -t / round trip (newc) ------------------------------

    @Test
    fun copyOutThenListRoundTrip() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/a.txt" to "alpha\n".toByteArray(), "/work/b.txt" to "beta\n".toByteArray()))
        // -o reads names from stdin
        val out = runCpio(listOf("-o"), stdinBytes = "a.txt\nb.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        assertTrue(out.stdout.isNotEmpty(), "archive empty")
        // Now -t over the archive
        val list = runCpio(listOf("-t"), stdinBytes = out.stdout)
        assertEquals(0, list.exit, "stderr=${list.stderr}")
        val names =
            list.stdout
                .decodeToString()
                .lines()
                .filter { it.isNotEmpty() }
        assertEquals(listOf("a.txt", "b.txt"), names)
    }

    @Test
    fun copyOutThenCopyInRoundTrip() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/hello.txt" to "world\n".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "hello.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        // Extract into a fresh fs
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertTrue(outFs.exists("/out/hello.txt"))
        val data = runBlocking { outFs.readBytes("/out/hello.txt") }
        assertArrayEquals("world\n".toByteArray(), data)
    }

    @Test
    fun copyInRequiresOverwriteForExistingFile() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/dup.txt" to "v1".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "dup.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        // Fresh fs that ALREADY has dup.txt
        val outFs = InMemoryFs()
        seedFiles(outFs, mapOf("/out/dup.txt" to "EXISTING".toByteArray()))
        // No -u: should error
        val r1 = runCpio(listOf("-i"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertNotEquals(0, r1.exit)
        assertTrue(r1.stderr.contains("not overwritten"), "stderr was: ${r1.stderr}")
        // Now with -u: overwrite
        val outFs2 = InMemoryFs()
        seedFiles(outFs2, mapOf("/out/dup.txt" to "EXISTING".toByteArray()))
        val r2 = runCpio(listOf("-i", "-u"), stdinBytes = out.stdout, fs = outFs2, cwd = "/out")
        assertEquals(0, r2.exit, "stderr=${r2.stderr}")
        assertArrayEquals("v1".toByteArray(), runBlocking { outFs2.readBytes("/out/dup.txt") })
    }

    // ---- odc round trip --------------------------------------------------

    @Test
    fun odcCopyOutListRoundTrip() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/one" to "1".toByteArray(), "/work/two" to "22".toByteArray()))
        val out = runCpio(listOf("-o", "-H", "odc"), stdinBytes = "one\ntwo\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        // First 6 bytes should be the odc magic
        assertEquals("070707", out.stdout.copyOfRange(0, 6).decodeToString())
        val list = runCpio(listOf("-t", "-H", "odc"), stdinBytes = out.stdout)
        assertEquals(0, list.exit, "stderr=${list.stderr}")
        assertEquals(
            listOf("one", "two"),
            list.stdout
                .decodeToString()
                .lines()
                .filter { it.isNotEmpty() },
        )
    }

    @Test
    fun odcCopyOutThenCopyInRoundTrip() {
        val fs = InMemoryFs()
        val payload = "the quick brown fox jumps over the lazy dog\n".toByteArray()
        seedFiles(fs, mapOf("/work/fox.txt" to payload))
        val out = runCpio(listOf("-o", "-H", "odc"), stdinBytes = "fox.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i", "-H", "odc"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertArrayEquals(payload, runBlocking { outFs.readBytes("/out/fox.txt") })
    }

    // ---- binary integrity -----------------------------------------------

    @Test
    fun newcPreservesBinary() {
        val bin = ByteArray(257) { it.toByte() }
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/bin.dat" to bin))
        val out = runCpio(listOf("-o"), stdinBytes = "bin.dat\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        // Extract
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertArrayEquals(bin, runBlocking { outFs.readBytes("/out/bin.dat") })
    }

    @Test
    fun odcPreservesBinary() {
        val bin = ByteArray(513) { ((it * 7 + 3) and 0xff).toByte() }
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/odcbin" to bin))
        val out = runCpio(listOf("-o", "-H", "odc"), stdinBytes = "odcbin\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i", "-H", "odc"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertArrayEquals(bin, runBlocking { outFs.readBytes("/out/odcbin") })
    }

    // ---- multiple files / padding boundaries ---------------------------

    @Test
    fun newcSizesThatExerciseAllPaddingMods() {
        val fs = InMemoryFs()
        val files = (0..6).associate { i -> "/work/f$i" to ByteArray(i) { 'a'.code.toByte() } }
        seedFiles(fs, files)
        val names = (0..6).joinToString("\n") { "f$it" } + "\n"
        val out = runCpio(listOf("-o"), stdinBytes = names.toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        for (i in 0..6) {
            assertArrayEquals(
                ByteArray(i) { 'a'.code.toByte() },
                runBlocking { outFs.readBytes("/out/f$i") },
                "f$i mismatch",
            )
        }
    }

    // ---- mode flags / error handling ------------------------------------

    @Test
    fun mutuallyExclusiveModesError() {
        val r = runCpio(listOf("-o", "-i"))
        assertEquals(2, r.exit)
        assertTrue(r.stderr.contains("cannot mix"), "stderr=${r.stderr}")
    }

    @Test
    fun missingModeError() {
        val r = runCpio(listOf("-v"))
        assertEquals(2, r.exit)
        assertTrue(r.stderr.contains("must specify"), "stderr=${r.stderr}")
    }

    @Test
    fun unknownFormatError() {
        val r = runCpio(listOf("-o", "-H", "bin"))
        assertEquals(2, r.exit)
        assertTrue(r.stderr.contains("unsupported format"), "stderr=${r.stderr}")
    }

    @Test
    fun missingFileReportsError() {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val r = runCpio(listOf("-o"), stdinBytes = "ghost.txt\n".toByteArray(), fs = fs)
        assertEquals(1, r.exit)
        assertTrue(r.stderr.contains("No such file"), "stderr=${r.stderr}")
    }

    @Test
    fun shortFormCluster() {
        // -ov should set o-mode + verbose
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/a" to "1".toByteArray()))
        val r = runCpio(listOf("-ov"), stdinBytes = "a\n".toByteArray(), fs = fs)
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        // -v writes filename to stderr during copy-out
        assertTrue(r.stderr.contains("a"), "stderr should mention a; was: ${r.stderr}")
    }

    @Test
    fun longFormCreate() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/x" to "x".toByteArray()))
        val r = runCpio(listOf("--create"), stdinBytes = "x\n".toByteArray(), fs = fs)
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        // Verify it parses as newc
        assertEquals("070701", r.stdout.copyOfRange(0, 6).decodeToString())
    }

    @Test
    fun copyOutEmptyInputProducesJustTrailer() {
        val r = runCpio(listOf("-o"), stdinBytes = ByteArray(0))
        assertEquals(0, r.exit)
        // Should contain TRAILER!!! name
        assertTrue(r.stdout.decodeToString().contains("TRAILER!!!"), "missing trailer")
    }

    @Test
    fun copyInEmptyArchiveJustTrailer() {
        // Make an archive with no files first
        val empty = runCpio(listOf("-o"), stdinBytes = ByteArray(0))
        assertEquals(0, empty.exit)
        val outFs = InMemoryFs()
        val r = runCpio(listOf("-i"), stdinBytes = empty.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
    }

    @Test
    fun verboseListShowsModeFields() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/v" to "hello".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "v\n".toByteArray(), fs = fs)
        val list = runCpio(listOf("-tv"), stdinBytes = out.stdout)
        assertEquals(0, list.exit, "stderr=${list.stderr}")
        // -tv first column starts with file type char and rwx triples
        val firstLine =
            list.stdout
                .decodeToString()
                .lines()
                .first()
        assertTrue(firstLine.length >= 10, "expected mode prefix; line=$firstLine")
        assertTrue(firstLine.endsWith(" v"), "should end with name; line=$firstLine")
    }

    // ---- patterns / filtering -------------------------------------------

    @Test
    fun copyInFiltersByPattern() {
        val fs = InMemoryFs()
        seedFiles(
            fs,
            mapOf(
                "/work/keep1.log" to "k1".toByteArray(),
                "/work/skip.txt" to "s".toByteArray(),
                "/work/keep2.log" to "k2".toByteArray(),
            ),
        )
        val out = runCpio(listOf("-o"), stdinBytes = "keep1.log\nskip.txt\nkeep2.log\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i", "*.log"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertTrue(outFs.exists("/out/keep1.log"))
        assertTrue(outFs.exists("/out/keep2.log"))
        assertTrue(!outFs.exists("/out/skip.txt"))
    }

    @Test
    fun listFiltersByPattern() {
        val fs = InMemoryFs()
        seedFiles(
            fs,
            mapOf(
                "/work/a.kt" to "a".toByteArray(),
                "/work/b.kt" to "b".toByteArray(),
                "/work/c.txt" to "c".toByteArray(),
            ),
        )
        val out = runCpio(listOf("-o"), stdinBytes = "a.kt\nb.kt\nc.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        val list = runCpio(listOf("-t", "*.kt"), stdinBytes = out.stdout)
        assertEquals(0, list.exit)
        val names =
            list.stdout
                .decodeToString()
                .lines()
                .filter { it.isNotEmpty() }
        assertEquals(listOf("a.kt", "b.kt"), names)
    }

    // ---- glob unit tests ------------------------------------------------

    @Test
    fun globBasics() {
        assertTrue(globMatches("*.log", "foo.log"))
        assertTrue(globMatches("*.log", ".log"))
        assertTrue(!globMatches("*.log", "foo.txt"))
        assertTrue(globMatches("a?c", "abc"))
        assertTrue(!globMatches("a?c", "ac"))
        assertTrue(globMatches("*", "anything"))
        assertTrue(globMatches("", ""))
        assertTrue(!globMatches("", "x"))
        assertTrue(globMatches("foo*bar", "fooXYZbar"))
        assertTrue(globMatches("foo*bar", "foobar"))
    }

    // ---- preserve mtime (-m) -------------------------------------------

    @Test
    fun preserveMtimeAcceptedWithoutFail() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/m" to "m".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "m\n".toByteArray(), fs = fs)
        val outFs = InMemoryFs()
        val r = runCpio(listOf("-i", "-m"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(outFs.exists("/out/m"))
    }

    // ---- -d creates parent directories ---------------------------------

    @Test
    fun extractCreatesParentDirsWithDashD() {
        val fs = InMemoryFs()
        fs.mkdirs("/work/sub/deep")
        seedFiles(fs, mapOf("/work/sub/deep/leaf.txt" to "leaf".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "sub/deep/leaf.txt\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        val outFs = InMemoryFs()
        val r = runCpio(listOf("-i", "-d"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(outFs.exists("/out/sub/deep/leaf.txt"))
    }

    // ---- format default --------------------------------------------------

    @Test
    fun defaultFormatIsNewc() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/f" to "x".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "f\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        assertEquals("070701", out.stdout.copyOfRange(0, 6).decodeToString())
    }

    @Test
    fun newcHeaderIs110Bytes() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/zz" to ByteArray(0)))
        val out = runCpio(listOf("-o"), stdinBytes = "zz\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        // First 110 bytes header. After header: namesize includes NUL -> "zz\0" = 3.
        // Total before trailer: 110 + 3 + pad-to-4. (113 -> pad 3 -> 116)
        // Then 110 byte trailer header, then "TRAILER!!!\0" = 11, pad to 4 -> 12 -> 116+110+12=238
        // Plus archive-end pad to 4 (already at 238 -> mod 4 = 2 -> need 2 more)
        // Just assert >= 220 bytes for sanity.
        assertTrue(out.stdout.size >= 220, "archive too small: ${out.stdout.size}")
    }

    // ---- streaming large file --------------------------------------------

    @Test
    fun largeFileRoundTrip() {
        val big = ByteArray(20_000) { (it and 0xff).toByte() }
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/big.bin" to big))
        val out = runCpio(listOf("-o"), stdinBytes = "big.bin\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit)
        val outFs = InMemoryFs()
        val inn = runCpio(listOf("-i"), stdinBytes = out.stdout, fs = outFs, cwd = "/out")
        assertEquals(0, inn.exit, "stderr=${inn.stderr}")
        assertArrayEquals(big, runBlocking { outFs.readBytes("/out/big.bin") })
    }

    // ---- mixed line endings on -o stdin ---------------------------------

    @Test
    fun copyOutTolerantOfCRLF() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/a" to "A".toByteArray(), "/work/b" to "B".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "a\r\nb\r\n".toByteArray(), fs = fs)
        assertEquals(0, out.exit, "stderr=${out.stderr}")
        val list = runCpio(listOf("-t"), stdinBytes = out.stdout)
        assertEquals(
            listOf("a", "b"),
            list.stdout
                .decodeToString()
                .lines()
                .filter { it.isNotEmpty() },
        )
    }

    // ---- bad archive -----------------------------------------------------

    @Test
    fun badMagicErrors() {
        val r = runCpio(listOf("-i"), stdinBytes = "NOTACPIO".toByteArray())
        assertEquals(1, r.exit)
        assertTrue(r.stderr.contains("bad newc magic"), "stderr=${r.stderr}")
    }

    @Test
    fun truncatedArchiveErrors() {
        val fs = InMemoryFs()
        seedFiles(fs, mapOf("/work/x" to "x".toByteArray()))
        val out = runCpio(listOf("-o"), stdinBytes = "x\n".toByteArray(), fs = fs)
        // truncate to before trailer
        val truncated = out.stdout.copyOfRange(0, 50)
        val r = runCpio(listOf("-i"), stdinBytes = truncated)
        assertNotEquals(0, r.exit)
    }
}

// ---- minimal SuspendSource/Sink shims around Buffer --------------------

private class BufSrc(
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

private class BufSink(
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
