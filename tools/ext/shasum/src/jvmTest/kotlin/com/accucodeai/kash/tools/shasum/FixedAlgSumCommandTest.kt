package com.accucodeai.kash.tools.shasum

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FixedAlgSumCommandTest {
    // Known vectors.
    private val emptyMd5 = "d41d8cd98f00b204e9800998ecf8427e"
    private val abcMd5 = "900150983cd24fb0d6963f7d28e17f72"
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"
    private val abcSha224 = "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7"
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcSha384 =
        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed" +
            "8086072ba1e7cc2358baeca134c825a7"
    private val abcSha512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    // --- md5sum ---

    @Test
    fun md5EmptyStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Md5SumCommand(), emptyList(), stdin = "")
            assertEquals(0, exit)
            assertEquals("$emptyMd5 *-\n", out)
        }

    @Test
    fun md5AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Md5SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcMd5 *-\n", out)
        }

    @Test
    fun md5TextMode() =
        runBlocking {
            val (out, _, exit) = runWith(Md5SumCommand(), listOf("-t"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcMd5  -\n", out)
        }

    @Test
    fun md5File() =
        runBlocking {
            val fs = FakeFsX(mapOf("/work/hi.txt" to "abc".toByteArray()))
            val (out, _, exit) = runWith(Md5SumCommand(), listOf("hi.txt"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcMd5 *hi.txt\n", out)
        }

    @Test
    fun md5CheckPassAndFail() =
        runBlocking {
            val wrong = "0".repeat(32)
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a.txt" to "abc".toByteArray(),
                        "/work/b.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcMd5 *a.txt\n$wrong *b.txt\n".toByteArray(),
                    ),
                )
            val (out, err, exit) = runWith(Md5SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(1, exit)
            assertTrue(out.contains("a.txt: OK"), out)
            assertTrue(out.contains("b.txt: FAILED"), out)
            assertTrue(err.contains("did NOT match"), err)
        }

    @Test
    fun md5MultipleFiles() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a" to "abc".toByteArray(),
                        "/work/b" to "".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(Md5SumCommand(), listOf("a", "b"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcMd5 *a\n$emptyMd5 *b\n", out)
        }

    @Test
    fun md5BsdTag() =
        runBlocking {
            val (out, _, exit) = runWith(Md5SumCommand(), listOf("--tag"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("MD5 (-) = $abcMd5\n", out)
        }

    @Test
    fun md5CheckBsdTag() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a.txt" to "abc".toByteArray(),
                        "/work/sums" to "MD5 (a.txt) = $abcMd5\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(Md5SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("a.txt: OK\n", out)
        }

    // --- sha1sum ---

    @Test
    fun sha1AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha1SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha1 *-\n", out)
        }

    @Test
    fun sha1FileAndCheck() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha1 *a.txt\n".toByteArray(),
                    ),
                )
            val (out1, _, exit1) = runWith(Sha1SumCommand(), listOf("a.txt"), fs = fs)
            assertEquals(0, exit1)
            assertEquals("$abcSha1 *a.txt\n", out1)

            val (out2, _, exit2) = runWith(Sha1SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit2)
            assertEquals("a.txt: OK\n", out2)
        }

    @Test
    fun sha1CheckMissing() =
        runBlocking {
            val fs = FakeFsX(mapOf("/work/sums" to "$abcSha1 *ghost\n".toByteArray()))
            val (out, _, exit) = runWith(Sha1SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(1, exit)
            assertTrue(out.contains("ghost: FAILED open or read"), out)
        }

    // --- sha224sum ---

    @Test
    fun sha224AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha224SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha224 *-\n", out)
        }

    @Test
    fun sha224Check() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha224 *a.txt\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(Sha224SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("a.txt: OK\n", out)
        }

    // --- sha256sum ---

    @Test
    fun sha256AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha256SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha256 *-\n", out)
        }

    @Test
    fun sha256FileTextMode() =
        runBlocking {
            val fs = FakeFsX(mapOf("/work/a.txt" to "abc".toByteArray()))
            val (out, _, exit) = runWith(Sha256SumCommand(), listOf("-t", "a.txt"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcSha256  a.txt\n", out)
        }

    @Test
    fun sha256MultiFileAndCheck() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a" to "abc".toByteArray(),
                        "/work/b" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha256 *a\n$abcSha256 *b\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(Sha256SumCommand(), listOf("a", "b"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcSha256 *a\n$abcSha256 *b\n", out)

            val (out2, _, exit2) = runWith(Sha256SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit2)
            assertEquals("a: OK\nb: OK\n", out2)
        }

    @Test
    fun sha256BadChecksumLength() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a" to "abc".toByteArray(),
                        "/work/sums" to "abcd *a\n".toByteArray(),
                    ),
                )
            val (_, err, exit) = runWith(Sha256SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("bad checksum length"), err)
        }

    // --- sha384sum / sha512sum ---

    @Test
    fun sha384AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha384SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha384 *-\n", out)
        }

    @Test
    fun sha512AbcStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha512SumCommand(), emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha512 *-\n", out)
        }

    @Test
    fun sha512Check() =
        runBlocking {
            val fs =
                FakeFsX(
                    mapOf(
                        "/work/a" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha512 *a\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(Sha512SumCommand(), listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("a: OK\n", out)
        }

    // --- common surface ---

    @Test
    fun unknownOption() =
        runBlocking {
            val (_, err, exit) = runWith(Sha256SumCommand(), listOf("--bogus"), stdin = "")
            assertEquals(1, exit)
            assertTrue(err.contains("unknown option"), err)
        }

    @Test
    fun dashFileMeansStdin() =
        runBlocking {
            val (out, _, exit) = runWith(Sha256SumCommand(), listOf("-"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha256 *-\n", out)
        }

    @Test
    fun doubleDashEndsOptions() =
        runBlocking {
            val fs = FakeFsX(mapOf("/work/--weird" to "abc".toByteArray()))
            val (out, _, exit) = runWith(Sha256SumCommand(), listOf("--", "--weird"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcSha256 *--weird\n", out)
        }

    @Test
    fun missingFileExit1() =
        runBlocking {
            val fs = FakeFsX(emptyMap())
            val (_, err, exit) = runWith(Md5SumCommand(), listOf("nope"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("No such file or directory"), err)
        }

    // --- harness ---

    private data class Run(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private fun runWith(
        cmd: Command,
        args: List<String>,
        stdin: String = "",
        fs: FakeFsX = FakeFsX(emptyMap()),
    ): Run {
        val stdinSrc = BufferRawSource(Buffer().apply { writeString(stdin) })
        val stdoutBuf = Buffer()
        val stderrBuf = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = stdinSrc,
                stdout = BufferRawSink(stdoutBuf),
                stderr = BufferRawSink(stderrBuf),
            )
        val exit = runBlocking { cmd.run(args, ctx).exitCode }
        return Run(stdoutBuf.readString(), stderrBuf.readString(), exit)
    }
}

private class BufferRawSource(
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

private class BufferRawSink(
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

private class FakeFsX(
    private val files: Map<String, ByteArray>,
) : FileSystem {
    override fun exists(path: String): Boolean = files.containsKey(normalize(path))

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource {
        val bytes = files[normalize(path)] ?: throw FileNotFound(path)
        val b = Buffer()
        b.write(bytes)
        return BufferRawSource(b)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = error("not supported in tests")

    override fun mkdirs(
        path: String,
        mode: Int,
    ) = error("not supported in tests")

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) = error("not supported in tests")

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
