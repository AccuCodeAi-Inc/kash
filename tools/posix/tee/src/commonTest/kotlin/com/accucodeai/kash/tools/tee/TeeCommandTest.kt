package com.accucodeai.kash.tools.tee

import com.accucodeai.kash.api.io.BrokenPipeException
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Capturing in-memory filesystem for tee tests. Each opened sink is a Buffer
 * stored under its path so the test can read back what tee wrote. Truncates by
 * default; with `append = true`, retains previous bytes.
 */
private class CapFs(
    initial: Map<String, String> = emptyMap(),
    private val openFailures: Set<String> = emptySet(),
    private val writeFailures: Set<String> = emptySet(),
) : FileSystem {
    val files: MutableMap<String, Buffer> = mutableMapOf()

    init {
        for ((p, c) in initial) {
            val b = Buffer()
            b.writeString(c)
            files[p] = b
        }
    }

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource {
        val src = files[path] ?: error("missing: $path")
        val copy = Buffer()
        copy.writeString(src.copy().readString())
        return copy.asSuspendSource()
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink {
        if (path in openFailures) throw RuntimeException("permission denied")
        val target = if (append && files.containsKey(path)) files[path]!! else Buffer().also { files[path] = it }
        return object : SuspendSink {
            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                if (path in writeFailures) throw BrokenPipeException()
                target.write(source, byteCount)
            }

            override suspend fun flush() {}

            override fun close() {}
        }
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {
        files.remove(path)
    }

    fun contents(path: String): String = files[path]?.copy()?.readString() ?: ""
}

private fun stdinOf(s: String): Buffer {
    val b = Buffer()
    b.writeString(s)
    return b
}

private suspend fun runTee(
    input: String = "",
    fs: CapFs = CapFs(),
    vararg args: String,
    stdoutSink: SuspendSink? = null,
): Triple<Int, String, String> {
    val outBuf = Buffer()
    val effectiveOut: SuspendSink = stdoutSink ?: outBuf.asSuspendSink()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdinOf(input).asSuspendSource(),
            stdout = effectiveOut,
            stderr = err.asSuspendSink(),
        )
    val res = TeeCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, outBuf.readString(), err.readString())
}

class TeeCommandTest {
    @Test fun `single file gets stdin and stdout echoes it`() =
        runTest {
            val fs = CapFs()
            val (rc, out, _) = runTee("hello\n", fs = fs, args = arrayOf("/a"))
            assertEquals(0, rc)
            assertEquals("hello\n", out)
            assertEquals("hello\n", fs.contents("/a"))
        }

    @Test fun `multiple files all receive input`() =
        runTest {
            val fs = CapFs()
            val (rc, out, _) = runTee("data\n", fs = fs, args = arrayOf("/a", "/b", "/c"))
            assertEquals(0, rc)
            assertEquals("data\n", out)
            assertEquals("data\n", fs.contents("/a"))
            assertEquals("data\n", fs.contents("/b"))
            assertEquals("data\n", fs.contents("/c"))
        }

    @Test fun `a flag appends instead of truncating`() =
        runTest {
            val fs = CapFs(initial = mapOf("/log" to "old\n"))
            val (rc, _, _) = runTee("new\n", fs = fs, args = arrayOf("-a", "/log"))
            assertEquals(0, rc)
            assertEquals("old\nnew\n", fs.contents("/log"))
        }

    @Test fun `without a flag truncates existing file`() =
        runTest {
            val fs = CapFs(initial = mapOf("/log" to "old\n"))
            val (rc, _, _) = runTee("new\n", fs = fs, args = arrayOf("/log"))
            assertEquals(0, rc)
            assertEquals("new\n", fs.contents("/log"))
        }

