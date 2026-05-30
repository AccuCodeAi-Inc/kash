package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.hash.sha1

/**
 * `.git/index` — the staging area. Binary, big-endian, v2 layout per
 * [git's documentation](https://git-scm.com/docs/index-format).
 *
 * What we encode: a sorted list of [IndexEntry]s (by name, then stage),
 * followed by a 20-byte SHA-1 over every preceding byte. No extensions
 * on write — the `TREE`/`REUC`/`link` extensions are advisory and real
 * git rebuilds them when needed.
 *
 * What we tolerate on read: any extension chunk is skipped using its
 * declared length. The trailing checksum is verified; mismatch throws.
 *
 * Field strategy: ctime/mtime/dev/ino/uid/gid default to 0. Real git
 * just treats that as "stat-dirty" — `git status` will say a file is
 * modified until you `git add` it again — which is the right
 * conservative behavior for synthetic/seeded indices.
 */
public data class IndexEntry(
    val ctimeSec: Int = 0,
    val ctimeNanos: Int = 0,
    val mtimeSec: Int = 0,
    val mtimeNanos: Int = 0,
    val dev: Int = 0,
    val ino: Int = 0,
    val mode: FileMode,
    val uid: Int = 0,
    val gid: Int = 0,
    val size: Int,
    val sha: String,
    val stage: Int = 0,
    val assumeValid: Boolean = false,
    val path: String,
) {
    init {
        require(sha.length == 40) { "sha must be 40 hex chars" }
        require(stage in 0..3) { "stage must be 0..3, got $stage" }
        require(path.isNotEmpty()) { "index path must be non-empty" }
        require(!path.startsWith("/")) { "index path must be relative" }
    }
}

public class IndexFile(
    public val version: Int,
    public val entries: List<IndexEntry>,
) {
    init {
        require(version == 2) { "only index v2 supported on write, got v$version" }
    }

    public companion object {
        public const val SIGNATURE: String = "DIRC"
    }
}

/**
 * The four mode words git's *index* uses — same shape as the wire mode
 * for tree entries but encoded as 32-bit BE integers (vs. the ASCII
 * octal form trees use). We re-use [FileMode] for the wire/octal form
 * and convert when needed.
 */
private fun FileMode.toIndexMode(): Int =
    when (this) {
        FileMode.REGULAR -> 0x000081A4
        FileMode.EXECUTABLE -> 0x000081ED
        FileMode.SYMLINK -> 0x0000A000
        FileMode.GITLINK -> 0x0000E000
        FileMode.TREE -> error("index entries cannot be trees")
    }

private fun indexModeToFileMode(m: Int): FileMode =
    when (m) {
        0x000081A4 -> FileMode.REGULAR
        0x000081ED -> FileMode.EXECUTABLE
        0x0000A000 -> FileMode.SYMLINK
        0x0000E000 -> FileMode.GITLINK
        else -> error("unsupported index mode: ${m.toString(16)}")
    }

/**
 * Encode [file] to the on-disk byte form (v2). Trailing SHA-1 is
 * computed and appended.
 */
public fun encodeIndex(file: IndexFile): ByteArray {
    val out = ByteArrayBuilder()
    out.writeAscii(IndexFile.SIGNATURE)
    out.writeIntBe(file.version)
    out.writeIntBe(file.entries.size)

    val sorted = file.entries.sortedWith(compareBy({ it.path }, { it.stage }))
    for (e in sorted) {
        val start = out.size
        out.writeIntBe(e.ctimeSec)
        out.writeIntBe(e.ctimeNanos)
        out.writeIntBe(e.mtimeSec)
        out.writeIntBe(e.mtimeNanos)
        out.writeIntBe(e.dev)
        out.writeIntBe(e.ino)
        out.writeIntBe(e.mode.toIndexMode())
        out.writeIntBe(e.uid)
        out.writeIntBe(e.gid)
        out.writeIntBe(e.size)
        out.writeBytes(hexToBytes20(e.sha))
        val nameBytes = e.path.encodeToByteArray()
        val nameLen = minOf(nameBytes.size, 0xFFF)
        var flags = nameLen
        flags = flags or (e.stage shl 12)
        if (e.assumeValid) flags = flags or 0x8000
        out.writeShortBe(flags)
        out.writeBytes(nameBytes)
        // Pad with NULs so the entry length is a multiple of 8, with at
        // least one NUL (the terminator).
        val entryLen = out.size - start
        val pad = 8 - (entryLen % 8)
        // pad >= 1; if entryLen % 8 == 0, pad is 8 (still need one NUL).
        out.writeZeros(pad)
    }

    val body = out.toByteArray()
    val checksum = sha1(body)
    return body + checksum
}

/**
 * Build a fresh-checkout index from [flat] — one stage-0 entry per file,
 * with `sha`/`size` matching the file's blob. This is the index a real
 * `git clone` / `git checkout` leaves behind: it equals HEAD, so `git status`
 * / `git diff` against an *untouched* working tree report clean.
 *
 * Stat fields (ctime/mtime/dev/ino/…) stay 0. kash's status compares the
 * content sha (`blobSha(workTreeBytes)` vs the entry sha), not stat, so a
 * pristine seed still reads as unmodified — see `porcelain/Status.kt`.
 */
public fun indexFromFlatTree(flat: FlatTree): IndexFile =
    IndexFile(
        version = 2,
        entries =
            flat.map { (path, file) ->
                IndexEntry(
                    mode = if (file.executable) FileMode.EXECUTABLE else FileMode.REGULAR,
                    size = file.bytes.size,
                    sha = blobSha(file.bytes),
                    path = path,
                )
            },
    )

