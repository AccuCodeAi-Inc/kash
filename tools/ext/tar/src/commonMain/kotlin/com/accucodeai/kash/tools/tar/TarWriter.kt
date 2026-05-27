package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer

/**
 * Streaming tar writer. Emits ustar-format archives with GNU `L` long-name
 * extension records when a path exceeds the 100-byte name field (or 100-byte
 * linkname for symlinks). Caller drives by calling [writeRegularFile] /
 * [writeDirectory] / [writeSymlink] per entry, then [finish] once at end.
 *
 * Content is streamed in chunks; no in-memory file copies. Block padding is
 * emitted to round each entry's content to a 512-byte multiple.
 */
public class TarWriter(
    private val sink: SuspendSink,
) {
    private val zeros = ByteArray(TarFormat.BLOCK_SIZE)
    private var finished = false

    public suspend fun writeDirectory(
        path: String,
        mode: Int = 0b111_101_101,
        mtime: Long = 0L,
        uname: String = "user",
        gname: String = "user",
    ) {
        val name = if (path.endsWith("/")) path else "$path/"
        writeHeaderWithLongName(
            name = name,
            mode = mode,
            size = 0L,
            mtime = mtime,
            typeflag = TarFormat.TF_DIR,
            linkname = "",
            uname = uname,
            gname = gname,
        )
    }

    public suspend fun writeSymlink(
        path: String,
        target: String,
        mode: Int = 0b111_111_111,
        mtime: Long = 0L,
        uname: String = "user",
        gname: String = "user",
    ) {
        // Long-link helper first if target > 100 bytes.
        if (target.encodeToByteArray().size > 100) {
            emitLongHelper(TarFormat.TF_GNU_LONGLINK, target)
        }
        writeHeaderWithLongName(
            name = path,
            mode = mode,
            size = 0L,
            mtime = mtime,
            typeflag = TarFormat.TF_SYMLINK,
            linkname = target.take(100),
            uname = uname,
            gname = gname,
        )
    }

    /**
     * Write a regular file's header and stream [size] bytes of content from
     * [content]. Caller must guarantee [content] yields exactly [size] bytes;
     * a short source is padded with NUL (tar requires the declared length).
     */
    public suspend fun writeRegularFile(
        path: String,
        size: Long,
        content: SuspendSource,
        mode: Int = 0b110_100_100,
        mtime: Long = 0L,
        uname: String = "user",
        gname: String = "user",
    ) {
        writeHeaderWithLongName(
            name = path,
            mode = mode,
            size = size,
            mtime = mtime,
            typeflag = TarFormat.TF_REGULAR,
            linkname = "",
            uname = uname,
            gname = gname,
        )
        // Stream content.
        val buf = Buffer()
        var remaining = size
        while (remaining > 0) {
            val want = minOf(remaining, 8 * 1024L)
            val n = content.readAtMostTo(buf, want)
            if (n == -1L) break
            sink.write(buf, buf.size)
            remaining -= n
        }
        // Pad short reads.
        if (remaining > 0) {
            writeZeros(remaining)
        }
        // Block padding to 512-byte boundary.
        val pad = ((TarFormat.BLOCK_SIZE - (size % TarFormat.BLOCK_SIZE)) % TarFormat.BLOCK_SIZE).toInt()
        if (pad > 0) writeZeros(pad.toLong())
        sink.flush()
    }

    /** Write the trailing two zero blocks. Idempotent. */
    public suspend fun finish() {
        if (finished) return
        writeZeros((TarFormat.BLOCK_SIZE * 2).toLong())
        sink.flush()
        finished = true
    }

    private suspend fun writeHeaderWithLongName(
        name: String,
        mode: Int,
        size: Long,
        mtime: Long,
        typeflag: Byte,
        linkname: String,
        uname: String,
        gname: String,
    ) {
        val nameBytes = name.encodeToByteArray()
        if (nameBytes.size > 100) {
            // Try ustar prefix+name split first (saves a block).
            val split = trySplitUstar(name)
            if (split != null) {
                val header =
                    buildUstarHeader(
                        name = split.second,
                        mode = mode,
                        uid = 0,
                        gid = 0,
                        size = size,
                        mtime = mtime,
                        typeflag = typeflag,
                        linkname = linkname,
                        uname = uname,
                        gname = gname,
                        prefix = split.first,
                    )
                writeBytes(header)
                return
            }
            emitLongHelper(TarFormat.TF_GNU_LONGNAME, name)
        }
        val header =
            buildUstarHeader(
                name = if (nameBytes.size > 100) name.take(100) else name,
                mode = mode,
                uid = 0,
                gid = 0,
                size = size,
                mtime = mtime,
                typeflag = typeflag,
                linkname = linkname,
                uname = uname,
                gname = gname,
            )
        writeBytes(header)
    }

    private fun trySplitUstar(name: String): Pair<String, String>? {
        // ustar: prefix (155) + "/" + name (100). Find a split point at a
        // path separator where both halves fit.
        val bytes = name.encodeToByteArray()
        if (bytes.size > 255) return null // can't fit even with the slash.
        // Walk backwards: largest prefix that still leaves name ≤ 100.
        var i = bytes.size - 1
        while (i >= 0) {
            if (bytes[i] == '/'.code.toByte()) {
                val prefixLen = i
                val nameLen = bytes.size - i - 1
                if (prefixLen in 1..155 && nameLen in 1..100) {
                    val prefix = name.substring(0, i)
                    val rest = name.substring(i + 1)
                    return prefix to rest
                }
            }
            i--
        }
        return null
    }

    private suspend fun emitLongHelper(
        typeflag: Byte,
        longName: String,
    ) {
        val payload = longName.encodeToByteArray() + ByteArray(1) // trailing NUL
        val header =
            buildUstarHeader(
                name = "././@LongLink",
                mode = 0,
                uid = 0,
                gid = 0,
                size = payload.size.toLong(),
                mtime = 0L,
                typeflag = typeflag,
                uname = "root",
                gname = "root",
            )
        writeBytes(header)
        writeBytes(payload)
        val pad = ((TarFormat.BLOCK_SIZE - (payload.size % TarFormat.BLOCK_SIZE)) % TarFormat.BLOCK_SIZE)
        if (pad > 0) writeZeros(pad.toLong())
    }

    private suspend fun writeBytes(bytes: ByteArray) {
        val buf = Buffer()
        buf.write(bytes)
        sink.write(buf, buf.size)
    }

    private suspend fun writeZeros(n: Long) {
        var remaining = n
        val buf = Buffer()
        while (remaining > 0) {
            val take = minOf(remaining, zeros.size.toLong()).toInt()
            buf.write(zeros, 0, take)
            sink.write(buf, buf.size)
            remaining -= take
        }
    }
}
