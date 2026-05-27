package com.accucodeai.kash.tools.dd

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class MemFs(
    initial: Map<String, ByteArray> = emptyMap(),
) : FileSystem {
    val files: MutableMap<String, ByteArray> = mutableMapOf<String, ByteArray>().apply { putAll(initial) }

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource {
        val data = files[path] ?: error("missing: $path")
        val b = Buffer().also { it.write(data) }
        return b.asSuspendSource()
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink {
        val collector = Buffer()
        if (append && files.containsKey(path)) collector.write(files[path]!!)
        return object : SuspendSink {
            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                collector.write(source, byteCount)
            }

            override suspend fun flush() {}

            override fun close() {
                files[path] = collector.copy().readByteArray()
            }
        }
    }

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ) {
        files[path] = bytes.copyOf()
    }

    override suspend fun appendBytes(
        path: String,
        bytes: ByteArray,
    ) {
        val cur = files[path] ?: ByteArray(0)
        files[path] = cur + bytes
    }

    override suspend fun readBytes(path: String): ByteArray = files[path]?.copyOf() ?: error("missing: $path")

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {
        files.remove(path)
    }
}

private fun stdinOf(bytes: ByteArray): Buffer {
    val b = Buffer()
    b.write(bytes)
    return b
}

private suspend fun runDd(
    input: ByteArray = ByteArray(0),
    fs: MemFs = MemFs(),
    vararg args: String,
): Triple<Int, ByteArray, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = stdinOf(input).asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = DdCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readByteArray(), err.readString())
}

class DdCommandTest {
    @Test fun `simple file copy round trip`() =
        runTest {
            val data = "hello world\n".encodeToByteArray()
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=4096"))
            assertEquals(0, rc)
            assertContentEquals(data, fs.files["/out"])
        }

    @Test fun `count=2 bs=512 copies 1024 bytes`() =
        runTest {
            val data = ByteArray(2048) { (it and 0xFF).toByte() }
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512", "count=2"))
            assertEquals(0, rc)
            assertContentEquals(data.copyOfRange(0, 1024), fs.files["/out"])
        }

    @Test fun `skip=2 bs=512 skips first 1024 bytes`() =
        runTest {
            val data = ByteArray(2048) { (it and 0xFF).toByte() }
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512", "skip=2"))
            assertEquals(0, rc)
            assertContentEquals(data.copyOfRange(1024, 2048), fs.files["/out"])
        }

    @Test fun `seek=2 bs=512 writes at offset 1024 with zero padding`() =
        runTest {
            val data = "DATA".encodeToByteArray()
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512", "seek=2"))
            assertEquals(0, rc)
            val out = fs.files["/out"]!!
            assertEquals(1024 + 4, out.size)
            for (i in 0 until 1024) assertEquals(0.toByte(), out[i])
            assertContentEquals(data, out.copyOfRange(1024, 1028))
        }

