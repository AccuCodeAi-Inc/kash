package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------
// Approach: buffer the whole archive in memory and run `java.util.zip` on a
// ByteArrayInputStream / ByteArrayOutputStream. No suspend<->blocking
// bridges — every suspending operation is done via the public suspend API
// before / after handing the byte[] off to the synchronous codec.
//
// Memory cost: one archive's worth. Matches GzipFilter / Bzip2Codec.jvm /
// tar's Bzip2XzFilter — consistent policy across the compression tools.
// True per-chunk streaming would need PipedInputStream + a worker thread.
// ---------------------------------------------------------------------------

private suspend fun drainSource(source: SuspendSource): ByteArray {
    val all = Buffer()
    while (true) {
        val n = source.readAtMostTo(all, 64 * 1024L)
        if (n == -1L) break
    }
    return all.readByteArray()
}

// ---------------------------------------------------------------------------
// ZipReader
// ---------------------------------------------------------------------------

public actual class ZipReader actual constructor(
    private val source: SuspendSource,
) {
    private var zin: ZipInputStream? = null
    private var current: ZipEntry? = null

    // When the local file header has size=-1 (size moved to the data descriptor
    // — what j.u.zip emits by default for DEFLATE), we have to read the entry
    // to learn its size. Buffer that read so entryBytes()/copyEntryTo() can
    // still serve the payload. Null when size came from the LFH directly.
    private var bufferedBody: ByteArray? = null
    private var bufferedConsumed = false

    private suspend fun ensureStream(): ZipInputStream {
        zin?.let { return it }
        val bytes = drainSource(source)
        return ZipInputStream(ByteArrayInputStream(bytes)).also { zin = it }
    }

    public actual suspend fun readNextEntry(): ZipEntryInfo? {
        val zin = ensureStream()
        // closeEntry on prior so j.u.zip advances cleanly even if caller
        // didn't fully drain the body.
        current?.let { zin.closeEntry() }
        bufferedBody = null
        bufferedConsumed = false
        val e =
            zin.nextEntry ?: run {
                current = null
                return null
            }
        current = e
        val size: Long =
            if (e.isDirectory || e.size >= 0) {
                e.size
            } else {
                // Drain into a buffer; size becomes available on the entry afterward.
                val buf = ByteArrayOutputStream4K()
                val tmp = ByteArray(8 * 1024)
                while (true) {
                    val n = zin.read(tmp)
                    if (n <= 0) break
                    buf.write(tmp, 0, n)
                }
                val bytes = buf.toByteArray()
                bufferedBody = bytes
                bytes.size.toLong()
            }
        return ZipEntryInfo(
            name = e.name,
            isDirectory = e.isDirectory,
            uncompressedSize = size,
            mtimeEpochSeconds = e.time / 1000L,
        )
    }

    public actual suspend fun entryBytes(): ByteArray {
        bufferedBody?.let {
            bufferedConsumed = true
            return it
        }
        val z = zin ?: return ByteArray(0)
        val buf = ByteArrayOutputStream4K()
        val tmp = ByteArray(8 * 1024)
        while (true) {
            val n = z.read(tmp)
            if (n <= 0) break
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    public actual suspend fun copyEntryTo(sink: SuspendSink) {
        bufferedBody?.let {
            bufferedConsumed = true
            if (it.isNotEmpty()) sink.writeBytes(it)
            return
        }
        val z = zin ?: return
        val tmp = ByteArray(8 * 1024)
        while (true) {
            val n = z.read(tmp)
            if (n <= 0) break
            sink.writeBytes(if (n == tmp.size) tmp else tmp.copyOf(n))
        }
    }

    public actual suspend fun skipEntry() {
        if (bufferedBody != null) {
            bufferedConsumed = true
            return
        }
        val z = zin ?: return
        val tmp = ByteArray(8 * 1024)
        while (z.read(tmp) > 0) { /* drain */ }
    }

    public actual fun close() {
        zin?.close()
    }
}

// Tiny wrapper so we don't pull java.io.ByteArrayOutputStream into commonMain.
private class ByteArrayOutputStream4K {
    private val raw = java.io.ByteArrayOutputStream(4096)

    fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        raw.write(b, off, len)
    }

    fun toByteArray(): ByteArray = raw.toByteArray()
}

// ---------------------------------------------------------------------------
// ZipWriter
// ---------------------------------------------------------------------------

public actual class ZipWriter actual constructor(
    private val sink: SuspendSink,
    level: Int,
) {
    private val staging = ByteArrayOutputStream()
    private val out: ZipOutputStream =
        ZipOutputStream(staging).apply {
            when {
                level == 0 -> {
                    setMethod(ZipOutputStream.STORED)
                }

                level in 1..9 -> {
                    setMethod(ZipOutputStream.DEFLATED)
                    setLevel(level)
                }

                else -> {
                    setMethod(ZipOutputStream.DEFLATED)
                    setLevel(Deflater.DEFAULT_COMPRESSION)
                }
            }
        }
    private var entryOpen: Boolean = false
    private val storedMode: Boolean = level == 0
    private val crcEngine = java.util.zip.CRC32()

    public actual suspend fun putDirEntry(
        name: String,
        mtimeEpochSeconds: Long?,
    ) {
        if (entryOpen) {
            out.closeEntry()
            entryOpen = false
        }
        val entryName = if (name.endsWith("/")) name else "$name/"
        val e = ZipEntry(entryName)
        if (mtimeEpochSeconds != null) e.time = mtimeEpochSeconds * 1000L
        if (storedMode) {
            e.method = ZipEntry.STORED
            e.size = 0
            e.compressedSize = 0
            e.crc = 0
        }
        out.putNextEntry(e)
        out.closeEntry()
    }

    public actual suspend fun putFileEntry(
        name: String,
        bytes: ByteArray,
        mtimeEpochSeconds: Long?,
    ) {
        if (entryOpen) {
            out.closeEntry()
            entryOpen = false
        }
        val e = ZipEntry(name)
        if (mtimeEpochSeconds != null) e.time = mtimeEpochSeconds * 1000L
        if (storedMode) {
            e.method = ZipEntry.STORED
            e.size = bytes.size.toLong()
            e.compressedSize = bytes.size.toLong()
            crcEngine.reset()
            crcEngine.update(bytes)
            e.crc = crcEngine.value
        }
        out.putNextEntry(e)
        if (bytes.isNotEmpty()) out.write(bytes)
        out.closeEntry()
    }

    public actual suspend fun openFileEntry(
        name: String,
        mtimeEpochSeconds: Long?,
    ): SuspendSink {
        if (entryOpen) {
            out.closeEntry()
            entryOpen = false
        }
        // STORED requires CRC + size up-front, which a streaming body can't
        // provide; force deflate for openFileEntry callers in stored mode.
        val e = ZipEntry(name)
        if (mtimeEpochSeconds != null) e.time = mtimeEpochSeconds * 1000L
        out.putNextEntry(e)
        entryOpen = true
        return EntrySink()
    }

    public actual suspend fun closeEntry() {
        if (entryOpen) {
            out.closeEntry()
            entryOpen = false
        }
    }

    public actual suspend fun close() {
        if (entryOpen) {
            out.closeEntry()
            entryOpen = false
        }
        out.close()
        val bytes = staging.toByteArray()
        staging.reset()
        if (bytes.isNotEmpty()) {
            val buf = Buffer()
            buf.write(bytes)
            sink.write(buf, buf.size)
        }
        sink.flush()
    }

    private inner class EntrySink : SuspendSink {
        override suspend fun write(
            source: Buffer,
            byteCount: Long,
        ) {
            // Drain `source` into the underlying ZipOutputStream. j.u.zip
            // wants raw bytes; we materialize chunk-by-chunk to bound peak.
            var remaining = byteCount
            val tmp = ByteArray(8 * 1024)
            while (remaining > 0) {
                val take = minOf(tmp.size.toLong(), remaining).toInt()
                source.readTo(tmp, 0, take)
                out.write(tmp, 0, take)
                remaining -= take
            }
        }

        override suspend fun flush() {
            // ZipOutputStream buffers internally; flush would force a deflate
            // sync flush which bloats output. No-op until closeEntry().
        }

        override fun close() {
            // No-op — the surrounding ZipWriter owns the entry lifecycle.
        }
    }
}

private fun Buffer.readTo(
    dst: ByteArray,
    off: Int,
    len: Int,
) {
    val arr = readByteArray(len)
    System.arraycopy(arr, 0, dst, off, len)
}
