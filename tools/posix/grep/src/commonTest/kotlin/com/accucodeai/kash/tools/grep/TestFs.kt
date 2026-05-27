package com.accucodeai.kash.tools.grep

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/** Minimal in-memory FS that models files and directories. */
internal class TestFs : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf("/")

    override fun exists(path: String) = files.containsKey(path) || dirs.contains(path)

    override fun isDirectory(path: String) = dirs.contains(path)

    override fun source(path: String): SuspendSource {
        val b = Buffer()
        b.write(files[path] ?: error("no such file: $path"))
        return b.asSuspendSource()
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink =
        object : SuspendSink {
            private val buf = Buffer()

            init {
                if (append) files[path]?.let { buf.write(it) }
            }

            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                buf.write(source, byteCount)
            }

            override suspend fun flush() {
                files[path] = buf.copy().readByteArray()
                ensureParents(path)
            }

            override fun close() {
                files[path] = buf.copy().readByteArray()
                ensureParents(path)
            }
        }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        dirs.add(path)
        ensureParents(path)
    }

    override fun list(path: String): List<String> {
        val base = if (path == "/") "/" else "$path/"
        val out = mutableSetOf<String>()
        for (f in files.keys) {
            if (f.startsWith(base)) {
                val rest = f.substring(base.length)
                val firstSeg = rest.substringBefore('/')
                if (firstSeg.isNotEmpty()) out.add(firstSeg)
            }
        }
        for (d in dirs) {
            if (d.startsWith(base) && d != path) {
                val rest = d.substring(base.length)
                val firstSeg = rest.substringBefore('/')
                if (firstSeg.isNotEmpty()) out.add(firstSeg)
            }
        }
        return out.sorted()
    }

    override fun remove(path: String) {
        files.remove(path)
        dirs.remove(path)
    }

    override suspend fun readBytes(path: String): ByteArray = files[path] ?: error("no such file: $path")

    override suspend fun writeBytes(
        path: String,
        bytes: ByteArray,
        mode: Int,
    ) {
        files[path] = bytes
        ensureParents(path)
    }

    private fun ensureParents(path: String) {
        var i = path.lastIndexOf('/')
        while (i > 0) {
            val parent = path.substring(0, i)
            dirs.add(parent)
            i = parent.lastIndexOf('/')
        }
        dirs.add("/")
    }
}

internal fun ctxFor(stdin: String = ""): Triple<CommandContext, Buffer, Buffer> {
    val inBuf = Buffer().also { it.writeString(stdin) }
    val outBuf = Buffer()
    val errBuf = Buffer()
    val ctx =
        bareCommandContext(
            fs = TestFs(),
            env = mutableMapOf(),
            cwd = "/",
            stdin = inBuf.asSuspendSource(),
            stdout = outBuf.asSuspendSink(),
            stderr = errBuf.asSuspendSink(),
        )
    return Triple(ctx, outBuf, errBuf)
}
