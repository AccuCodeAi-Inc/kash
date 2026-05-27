package com.accucodeai.kash.tools.tar

import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Streaming tar reader. Pulls 512-byte blocks from [source] and yields one
 * entry at a time via [nextEntry] / [readEntryBytes]. Memory footprint is
 * bounded by one block + the current entry's pending content (consumed
 * incrementally by [readEntryBytes]).
 *
 * Supports:
 *  - ustar regular files / directories / symlinks
 *  - GNU `L` long-name and `K` long-link extension headers
 *  - pax `x` extended headers (path / linkpath)
 *  - GNU base-256 binary size encoding
 *
 * Not supported: sparse files (`S` typeflag), multi-volume archives.
 */
public class TarReader(
    private val source: SuspendSource,
) {
    private val pending = Buffer()
    private var atEof = false

    /** Currently in-flight entry's remaining content size (in bytes). */
    private var remaining: Long = 0L

    /** Padding bytes to skip after the current entry's content. */
    private var padding: Int = 0

    /**
     * Read the next entry header, transparently consuming any pax/GNU long-
     * name helper records that prefix it. Returns null at archive end.
     */
    public suspend fun nextEntry(): TarHeader? {
        // Skip the previous entry's leftover content if the caller didn't drain it.
        if (remaining > 0) {
            skipBytes(remaining)
            remaining = 0
        }
        if (padding > 0) {
            skipBytes(padding.toLong())
            padding = 0
        }

        var pendingName: String? = null
        var pendingLink: String? = null
        var paxOverrides: Map<String, String> = emptyMap()

        while (true) {
            val block = readBlock() ?: return null
            if (TarFormat.isZeroBlock(block)) {
                // POSIX archives end with two zero blocks; one is enough to terminate.
                // Try to consume the second; if absent, still terminate.
                readBlock() // best-effort
                return null
            }
            val header = parseHeader(block)
            when (header.typeflag) {
                TarFormat.TF_GNU_LONGNAME -> {
                    pendingName = readPayloadString(header.size)
                }

                TarFormat.TF_GNU_LONGLINK -> {
                    pendingLink = readPayloadString(header.size)
                }

                TarFormat.TF_PAX_EXT, TarFormat.TF_PAX_GLOBAL -> {
                    val payload = readPayloadBytes(header.size)
                    paxOverrides = paxOverrides + parsePax(payload)
                }

                else -> {
                    remaining = header.size
                    padding =
                        ((TarFormat.BLOCK_SIZE - (header.size % TarFormat.BLOCK_SIZE)) % TarFormat.BLOCK_SIZE).toInt()
                    var name = pendingName ?: paxOverrides["path"] ?: header.path
                    val link = pendingLink ?: paxOverrides["linkpath"] ?: header.linkname
                    val size =
                        paxOverrides["size"]?.toLongOrNull() ?: run {
                            if (header.typeflag == TarFormat.TF_DIR) 0L else header.size
                        }
                    if (size != header.size) {
                        remaining = size
                        padding =
                            ((TarFormat.BLOCK_SIZE - (size % TarFormat.BLOCK_SIZE)) % TarFormat.BLOCK_SIZE).toInt()
                    }
                    // Normalize directory typeflag by trailing-slash legacy form.
                    var tflag = header.typeflag
                    if (tflag == TarFormat.TF_REGULAR_OLD && name.endsWith("/")) {
                        tflag = TarFormat.TF_DIR
                    }
                    if (name.endsWith("/") && tflag == TarFormat.TF_DIR) {
                        name = name.trimEnd('/')
                    }
                    return header.copy(
                        name = name,
                        linkname = link,
                        size = size,
                        typeflag = tflag,
                        prefix = "",
                    )
                }
            }
        }
    }

    /**
     * Read up to [byteCount] bytes of the current entry's content into [sink].
     * Returns the count read, or -1 at end of entry.
     */
    public suspend fun readEntryBytes(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (remaining <= 0) return -1L
        val want = minOf(byteCount, remaining)
        val got = readInto(sink, want)
        remaining -= got
        if (remaining == 0L && padding > 0) {
            skipBytes(padding.toLong())
            padding = 0
        }
        return got
    }

    /** Slurp the rest of the current entry as a byte array. */
    public suspend fun readEntryFully(): ByteArray {
        val buf = Buffer()
        while (true) {
            val n = readEntryBytes(buf, 8 * 1024L)
            if (n == -1L) break
        }
        return buf.readByteArray()
    }

    private suspend fun readBlock(): ByteArray? {
        val buf = Buffer()
        val n = readInto(buf, TarFormat.BLOCK_SIZE.toLong())
        if (n == 0L) return null
        if (n < TarFormat.BLOCK_SIZE) {
            // Truncated trailing partial block — treat as EOF.
            return null
        }
        return buf.readByteArray(TarFormat.BLOCK_SIZE)
    }

    private suspend fun readInto(
        sink: Buffer,
        want: Long,
    ): Long {
        var produced = 0L
        while (produced < want) {
            if (pending.size > 0) {
                val take = minOf(want - produced, pending.size)
                pending.readAtMostTo(sink, take)
                produced += take
                continue
            }
            if (atEof) break
            val n = source.readAtMostTo(pending, 8 * 1024L)
            if (n == -1L) {
                atEof = true
                break
            }
        }
        return produced
    }

    private suspend fun skipBytes(n: Long) {
        var remaining = n
        val scratch = Buffer()
        while (remaining > 0) {
            val got = readInto(scratch, minOf(remaining, 8 * 1024L))
            if (got == 0L) break
            scratch.clear()
            remaining -= got
        }
    }

    private suspend fun readPayloadBytes(size: Long): ByteArray {
        val buf = Buffer()
        var left = size
        while (left > 0) {
            val got = readInto(buf, left)
            if (got == 0L) break
            left -= got
        }
        // Consume block padding.
        val pad = ((TarFormat.BLOCK_SIZE - (size % TarFormat.BLOCK_SIZE)) % TarFormat.BLOCK_SIZE).toInt()
        if (pad > 0) skipBytes(pad.toLong())
        return buf.readByteArray()
    }

    private suspend fun readPayloadString(size: Long): String {
        val bytes = readPayloadBytes(size)
        // GNU L/K payload is NUL-terminated.
        var end = bytes.size
        while (end > 0 && bytes[end - 1].toInt() == 0) end--
        val chars = CharArray(end) { i -> (bytes[i].toInt() and 0xff).toChar() }
        return chars.concatToString()
    }

    private fun parseHeader(block: ByteArray): TarHeader {
        val name = TarFormat.decodeString(block, 0, 100)
        val mode = TarFormat.decodeOctal(block, 100, 8).toInt()
        val uid = TarFormat.decodeOctal(block, 108, 8).toInt()
        val gid = TarFormat.decodeOctal(block, 116, 8).toInt()
        val size = TarFormat.decodeOctal(block, 124, 12)
        val mtime = TarFormat.decodeOctal(block, 136, 12)
        val tflag = block[156]
        val linkname = TarFormat.decodeString(block, 157, 100)
        val magic = TarFormat.decodeString(block, 257, 6)
        val uname = TarFormat.decodeString(block, 265, 32)
        val gname = TarFormat.decodeString(block, 297, 32)
        val prefix = if (magic.startsWith("ustar")) TarFormat.decodeString(block, 345, 155) else ""
        return TarHeader(
            name = name,
            mode = mode,
            uid = uid,
            gid = gid,
            size = size,
            mtime = mtime,
            typeflag = tflag,
            linkname = linkname,
            uname = uname,
            gname = gname,
            prefix = prefix,
        )
    }

    /** Parse a pax extended-header payload into a map of key→value. */
    private fun parsePax(payload: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        while (i < payload.size) {
            // Format: "<len> <key>=<value>\n"
            var sp = i
            while (sp < payload.size && payload[sp] != ' '.code.toByte()) sp++
            if (sp >= payload.size) break
            val len = (payload.sliceArray(i until sp).toAsciiString()).toIntOrNull() ?: break
            if (len <= 0 || i + len > payload.size) break
            val recordEnd = i + len
            // record is payload[i..recordEnd) and ends with '\n'
            val kvStart = sp + 1
            var eq = kvStart
            while (eq < recordEnd && payload[eq] != '='.code.toByte()) eq++
            if (eq >= recordEnd) {
                i = recordEnd
                continue
            }
            val key = payload.sliceArray(kvStart until eq).toAsciiString()
            // value runs to recordEnd - 1 (drop trailing '\n')
            val value = payload.sliceArray((eq + 1) until (recordEnd - 1)).toAsciiString()
            result[key] = value
            i = recordEnd
        }
        return result
    }

    private fun ByteArray.toAsciiString(): String {
        val chars = CharArray(size) { i -> (this[i].toInt() and 0xff).toChar() }
        return chars.concatToString()
    }
}
