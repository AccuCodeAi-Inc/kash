package com.accucodeai.kash.tools.shasum

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

class ShaSumCommandTest {
    // Known SHA digests of the empty string and "abc".
    private val emptySha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val abcSha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    private val abcSha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"
    private val abcSha512 =
        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
            "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    @Test
    fun stdinDefaultSha256() =
        runBlocking {
            val (out, _, exit) = runWith(emptyList(), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha256  -\n", out)
        }

    @Test
    fun emptyStdin() =
        runBlocking {
            val (out, _, exit) = runWith(emptyList(), stdin = "")
            assertEquals(0, exit)
            assertEquals("$emptySha256  -\n", out)
        }

    @Test
    fun sha1Flag() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("-a", "1"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha1  -\n", out)
        }

    @Test
    fun sha512Flag() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("-a", "512"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha512  -\n", out)
        }

    @Test
    fun binaryMarker() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("-b"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha256 *-\n", out)
        }

    @Test
    fun fileInput() =
        runBlocking {
            val fs = FakeFs(mapOf("/work/hello.txt" to "abc".toByteArray()))
            val (out, _, exit) = runWith(listOf("hello.txt"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$abcSha256  hello.txt\n", out)
        }

    @Test
    fun multipleFiles() =
        runBlocking {
            val fs =
                FakeFs(
                    mapOf(
                        "/work/a.txt" to "abc".toByteArray(),
                        "/work/b.txt" to "".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(listOf("a.txt", "b.txt"), fs = fs)
            assertEquals(0, exit)
            assertEquals(
                "$abcSha256  a.txt\n$emptySha256  b.txt\n",
                out,
            )
        }

    @Test
    fun missingFileReportsError() =
        runBlocking {
            val fs = FakeFs(emptyMap())
            val (_, err, exit) = runWith(listOf("nope.txt"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("No such file or directory"), "stderr was: $err")
        }

    @Test
    fun unknownAlgorithm() =
        runBlocking {
            val (_, err, exit) = runWith(listOf("-a", "999"), stdin = "abc")
            assertEquals(1, exit)
            assertTrue(err.contains("unrecognized algorithm"), "stderr was: $err")
        }

    @Test
    fun unknownOption() =
        runBlocking {
            val (_, err, exit) = runWith(listOf("--bogus"), stdin = "")
            assertEquals(1, exit)
            assertTrue(err.contains("unknown option"), "stderr was: $err")
        }

    @Test
    fun checkPass() =
        runBlocking {
            val fs =
                FakeFs(
                    mapOf(
                        "/work/file.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha256  file.txt\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("file.txt: OK\n", out)
        }

    @Test
    fun checkFail() =
        runBlocking {
            val wrong = "0".repeat(64)
            val fs =
                FakeFs(
                    mapOf(
                        "/work/file.txt" to "abc".toByteArray(),
                        "/work/sums" to "$wrong  file.txt\n".toByteArray(),
                    ),
                )
            val (out, err, exit) = runWith(listOf("-c", "sums"), fs = fs)
            assertEquals(1, exit)
            assertTrue(out.contains("file.txt: FAILED"), "stdout was: $out")
            assertTrue(err.contains("did NOT match"), "stderr was: $err")
        }

    @Test
    fun checkMissingFile() =
        runBlocking {
            val fs =
                FakeFs(
                    mapOf(
                        "/work/sums" to "$abcSha256  ghost.txt\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(listOf("-c", "sums"), fs = fs)
            assertEquals(1, exit)
            assertTrue(out.contains("ghost.txt: FAILED open or read"), "stdout was: $out")
        }

    @Test
    fun checkAlgorithm1() =
        runBlocking {
            val fs =
                FakeFs(
                    mapOf(
                        "/work/file.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha1  file.txt\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(listOf("-a", "1", "-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("file.txt: OK\n", out)
        }

    @Test
    fun checkBinaryMarker() =
        runBlocking {
            val fs =
                FakeFs(
                    mapOf(
                        "/work/file.txt" to "abc".toByteArray(),
                        "/work/sums" to "$abcSha256 *file.txt\n".toByteArray(),
                    ),
                )
            val (out, _, exit) = runWith(listOf("-c", "sums"), fs = fs)
            assertEquals(0, exit)
            assertEquals("file.txt: OK\n", out)
        }

    @Test
    fun streamingLargeInput() =
        runBlocking {
            // 1 MiB of zeros — verify streaming doesn't blow up; compare to a
            // known digest computed independently via MessageDigest.
            val data = ByteArray(1024 * 1024)
            val expected =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(data)
                    .joinToString("") { "%02x".format(it) }
            val fs = FakeFs(mapOf("/work/big" to data))
            val (out, _, exit) = runWith(listOf("big"), fs = fs)
            assertEquals(0, exit)
            assertEquals("$expected  big\n", out)
        }

    @Test
    fun dashFileMeansStdin() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("-"), stdin = "abc")
            assertEquals(0, exit)
            assertEquals("$abcSha256  -\n", out)
        }

    // --- Test harness ---

    private data class Run(
        val stdout: String,
        val stderr: String,
        val exit: Int,
    )

    private fun runWith(
        args: List<String>,
        stdin: String = "",
        fs: FakeFs = FakeFs(emptyMap()),
    ): Run {
        val stdinSrc = BufferSource(Buffer().apply { writeString(stdin) })
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
        val exit = runBlocking { ShaSumCommand().run(args, ctx).exitCode }
        return Run(stdoutBuf.readString(), stderrBuf.readString(), exit)
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

private class FakeFs(
    private val files: Map<String, ByteArray>,
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
    ): SuspendSink = error("not supported in tests")

    override fun mkdirs(
        path: String,
        mode: Int,
    ) = error("not supported in tests")

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) = error("not supported in tests")

    private fun normalize(path: String): String {
        // Simple normalizer — collapse "/work/./foo" or duplicated slashes.
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
