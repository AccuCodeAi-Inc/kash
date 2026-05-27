package com.accucodeai.kash.tools.tar

/**
 * ustar / pax tar format primitives — header layout, octal field encoding,
 * checksum computation, typeflag constants. No I/O lives here; everything
 * is plain byte math so it ports to every KMP target.
 *
 * Header layout (POSIX 1003.1-1988 ustar; 512-byte block):
 *   off  size  name
 *     0   100  name
 *   100     8  mode
 *   108     8  uid
 *   116     8  gid
 *   124    12  size
 *   136    12  mtime
 *   148     8  chksum
 *   156     1  typeflag
 *   157   100  linkname
 *   257     6  magic ("ustar\0" or "ustar "+\0 for GNU)
 *   263     2  version ("00" for POSIX)
 *   265    32  uname
 *   297    32  gname
 *   329     8  devmajor
 *   337     8  devminor
 *   345   155  prefix
 *   500    12  pad
 */
public object TarFormat {
    public const val BLOCK_SIZE: Int = 512

    // typeflag bytes
    public const val TF_REGULAR: Byte = '0'.code.toByte()
    public const val TF_REGULAR_OLD: Byte = 0
    public const val TF_HARDLINK: Byte = '1'.code.toByte()
    public const val TF_SYMLINK: Byte = '2'.code.toByte()
    public const val TF_CHAR: Byte = '3'.code.toByte()
    public const val TF_BLOCK: Byte = '4'.code.toByte()
    public const val TF_DIR: Byte = '5'.code.toByte()
    public const val TF_FIFO: Byte = '6'.code.toByte()
    public const val TF_PAX_GLOBAL: Byte = 'g'.code.toByte()
    public const val TF_PAX_EXT: Byte = 'x'.code.toByte()
    public const val TF_GNU_LONGNAME: Byte = 'L'.code.toByte()
    public const val TF_GNU_LONGLINK: Byte = 'K'.code.toByte()

    /** Octal-encode [value] into [n]-byte field. Trailing NUL or space per ustar. */
    public fun encodeOctal(
        value: Long,
        n: Int,
    ): ByteArray {
        require(value >= 0) { "negative octal: $value" }
        // ustar convention: n-1 octal digits + NUL.
        val s = value.toString(8).padStart(n - 1, '0')
        val out = ByteArray(n)
        for (i in 0 until n - 1) out[i] = s[i].code.toByte()
        out[n - 1] = 0
        return out
    }

    /** Decode an octal/numeric field of [len] bytes starting at [off] in [block]. */
    public fun decodeOctal(
        block: ByteArray,
        off: Int,
        len: Int,
    ): Long {
        // GNU base-256 extension: high bit of first byte set => big-endian binary.
        val first = block[off].toInt() and 0xff
        if (first and 0x80 != 0) {
            var v = 0L
            // Mask top bit of the first byte.
            v = (first and 0x7f).toLong()
            for (i in 1 until len) {
                v = (v shl 8) or ((block[off + i].toInt() and 0xff).toLong())
            }
            return v
        }
        var v = 0L
        var seen = false
        for (i in 0 until len) {
            val c = block[off + i].toInt() and 0xff
            if (c == 0 || c == ' '.code) {
                if (seen) break else continue
            }
            if (c < '0'.code || c > '7'.code) {
                // Stop on garbage rather than throw — be liberal in what we accept.
                break
            }
            seen = true
            v = (v shl 3) or ((c - '0'.code).toLong())
        }
        return v
    }

    /** Decode an ASCII string from a fixed-length, NUL-terminated header field. */
    public fun decodeString(
        block: ByteArray,
        off: Int,
        len: Int,
    ): String {
        var end = off
        val limit = off + len
        while (end < limit && block[end].toInt() != 0) end++
        // ustar fields are ASCII; decoding via Latin-1 round-trips every byte.
        val chars = CharArray(end - off) { i -> (block[off + i].toInt() and 0xff).toChar() }
        return chars.concatToString()
    }

    /**
     * Compute the ustar header checksum: sum of every byte in the 512-byte
     * header treating the 8-byte chksum field itself as spaces (0x20).
     */
    public fun checksum(header: ByteArray): Int {
        require(header.size == BLOCK_SIZE)
        var sum = 0
        for (i in 0 until BLOCK_SIZE) {
            sum +=
                if (i in 148..155) {
                    0x20
                } else {
                    header[i].toInt() and 0xff
                }
        }
        return sum
    }

    /** True when [block] is all-zero — the EOF sentinel pair. */
    public fun isZeroBlock(block: ByteArray): Boolean {
        for (b in block) if (b.toInt() != 0) return false
        return true
    }
}

/** Parsed tar entry header. */
public data class TarHeader(
    val name: String,
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val mtime: Long,
    val typeflag: Byte,
    val linkname: String,
    val uname: String,
    val gname: String,
    val prefix: String,
) {
    /** Full path: prefix + "/" + name (or just name when prefix is empty). */
    public val path: String get() = if (prefix.isEmpty()) name else "$prefix/$name"

    public val isDirectory: Boolean
        get() = typeflag == TarFormat.TF_DIR || (typeflag == TarFormat.TF_REGULAR_OLD && name.endsWith("/"))

    public val isRegularFile: Boolean
        get() = typeflag == TarFormat.TF_REGULAR || typeflag == TarFormat.TF_REGULAR_OLD

    public val isSymlink: Boolean
        get() = typeflag == TarFormat.TF_SYMLINK
}

/** Build a 512-byte ustar header block. Long names need pax/GNU helper headers — done by encoder. */
internal fun buildUstarHeader(
    name: String,
    mode: Int,
    uid: Int,
    gid: Int,
    size: Long,
    mtime: Long,
    typeflag: Byte,
    linkname: String = "",
    uname: String = "user",
    gname: String = "user",
    prefix: String = "",
): ByteArray {
    val b = ByteArray(TarFormat.BLOCK_SIZE)

    fun putAscii(
        s: String,
        off: Int,
        n: Int,
    ) {
        val bytes = s.encodeToByteArray()
        val len = minOf(bytes.size, n)
        bytes.copyInto(b, off, 0, len)
        // Remaining bytes stay as 0 — that's NUL-padding per ustar.
    }

    fun putField(
        bytes: ByteArray,
        off: Int,
    ) {
        bytes.copyInto(b, off, 0, bytes.size)
    }

    putAscii(name, 0, 100)
    putField(TarFormat.encodeOctal((mode and 0xfff).toLong(), 8), 100)
    putField(TarFormat.encodeOctal(uid.toLong(), 8), 108)
    putField(TarFormat.encodeOctal(gid.toLong(), 8), 116)
    putField(TarFormat.encodeOctal(size, 12), 124)
    putField(TarFormat.encodeOctal(mtime, 12), 136)
    // chksum: fill with spaces, compute, then overwrite.
    for (i in 148..155) b[i] = 0x20
    b[156] = typeflag
    putAscii(linkname, 157, 100)
    // ustar magic + "00" version
    putAscii("ustar", 257, 6)
    b[262] = 0
    b[263] = '0'.code.toByte()
    b[264] = '0'.code.toByte()
    putAscii(uname, 265, 32)
    putAscii(gname, 297, 32)
    // devmajor / devminor / pad left zero
    putAscii(prefix, 345, 155)
    val sum = TarFormat.checksum(b)
    // chksum format: 6 octal digits, NUL, space.
    val sumStr = sum.toString(8).padStart(6, '0')
    for (i in 0 until 6) b[148 + i] = sumStr[i].code.toByte()
    b[154] = 0
    b[155] = 0x20
    return b
}
