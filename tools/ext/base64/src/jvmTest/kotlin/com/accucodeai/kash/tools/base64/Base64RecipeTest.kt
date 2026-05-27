package com.accucodeai.kash.tools.base64

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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Base64RecipeTest {
    @Test
    fun encodeShortString() =
        runBlocking {
            val (out, _, exit) = runWith(emptyList(), stdin = "hello\n")
            assertEquals(0, exit)
            assertEquals("aGVsbG8K\n", out)
        }

    @Test
    fun roundTripDecode() =
        runBlocking {
            val (out, _, exit) = runWithBytes(listOf("-d"), stdinBytes = "aGVsbG8K\n".toByteArray())
            assertEquals(0, exit)
            assertArrayEquals("hello\n".toByteArray(), out)
        }

    @Test
    fun encodeBinaryHighBit() =
        runBlocking {
            // Bytes 0xFE 0xED 0xFA 0xCE  -> "/u36zg=="
            val bytes = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFA.toByte(), 0xCE.toByte())
            val (out, _, exit) = runWithBytes(emptyList(), stdinBytes = bytes)
            assertEquals(0, exit)
            assertEquals("/u36zg==\n", String(out))
        }

    @Test
    fun decodeRoundTripsBinaryExact() =
        runBlocking {
            val original = ByteArray(256) { it.toByte() }
            // encode it
            val (encoded, _, exit1) = runWithBytes(emptyList(), stdinBytes = original)
            assertEquals(0, exit1)
            // decode it
            val (decoded, _, exit2) = runWithBytes(listOf("-d"), stdinBytes = encoded)
            assertEquals(0, exit2)
            assertArrayEquals(original, decoded)
        }

    @Test
    fun wrapZeroNoWrapping() =
        runBlocking {
            // 100-byte payload encodes to ~136 base64 chars; with -w 0 there
            // should be no embedded newline before the final trailing one.
            val payload = "x".repeat(100)
            val (out, _, exit) = runWith(listOf("-w", "0"), stdin = payload)
            assertEquals(0, exit)
            val newlineCount = out.count { it == '\n' }
            assertEquals(1, newlineCount, "expected single trailing newline, got: $out")
            assertTrue(out.endsWith("\n"))
        }

    @Test
    fun wrapTen() =
        runBlocking {
            // "abcdefghijklmnop" (16 bytes) -> "YWJjZGVmZ2hpamtsbW5vcA==" (24 chars).
            // With -w 10 we expect lines of 10 / 10 / 4 plus trailing newline.
            val (out, _, exit) = runWith(listOf("-w", "10"), stdin = "abcdefghijklmnop")
            assertEquals(0, exit)
            assertEquals("YWJjZGVmZ2\nhpamtsbW5v\ncA==\n", out)
        }

    @Test
    fun defaultWrapAt76() =
        runBlocking {
            // 100 bytes of 'x' -> 136 base64 chars; default wrap of 76 produces
            // a 76-char first line then a 60-char second line.
            val payload = "x".repeat(100)
            val (out, _, exit) = runWith(emptyList(), stdin = payload)
            assertEquals(0, exit)
            val lines = out.split('\n')
            // lines[0] = 76 chars, lines[1] = 60 chars, lines[2] = "" (trailing newline)
            assertEquals(76, lines[0].length, "first line wrong length; out=$out")
            assertEquals(60, lines[1].length, "second line wrong length; out=$out")
            assertEquals("", lines[2])
        }

    @Test
    fun decodeRejectsGarbageByDefault() =
        runBlocking {
            // Embed an exclamation mark — not in alphabet, not whitespace.
            val (_, err, exit) = runWith(listOf("-d"), stdin = "aGVs!bG8K")
            assertEquals(1, exit)
            assertTrue(err.contains("invalid input"), "stderr was: $err")
        }

    @Test
    fun decodeIgnoreGarbage() =
        runBlocking {
            // "hello\n" = "aGVsbG8K"; pepper with embedded junk & newlines.
            val input = "aG Vs\nbG8 K!@#\n"
            val (out, _, exit) = runWithBytes(listOf("-d", "-i"), stdinBytes = input.toByteArray())
            assertEquals(0, exit)
            assertArrayEquals("hello\n".toByteArray(), out)
        }

    @Test
    fun decodeAllowsLineBreaksByDefault() =
        runBlocking {
            // base64 output normally has embedded \n; decode should accept it
            // even without -i (we strip CR/LF in strict mode).
            val input = "YWJj\nZGVm\n"
            val (out, _, exit) = runWithBytes(listOf("-d"), stdinBytes = input.toByteArray())
            assertEquals(0, exit)
            assertArrayEquals("abcdef".toByteArray(), out)
        }

    @Test
    fun fileOperandEncode() =
        runBlocking {
            val fs = FakeFs(mapOf("/work/payload.bin" to "hello\n".toByteArray()))
            val (out, _, exit) = runWith(listOf("payload.bin"), fs = fs)
            assertEquals(0, exit)
            assertEquals("aGVsbG8K\n", out)
        }

    @Test
    fun dashOperandIsStdin() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("-"), stdin = "hello\n")
            assertEquals(0, exit)
            assertEquals("aGVsbG8K\n", out)
        }

    @Test
    fun missingFileReportsError() =
        runBlocking {
            val fs = FakeFs(emptyMap())
            val (_, err, exit) = runWith(listOf("nope.bin"), fs = fs)
            assertEquals(1, exit)
            assertTrue(err.contains("No such file or directory"), "stderr was: $err")
        }

    @Test
    fun longFormDecode() =
        runBlocking {
            val (out, _, exit) = runWithBytes(listOf("--decode"), stdinBytes = "aGVsbG8K\n".toByteArray())
            assertEquals(0, exit)
            assertArrayEquals("hello\n".toByteArray(), out)
        }

    @Test
    fun longFormWrapEquals() =
        runBlocking {
            val (out, _, exit) = runWith(listOf("--wrap=4"), stdin = "abcdef")
            assertEquals(0, exit)
            // "abcdef" -> "YWJjZGVm" (8) -> "YWJj\nZGVm\n"
            assertEquals("YWJj\nZGVm\n", out)
        }

    @Test
    fun unknownOptionUsageError() =
        runBlocking {
            val (_, err, exit) = runWith(listOf("--bogus"), stdin = "")
            assertEquals(2, exit)
            assertTrue(err.contains("unknown option"), "stderr was: $err")
        }

    @Test
    fun invalidWrapSize() =
        runBlocking {
            val (_, err, exit) = runWith(listOf("-w", "foo"), stdin = "")
            assertEquals(2, exit)
            assertTrue(err.contains("invalid wrap size"), "stderr was: $err")
        }

    @Test
    fun emptyInputEncodesToJustNewline() =
        runBlocking {
            val (out, _, exit) = runWith(emptyList(), stdin = "")
            assertEquals(0, exit)
            assertEquals("\n", out)
        }

    @Test
    fun endOfOptionsAllowsDashFile() =
        runBlocking {
            val fs = FakeFs(mapOf("/work/-weird" to "hi".toByteArray()))
            val (out, _, exit) = runWith(listOf("--", "-weird"), fs = fs)
            assertEquals(0, exit)
            assertEquals("aGk=\n", out)
        }

    // --- Test harness ---

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
        val exit = runBlocking { Base64Command().run(args, ctx).exitCode }
        return Run(
            stdoutBuf.readByteArray().toString(Charsets.UTF_8),
            stderrBuf.readByteArray().toString(Charsets.UTF_8),
            exit,
        )
    }

    private fun runWithBytes(
        args: List<String>,
        stdinBytes: ByteArray,
        fs: FakeFs = FakeFs(emptyMap()),
    ): RunBytes {
        val src = Buffer().apply { write(stdinBytes) }
        val stdinSrc = BufferSource(src)
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
        val exit = runBlocking { Base64Command().run(args, ctx).exitCode }
        return RunBytes(
            stdoutBuf.readByteArray(),
            stderrBuf.readByteArray().toString(Charsets.UTF_8),
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
