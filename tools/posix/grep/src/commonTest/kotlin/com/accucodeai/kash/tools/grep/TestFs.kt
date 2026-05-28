package com.accucodeai.kash.tools.grep

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString

/** Minimal in-memory FS that models files, directories, and (one-hop) symlinks. */
internal open class TestFs : FileSystem {
    private val files = mutableMapOf<String, ByteArray>()
    private val dirs = mutableSetOf("/")

    /** path → target. One-hop resolution is enough for the recursion tests. */
    private val symlinks = mutableMapOf<String, String>()

    /** Test helper: register a symlink at [link] pointing at [target]. */
    fun addSymlink(
        link: String,
        target: String,
    ) {
        symlinks[link] = target
        ensureParents(link)
    }

    override fun statLink(path: String): FileStat =
        if (symlinks.containsKey(path)) {
            FileStat(
                path = path,
                type = FileType.SYMLINK,
                size = 0,
                mtimeEpochSeconds = 0,
                mode = 0,
                symlinkTarget = symlinks[path],
            )
        } else {
            stat(path)
        }

    override fun readSymlink(path: String): String = symlinks[path] ?: error("not a symlink: $path")

    override fun exists(path: String) = files.containsKey(path) || dirs.contains(path) || symlinks.containsKey(path)

    override fun isDirectory(path: String) = dirs.contains(path) || (symlinks[path]?.let { dirs.contains(it) } ?: false)

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
        // Follow a symlinked directory one hop to its target.
        val real = symlinks[path] ?: path
        val base = if (real == "/") "/" else "$real/"
        val out = mutableSetOf<String>()
        for (key in files.keys + dirs + symlinks.keys) {
            if (key.startsWith(base) && key != real) {
                val firstSeg = key.substring(base.length).substringBefore('/')
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

internal fun ctxFor(stdin: String = ""): Triple<CommandContext, Buffer, Buffer> = ctxForFs(TestFs(), stdin)

internal fun ctxForFs(
    fs: FileSystem,
    stdin: String = "",
): Triple<CommandContext, Buffer, Buffer> {
    val inBuf = Buffer().also { it.writeString(stdin) }
    val outBuf = Buffer()
    val errBuf = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = "/",
            stdin = inBuf.asSuspendSource(),
            stdout = outBuf.asSuspendSink(),
            stderr = errBuf.asSuspendSink(),
        )
    return Triple(ctx, outBuf, errBuf)
}