    @Test fun `conv ucase upcases ASCII`() =
        runTest {
            val fs = MemFs(mapOf("/in" to "hello World!".encodeToByteArray()))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "conv=ucase"))
            assertEquals(0, rc)
            assertEquals("HELLO WORLD!", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `conv lcase downcases ASCII`() =
        runTest {
            val fs = MemFs(mapOf("/in" to "HELLO".encodeToByteArray()))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "conv=lcase"))
            assertEquals(0, rc)
            assertEquals("hello", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `conv swab swaps byte pairs`() =
        runTest {
            val fs = MemFs(mapOf("/in" to byteArrayOf(1, 2, 3, 4)))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "conv=swab"))
            assertEquals(0, rc)
            assertContentEquals(byteArrayOf(2, 1, 4, 3), fs.files["/out"])
        }

    @Test fun `conv notrunc preserves trailing bytes of larger preexisting file`() =
        runTest {
            val fs =
                MemFs(
                    mapOf(
                        "/in" to "NEW".encodeToByteArray(),
                        "/out" to "ORIGINAL-LONG-CONTENT".encodeToByteArray(),
                    ),
                )
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "conv=notrunc"))
            assertEquals(0, rc)
            // "NEW" overwrites first 3 bytes; rest preserved.
            assertEquals("NEWGINAL-LONG-CONTENT", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `without notrunc output is truncated`() =
        runTest {
            val fs =
                MemFs(
                    mapOf(
                        "/in" to "NEW".encodeToByteArray(),
                        "/out" to "ORIGINAL-LONG-CONTENT".encodeToByteArray(),
                    ),
                )
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out"))
            assertEquals(0, rc)
            assertEquals("NEW", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `default reads stdin when no if`() =
        runTest {
            val fs = MemFs()
            val (rc, _, _) = runDd(input = "stdin-data".encodeToByteArray(), fs = fs, args = arrayOf("of=/out"))
            assertEquals(0, rc)
            assertEquals("stdin-data", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `default writes to stdout when no of`() =
        runTest {
            val fs = MemFs(mapOf("/in" to "abc".encodeToByteArray()))
            val (rc, out, _) = runDd(fs = fs, args = arrayOf("if=/in"))
            assertEquals(0, rc)
            assertEquals("abc", out.decodeToString())
        }

    @Test fun `summary on stderr by default`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(1024)))
            val (rc, _, err) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512"))
            assertEquals(0, rc)
            assertTrue(err.contains("2+0 records in"), "stderr was: $err")
            assertTrue(err.contains("2+0 records out"), "stderr was: $err")
            assertTrue(err.contains("1024 bytes"), "stderr was: $err")
        }

    @Test fun `partial last block reports +1`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(1500)))
            val (rc, _, err) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512"))
            assertEquals(0, rc)
            // 1500 = 2 full 512 + 476 partial
            assertTrue(err.contains("2+1 records in"), "stderr was: $err")
        }

    @Test fun `status=none suppresses summary`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(1024)))
            val (rc, _, err) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "status=none"))
            assertEquals(0, rc)
            assertEquals("", err)
        }

    @Test fun `status=noxfer suppresses bytes line only`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(512)))
            val (rc, _, err) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "status=noxfer"))
            assertEquals(0, rc)
            assertTrue(err.contains("records in"), "stderr was: $err")
            assertTrue(err.contains("records out"), "stderr was: $err")
            assertTrue(!err.contains("bytes ("), "should not contain bytes line: $err")
        }

    @Test fun `invalid operand exits 2`() =
        runTest {
            val (rc, _, err) = runDd(args = arrayOf("bogus=1"))
            assertEquals(2, rc)
            assertTrue(err.contains("bogus"))
        }

    @Test fun `bs=garbage exits 2`() =
        runTest {
            val (rc, _, err) = runDd(args = arrayOf("bs=garbage"))
            assertEquals(2, rc)
            assertTrue(err.contains("dd:"))
        }

    @Test fun `conv ascii not implemented errors`() =
        runTest {
            val (rc, _, err) =
                runDd(
                    fs = MemFs(mapOf("/in" to ByteArray(0))),
                    args = arrayOf("if=/in", "conv=ascii"),
                )
            assertEquals(1, rc)
            assertTrue(err.contains("not implemented"))
        }

    @Test fun `oflag=append appends to existing file`() =
        runTest {
            val fs =
                MemFs(
                    mapOf(
                        "/in" to "NEW\n".encodeToByteArray(),
                        "/log" to "OLD\n".encodeToByteArray(),
                    ),
                )
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/log", "oflag=append"))
            assertEquals(0, rc)
            assertEquals("OLD\nNEW\n", fs.files["/log"]!!.decodeToString())
        }

    @Test fun `bs=1k copies 1024 bytes`() =
        runTest {
            val data = ByteArray(3 * 1024) { (it and 0xFF).toByte() }
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=1k", "count=1"))
            assertEquals(0, rc)
            assertContentEquals(data.copyOfRange(0, 1024), fs.files["/out"])
        }

    @Test fun `conv sync pads partial block to ibs`() =
        runTest {
            // 100 bytes input with ibs=512 + conv=sync → 512 bytes padded with NUL.
            val data = ByteArray(100) { 'A'.code.toByte() }
            val fs = MemFs(mapOf("/in" to data))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "ibs=512", "obs=512", "conv=sync"))
            assertEquals(0, rc)
            val out = fs.files["/out"]!!
            assertEquals(512, out.size)
            for (i in 0 until 100) assertEquals('A'.code.toByte(), out[i])
            for (i in 100 until 512) assertEquals(0.toByte(), out[i])
        }

    @Test fun `conv block pads variable lines to cbs with spaces`() =
        runTest {
            val fs = MemFs(mapOf("/in" to "ab\ncde\n".encodeToByteArray()))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "cbs=4", "conv=block"))
            assertEquals(0, rc)
            assertEquals("ab  cde ", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `conv unblock strips trailing spaces and adds newlines`() =
        runTest {
            val fs = MemFs(mapOf("/in" to "ab  cde ".encodeToByteArray()))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "cbs=4", "conv=unblock"))
            assertEquals(0, rc)
            assertEquals("ab\ncde\n", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `conv block without cbs errors`() =
        runTest {
            val (rc, _, err) = runDd(fs = MemFs(mapOf("/in" to ByteArray(0))), args = arrayOf("if=/in", "conv=block"))
            assertEquals(2, rc)
            assertTrue(err.contains("cbs"))
        }

    @Test fun `help flag prints usage and exits 0`() =
        runTest {
            val (rc, out, _) = runDd(args = arrayOf("--help"))
            assertEquals(0, rc)
            assertTrue(out.decodeToString().contains("usage: dd"))
        }

    @Test fun `version flag prints and exits 0`() =
        runTest {
            val (rc, out, _) = runDd(args = arrayOf("--version"))
            assertEquals(0, rc)
            assertTrue(out.decodeToString().contains("dd"))
        }

    @Test fun `pipeline ucase then swab combined`() =
        runTest {
            // ucase then swab: "ab" -> "AB" -> "BA"
            val fs = MemFs(mapOf("/in" to "ab".encodeToByteArray()))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "conv=ucase,swab"))
            assertEquals(0, rc)
            // POSIX order: swab applied first, then ucase. "ab" -> "ba" -> "BA"
            assertEquals("BA", fs.files["/out"]!!.decodeToString())
        }

    @Test fun `skip past end yields empty output`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(100)))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512", "skip=1"))
            assertEquals(0, rc)
            assertEquals(0, fs.files["/out"]!!.size)
        }

    @Test fun `count zero produces empty output`() =
        runTest {
            val fs = MemFs(mapOf("/in" to ByteArray(1024)))
            val (rc, _, _) = runDd(fs = fs, args = arrayOf("if=/in", "of=/out", "bs=512", "count=0"))
            assertEquals(0, rc)
            assertEquals(0, fs.files["/out"]!!.size)
        }

    @Test fun `recipe wipe 1KiB to stdout`() =
        runTest {
            // Like `dd if=/dev/zero bs=1024 count=1` — we feed zeros via /in.
            val fs = MemFs(mapOf("/zero" to ByteArray(4096)))
            val (rc, out, _) = runDd(fs = fs, args = arrayOf("if=/zero", "bs=1024", "count=1", "status=none"))
            assertEquals(0, rc)
            assertEquals(1024, out.size)
            for (b in out) assertEquals(0.toByte(), b)
        }
}