public fun decodeIndex(bytes: ByteArray): IndexFile {
    require(bytes.size >= 32) { "index too short: ${bytes.size} bytes" }
    val body = bytes.copyOfRange(0, bytes.size - 20)
    val trailing = bytes.copyOfRange(bytes.size - 20, bytes.size)
    val expected = sha1(body)
    require(trailing.contentEquals(expected)) { "index checksum mismatch" }

    val r = ByteArrayReader(body)
    require(r.readAscii(4) == IndexFile.SIGNATURE) { "bad index signature" }
    val version = r.readIntBe()
    require(version == 2) { "only index v2 supported, got v$version" }
    val count = r.readIntBe()

    val entries = mutableListOf<IndexEntry>()
    repeat(count) {
        val entryStart = r.pos
        val ctimeSec = r.readIntBe()
        val ctimeNanos = r.readIntBe()
        val mtimeSec = r.readIntBe()
        val mtimeNanos = r.readIntBe()
        val dev = r.readIntBe()
        val ino = r.readIntBe()
        val mode = r.readIntBe()
        val uid = r.readIntBe()
        val gid = r.readIntBe()
        val size = r.readIntBe()
        val rawSha = r.readBytes(20)
        val flags = r.readShortBe()
        val nameLen = flags and 0x0FFF
        val stage = (flags ushr 12) and 0x3
        val assumeValid = (flags and 0x8000) != 0
        // v3 extended flag (0x4000) — not used in v2 writes; tolerated
        // on read by skipping the extra 2 bytes if set.
        val nameBytes =
            if (nameLen < 0xFFF) {
                r.readBytes(nameLen)
            } else {
                // Long name: read until NUL.
                val collected = mutableListOf<Byte>()
                while (true) {
                    val b = r.readBytes(1)[0]
                    if (b == 0.toByte()) {
                        r.pos -= 1
                        break
                    }
                    collected += b
                }
                collected.toByteArray()
            }
        // Advance past the NUL + padding.
        val entryLen = r.pos - entryStart
        val pad = 8 - (entryLen % 8)
        r.readBytes(pad)
        entries +=
            IndexEntry(
                ctimeSec = ctimeSec,
                ctimeNanos = ctimeNanos,
                mtimeSec = mtimeSec,
                mtimeNanos = mtimeNanos,
                dev = dev,
                ino = ino,
                mode = indexModeToFileMode(mode),
                uid = uid,
                gid = gid,
                size = size,
                sha = bytesToHex20(rawSha, 0),
                stage = stage,
                assumeValid = assumeValid,
                path = nameBytes.decodeToString(),
            )
    }
    // Extensions and trailing checksum (already verified). Skip extensions.
    while (r.remaining >= 8 + 20) {
        // Each extension: 4 ASCII signature + 4-byte BE length + payload
        r.readBytes(4)
        val len = r.readIntBe()
        if (len < 0 || len > r.remaining - 20) break
        r.readBytes(len)
    }
    return IndexFile(version, entries)
}

private val HEX = "0123456789abcdef".toCharArray()

private fun bytesToHex20(
    src: ByteArray,
    off: Int,
): String {
    val sb = StringBuilder(40)
    for (k in 0 until 20) {
        val v = src[off + k].toInt() and 0xff
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0f])
    }
    return sb.toString()
}

private fun hexToBytes20(hex: String): ByteArray {
    require(hex.length == 40) { "sha hex must be 40 chars" }
    val out = ByteArray(20)
    for (k in 0 until 20) {
        out[k] = ((nib(hex[k * 2]) shl 4) or nib(hex[k * 2 + 1])).toByte()
    }
    return out
}

private fun nib(c: Char): Int =
    when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> throw IllegalArgumentException("bad hex char: $c")
    }

private class ByteArrayBuilder {
    private val buf = ArrayList<Byte>()
    val size: Int get() = buf.size

    fun writeIntBe(v: Int) {
        buf += ((v ushr 24) and 0xff).toByte()
        buf += ((v ushr 16) and 0xff).toByte()
        buf += ((v ushr 8) and 0xff).toByte()
        buf += (v and 0xff).toByte()
    }

    fun writeShortBe(v: Int) {
        buf += ((v ushr 8) and 0xff).toByte()
        buf += (v and 0xff).toByte()
    }

    fun writeBytes(b: ByteArray) {
        for (x in b) buf += x
    }

    fun writeAscii(s: String) {
        for (c in s) buf += c.code.toByte()
    }

    fun writeZeros(n: Int) {
        repeat(n) { buf += 0 }
    }

    fun toByteArray(): ByteArray = buf.toByteArray()
}

private class ByteArrayReader(
    private val data: ByteArray,
) {
    var pos: Int = 0
    val remaining: Int get() = data.size - pos

    fun readIntBe(): Int {
        val r =
            ((data[pos].toInt() and 0xff) shl 24) or
                ((data[pos + 1].toInt() and 0xff) shl 16) or
                ((data[pos + 2].toInt() and 0xff) shl 8) or
                (data[pos + 3].toInt() and 0xff)
        pos += 4
        return r
    }

    fun readShortBe(): Int {
        val r = ((data[pos].toInt() and 0xff) shl 8) or (data[pos + 1].toInt() and 0xff)
        pos += 2
        return r
    }

    fun readBytes(n: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun readAscii(n: Int): String {
        val s = data.decodeToString(pos, pos + n)
        pos += n
        return s
    }
}