    @Test fun `file open error mid-list continues with others`() =
        runTest {
            val fs = CapFs(openFailures = setOf("/bad"))
            val (rc, out, err) = runTee("x\n", fs = fs, args = arrayOf("/a", "/bad", "/c"))
            assertEquals(1, rc)
            assertEquals("x\n", out)
            assertEquals("x\n", fs.contents("/a"))
            assertEquals("x\n", fs.contents("/c"))
            assertTrue(err.contains("/bad"), "stderr should mention bad file: $err")
        }

    @Test fun `stdin streaming preserves bytes across chunk boundaries`() =
        runTest {
            // 20 KiB > 8 KiB chunk size; ensures multi-chunk path works.
            val big = buildString { repeat(20 * 1024) { append(('a' + (it % 26))) } }
            val fs = CapFs()
            val (rc, out, _) = runTee(big, fs = fs, args = arrayOf("/big"))
            assertEquals(0, rc)
            assertEquals(big, out)
            assertEquals(big, fs.contents("/big"))
        }

    @Test fun `i flag is accepted silently`() =
        runTest {
            val fs = CapFs()
            val (rc, out, err) = runTee("hi\n", fs = fs, args = arrayOf("-i", "/a"))
            assertEquals(0, rc)
            assertEquals("hi\n", out)
            assertEquals("hi\n", fs.contents("/a"))
            assertEquals("", err)
        }

    @Test fun `bundled -ai flags work`() =
        runTest {
            val fs = CapFs(initial = mapOf("/log" to "x"))
            val (rc, _, _) = runTee("y", fs = fs, args = arrayOf("-ai", "/log"))
            assertEquals(0, rc)
            assertEquals("xy", fs.contents("/log"))
        }

    @Test fun `double dash ends options`() =
        runTest {
            val fs = CapFs()
            val (rc, _, _) = runTee("z\n", fs = fs, args = arrayOf("--", "-a"))
            assertEquals(0, rc)
            // "-a" is the file path here, not a flag.
            assertEquals("z\n", fs.contents("/-a"))
        }

    @Test fun `no file arguments just copies stdin to stdout`() =
        runTest {
            val (rc, out, _) = runTee("solo\n", args = arrayOf())
            assertEquals(0, rc)
            assertEquals("solo\n", out)
        }

    @Test fun `unknown long option exits 2`() =
        runTest {
            val (rc, _, err) = runTee("x", args = arrayOf("--bogus"))
            assertEquals(2, rc)
            assertTrue(err.contains("--bogus"))
        }

    @Test fun `unknown short option exits 2`() =
        runTest {
            val (rc, _, err) = runTee("x", args = arrayOf("-Z"))
            assertEquals(2, rc)
            assertTrue(err.contains("'Z'"))
        }

    @Test fun `broken stdout pipe does not stop file writes`() =
        runTest {
            // Custom stdout sink that throws BrokenPipeException on first write.
            val brokenStdout =
                object : SuspendSink {
                    var writes = 0

                    override suspend fun write(
                        source: Buffer,
                        byteCount: Long,
                    ) {
                        writes++
                        throw BrokenPipeException()
                    }

                    override suspend fun flush() {}

                    override fun close() {}
                }
            val fs = CapFs()
            val err = Buffer()
            val ctx =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/",
                    stdin = stdinOf("payload\n").asSuspendSource(),
                    stdout = brokenStdout,
                    stderr = err.asSuspendSink(),
                )
            val res = TeeCommand().run(listOf("/a"), ctx)
            // POSIX tee survives SIGPIPE on stdout: exit code stays 0 if files succeed.
            assertEquals(0, res.exitCode)
            assertEquals("payload\n", fs.contents("/a"))
            assertFalse(err.readString().isNotEmpty(), "no error expected on stdout broken pipe")
        }

    @Test fun `broken file sink drops only that sink`() =
        runTest {
            val fs = CapFs(writeFailures = setOf("/bad"))
            val (rc, out, _) = runTee("data\n", fs = fs, args = arrayOf("/good", "/bad"))
            assertNotEquals(0, rc)
            assertEquals("data\n", out)
            assertEquals("data\n", fs.contents("/good"))
        }
}
