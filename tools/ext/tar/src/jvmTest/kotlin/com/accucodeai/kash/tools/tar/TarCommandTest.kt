package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TarCommandTest {
    // ---------------- CREATE + LIST ----------------

    @Test
    fun createAndListSingleFile() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/hello.txt", "hello\n".toByteArray())
            val archive = runTar(fs, listOf("-cf", "-", "hello.txt"), cwd = "/work")
            assertEquals(0, archive.exit)
            // Pipe archive bytes through -t
            val listed = runTar(fs, listOf("-tf", "-"), cwd = "/work", stdinBytes = archive.stdoutBytes)
            assertEquals(0, listed.exit)
            assertEquals("hello.txt\n", listed.stdoutText)
        }

    @Test
    fun createAndListDirectoryTree() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/dir/sub")
            fs.writeBytes("/work/dir/a.txt", "AAA".toByteArray())
            fs.writeBytes("/work/dir/sub/b.txt", "BBB".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", "dir"), cwd = "/work")
            assertEquals(0, arch.exit)
            val listed = runTar(fs, listOf("-tf", "-"), cwd = "/work", stdinBytes = arch.stdoutBytes)
            val names =
                listed.stdoutText
                    .trim()
                    .lines()
                    .toSet()
            assertTrue(names.contains("dir/"))
            assertTrue(names.contains("dir/a.txt"))
            assertTrue(names.contains("dir/sub/"))
            assertTrue(names.contains("dir/sub/b.txt"))
        }

    // ---------------- ROUND-TRIP ----------------

    @Test
    fun roundTripExtractRestoresContents() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            fs.writeBytes("/src/a.txt", "alpha\n".toByteArray())
            fs.writeBytes("/src/b.bin", byteArrayOf(0, 1, 2, 3, 0xff.toByte()))
            // Tar to bytes.
            val arch = runTar(fs, listOf("-cf", "-", "."), cwd = "/src")
            assertEquals(0, arch.exit)
            // Extract into /dst
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertArrayEquals("alpha\n".toByteArray(), fs.readBytes("/dst/a.txt"))
            assertArrayEquals(byteArrayOf(0, 1, 2, 3, 0xff.toByte()), fs.readBytes("/dst/b.bin"))
        }

    @Test
    fun roundTripPreservesLargeFile() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            val big = ByteArray(50_000) { (it and 0xff).toByte() }
            fs.writeBytes("/src/big.bin", big)
            val arch = runTar(fs, listOf("-cf", "-", "big.bin"), cwd = "/src")
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertArrayEquals(big, fs.readBytes("/dst/big.bin"))
        }

    // ---------------- -z gzip ----------------

    @Test
    fun gzipRoundTrip() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            fs.writeBytes("/src/file.txt", "gzipped\n".toByteArray())
            val arch = runTar(fs, listOf("-czf", "-", "file.txt"), cwd = "/src")
            assertEquals(0, arch.exit)
            // gzip header magic 0x1f 0x8b
            assertEquals(0x1f.toByte(), arch.stdoutBytes[0])
            assertEquals(0x8b.toByte(), arch.stdoutBytes[1])
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xzf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertArrayEquals("gzipped\n".toByteArray(), fs.readBytes("/dst/file.txt"))
        }

    @Test
    fun gzipListAfterCreate() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/a.txt", "A".toByteArray())
            fs.writeBytes("/work/b.txt", "B".toByteArray())
            val arch = runTar(fs, listOf("-czf", "-", "a.txt", "b.txt"), cwd = "/work")
            val listed = runTar(fs, listOf("-tzf", "-"), cwd = "/work", stdinBytes = arch.stdoutBytes)
            val names =
                listed.stdoutText
                    .trim()
                    .lines()
                    .toSet()
            assertEquals(setOf("a.txt", "b.txt"), names)
        }

    // ---------------- file operand ----------------

    @Test
    fun createWritesToFileOperand() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/foo.txt", "foo".toByteArray())
            val rc = runTar(fs, listOf("-cf", "/tmp/out.tar", "foo.txt"), cwd = "/work")
            assertEquals(0, rc.exit)
            assertTrue(fs.exists("/tmp/out.tar"))
            // List via file operand
            fs.mkdirs("/elsewhere")
            val listed = runTar(fs, listOf("-tf", "/tmp/out.tar"), cwd = "/elsewhere")
            assertEquals("foo.txt\n", listed.stdoutText)
        }

    // ---------------- -C chdir ----------------

    @Test
    fun chdirChangesArchiveRoots() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/somewhere/sub")
            fs.writeBytes("/somewhere/sub/inner.txt", "X".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", "-C", "/somewhere", "sub"), cwd = "/")
            assertEquals(0, arch.exit)
            val listed = runTar(fs, listOf("-tf", "-"), cwd = "/", stdinBytes = arch.stdoutBytes)
            val names =
                listed.stdoutText
                    .trim()
                    .lines()
                    .toSet()
            assertTrue(names.contains("sub/"))
            assertTrue(names.contains("sub/inner.txt"))
        }

    // ---------------- --exclude ----------------

    @Test
    fun excludePatternSkipsMatches() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/keep.txt", "K".toByteArray())
            fs.writeBytes("/work/skip.log", "S".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", "--exclude=*.log", "."), cwd = "/work")
            val listed = runTar(fs, listOf("-tf", "-"), cwd = "/work", stdinBytes = arch.stdoutBytes)
            val names =
                listed.stdoutText
                    .trim()
                    .lines()
                    .toSet()
            assertTrue(names.contains("./keep.txt"))
            assertFalse(names.any { it.endsWith("skip.log") })
        }

    // ---------------- --strip-components ----------------

    @Test
    fun stripComponentsPeelsLeadingDirs() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src/outer/inner")
            fs.writeBytes("/src/outer/inner/leaf.txt", "L".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", "outer"), cwd = "/src")
            fs.mkdirs("/dst")
            val extr =
                runTar(
                    fs,
                    listOf("-xf", "-", "--strip-components=1"),
                    cwd = "/dst",
                    stdinBytes = arch.stdoutBytes,
                )
            assertEquals(0, extr.exit)
            assertTrue(fs.exists("/dst/inner/leaf.txt"))
            assertFalse(fs.exists("/dst/outer"))
        }

    // ---------------- long paths ----------------

    @Test
    fun longPathRoundTripsViaGnuLongName() =
        runTest {
            val fs = InMemoryFs()
            // Build a name > 100 bytes that doesn't fit in prefix/name split
            val longLeaf = "x".repeat(120) + ".txt"
            fs.mkdirs("/src")
            fs.writeBytes("/src/$longLeaf", "L".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", longLeaf), cwd = "/src")
            assertEquals(0, arch.exit)
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertTrue(fs.exists("/dst/$longLeaf"))
        }

    // ---------------- verbose ----------------

    @Test
    fun verboseWritesNamesToStderr() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            fs.writeBytes("/work/a.txt", "A".toByteArray())
            val arch = runTar(fs, listOf("-cvf", "-", "a.txt"), cwd = "/work")
            assertEquals(0, arch.exit)
            assertTrue(arch.stderrText.contains("a.txt"), "stderr was: ${arch.stderrText}")
        }

    // ---------------- error cases ----------------

    @Test
    fun missingModeErrors() =
        runTest {
            val fs = InMemoryFs()
            val rc = runTar(fs, listOf("-f", "-"), cwd = "/")
            assertEquals(2, rc.exit)
            assertTrue(rc.stderrText.contains("one of the '-cxt' options"), rc.stderrText)
        }

    @Test
    fun bzip2RoundTrip() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            fs.writeBytes("/src/file.txt", "bzipped\n".toByteArray())
            val arch = runTar(fs, listOf("-cjf", "-", "file.txt"), cwd = "/src")
            assertEquals(0, arch.exit)
            // bzip2 magic "BZh"
            assertEquals('B'.code.toByte(), arch.stdoutBytes[0])
            assertEquals('Z'.code.toByte(), arch.stdoutBytes[1])
            assertEquals('h'.code.toByte(), arch.stdoutBytes[2])
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xjf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertArrayEquals("bzipped\n".toByteArray(), fs.readBytes("/dst/file.txt"))
        }

    @Test
    fun xzRoundTrip() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            fs.writeBytes("/src/file.txt", "xzipped\n".toByteArray())
            val arch = runTar(fs, listOf("-cJf", "-", "file.txt"), cwd = "/src")
            assertEquals(0, arch.exit)
            // xz magic FD 37 7A 58 5A 00
            assertEquals(0xFD.toByte(), arch.stdoutBytes[0])
            assertEquals(0x37.toByte(), arch.stdoutBytes[1])
            assertEquals(0x7A.toByte(), arch.stdoutBytes[2])
            fs.mkdirs("/dst")
            val extr = runTar(fs, listOf("-xJf", "-"), cwd = "/dst", stdinBytes = arch.stdoutBytes)
            assertEquals(0, extr.exit)
            assertArrayEquals("xzipped\n".toByteArray(), fs.readBytes("/dst/file.txt"))
        }

    @Test
    fun mutuallyExclusiveCompressionFlags() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/w")
            fs.writeBytes("/w/x", "X".toByteArray())
            val rc = runTar(fs, listOf("-czjf", "-", "x"), cwd = "/w")
            assertEquals(2, rc.exit)
            assertTrue(rc.stderrText.contains("mutually exclusive"), rc.stderrText)
        }

    @Test
    fun missingFileOnExtractErrors() =
        runTest {
            val fs = InMemoryFs()
            val rc = runTar(fs, listOf("-xf", "/no/such.tar"), cwd = "/")
            assertEquals(2, rc.exit)
            assertTrue(rc.stderrText.contains("Cannot open"), rc.stderrText)
        }

    @Test
    fun unknownLongOptionErrors() =
        runTest {
            val fs = InMemoryFs()
            val rc = runTar(fs, listOf("--bogus"), cwd = "/")
            assertEquals(2, rc.exit)
            assertTrue(rc.stderrText.contains("unknown option"), rc.stderrText)
        }

    @Test
    fun legacyClusterFormParses() =
        runTest {
            // First arg is "cf" with no leading dash — legacy tar style.
            val fs = InMemoryFs()
            fs.mkdirs("/w")
            fs.writeBytes("/w/y.txt", "Y".toByteArray())
            val arch = runTar(fs, listOf("cf", "-", "y.txt"), cwd = "/w")
            assertEquals(0, arch.exit)
            val listed = runTar(fs, listOf("tf", "-"), cwd = "/w", stdinBytes = arch.stdoutBytes)
            assertEquals("y.txt\n", listed.stdoutText)
        }

    @Test
    fun pathFilterOnExtractKeepsOnlyMatches() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/src")
            fs.writeBytes("/src/keep.txt", "K".toByteArray())
            fs.writeBytes("/src/drop.txt", "D".toByteArray())
            val arch = runTar(fs, listOf("-cf", "-", "."), cwd = "/src")
            fs.mkdirs("/dst")
            val extr =
                runTar(
                    fs,
                    listOf("-xf", "-", "./keep.txt"),
                    cwd = "/dst",
                    stdinBytes = arch.stdoutBytes,
                )
            assertEquals(0, extr.exit)
            assertTrue(fs.exists("/dst/keep.txt"))
            assertFalse(fs.exists("/dst/drop.txt"))
        }

    // ---------------- Harness ----------------

    private data class TarRun(
        val stdoutBytes: ByteArray,
        val stderrText: String,
        val exit: Int,
    ) {
        val stdoutText: String get() = stdoutBytes.toString(Charsets.UTF_8)
    }

    private suspend fun runTar(
        fs: InMemoryFs,
        args: List<String>,
        cwd: String,
        stdinBytes: ByteArray = ByteArray(0),
    ): TarRun {
        val stdinBuf = Buffer().apply { write(stdinBytes) }
        val stdoutBuf = Buffer()
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
        val exit = TarCommand().run(args, ctx).exitCode
        return TarRun(
            stdoutBytes = stdoutBuf.readByteArray(),
            stderrText = stderrBuf.readByteArray().toString(Charsets.UTF_8),
            exit = exit,
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
