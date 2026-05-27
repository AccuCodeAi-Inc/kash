package com.accucodeai.kash.magic

/**
 * Field-level sanity checks attached to noise-prone signatures. A validator is
 * a predicate over the candidate buffer (which begins at the item's start);
 * the signature only matches when it returns true.
 *
 * Convention: **accept on insufficient data.** If the buffer is too short to
 * read a field, the validator cannot disprove the match, so it returns true.
 * This keeps short prefixes / EOF-adjacent hits working while still filtering
 * the common case where the full header is present.
 *
 * Numeric criteria for the trickier formats (LZMA property/dictionary sets,
 * JFFS2 node types) follow the binwalk project's signature definitions.
 */
internal object Validators {
    private fun u16le(
        b: ByteArray,
        i: Int,
    ): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    private fun u16be(
        b: ByteArray,
        i: Int,
    ): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun u32le(
        b: ByteArray,
        i: Int,
    ): Long {
        var v = 0L
        for (k in 0 until 4) v = v or ((b[i + k].toLong() and 0xff) shl (8 * k))
        return v
    }

    /** gzip: compression method must be deflate (8); reserved flag bits clear. */
    fun gzip(buf: ByteArray): Boolean {
        if (buf.size >= 3 && (buf[2].toInt() and 0xff) != 0x08) return false
        if (buf.size >= 4 && (buf[3].toInt() and 0xe0) != 0) return false
        return true
    }

    private val ZIP_METHODS = setOf(0, 1, 6, 8, 9, 12, 14, 93, 95, 98, 99)

    /** ZIP local file header: compression method must be a known value. */
    fun zip(buf: ByteArray): Boolean {
        if (buf.size < 10) return true
        return u16le(buf, 8) in ZIP_METHODS
    }

    private fun jffs2NodeOk(nodetype: Int): Boolean {
        val lo = nodetype and 0xff
        val hi = (nodetype ushr 8) and 0xff
        // DIRENT/INODE/CLEANMARKER/PADDING low bytes, with the compat high bits.
        return lo in 1..4 && (hi == 0x20 || hi == 0xe0)
    }

    fun jffs2Le(buf: ByteArray): Boolean {
        if (buf.size < 4) return true
        return jffs2NodeOk(u16le(buf, 2))
    }

    fun jffs2Be(buf: ByteArray): Boolean {
        if (buf.size < 4) return true
        return jffs2NodeOk(u16be(buf, 2))
    }

    private val LZMA_PROPS = setOf(0x5d, 0x6e, 0x6d, 0x6c)
    private val LZMA_DICTS =
        setOf(
            0x10000000L,
            0x20000000L,
            0x01000000L,
            0x02000000L,
            0x04000000L,
            0x00800000L,
            0x00400000L,
            0x00200000L,
            0x00100000L,
            0x00080000L,
            0x00020000L,
            0x00010000L,
        )

    /**
     * Raw LZMA: a 1-byte magic is hopeless without field checks. Require a known
     * properties byte and a standard dictionary size. (binwalk also dry-run
     * decompresses; we have no pure LZMA decoder, so the static check is our
     * only filter — it still removes the vast majority of false positives.)
     */
    fun lzma(buf: ByteArray): Boolean {
        if (buf.size < 5) return true
        if ((buf[0].toInt() and 0xff) !in LZMA_PROPS) return false
        return u32le(buf, 1) in LZMA_DICTS
    }

    /**
     * MP3/MPEG-ADTS frame: the 11-bit sync is only 2 bytes (FF Fx), so check
     * the next header byte — bitrate index must not be `free` (0) or `bad`
     * (15), and the sample-rate index must not be reserved (3).
     */
    fun mp3(buf: ByteArray): Boolean {
        if (buf.size < 3) return true
        val b2 = buf[2].toInt() and 0xff
        val bitrate = (b2 ushr 4) and 0xf
        val sampleRate = (b2 ushr 2) and 0x3
        return bitrate in 1..14 && sampleRate != 3
    }

    /**
     * MS-DOS "MZ" is hopelessly common; only accept when a PE header is
     * positively confirmed at e_lfanew (u32le @0x3C) within the window. Pure
     * DOS-only executables are dropped — acceptable for carving.
     */
    fun pe(buf: ByteArray): Boolean {
        if (buf.size < 0x40) return true // can't read e_lfanew yet
        val lfanew = u32le(buf, 0x3c)
        if (lfanew < 0x40 || lfanew + 4 > buf.size) return false
        val o = lfanew.toInt()
        return buf[o].toInt() == 0x50 && buf[o + 1].toInt() == 0x45 &&
            buf[o + 2].toInt() == 0x00 && buf[o + 3].toInt() == 0x00
    }

    /** BMP: the two reserved 16-bit fields (offset 6,8) must be zero; size sane. */
    fun bmp(buf: ByteArray): Boolean {
        if (buf.size < 10) return true
        if (u16le(buf, 6) != 0 || u16le(buf, 8) != 0) return false
        return u32le(buf, 2) >= 26
    }

    /** ICO/CUR: image count (u16 @4) must be in range and the dir-entry reserved byte (@9) zero. */
    fun ico(buf: ByteArray): Boolean {
        if (buf.size < 6) return true
        val count = u16le(buf, 4)
        if (count !in 1..256) return false
        if (buf.size >= 10 && (buf[9].toInt() and 0xff) != 0) return false
        return true
    }

    /** JPEG: the byte after the SOI's FF must be a real marker (0xC0–0xFE). */
    fun jpeg(buf: ByteArray): Boolean {
        if (buf.size < 4) return true
        val marker = buf[3].toInt() and 0xff
        return marker in 0xc0..0xfe
    }

    /** ID3v2: the version-major byte (@3) is 2, 3, or 4. */
    fun id3(buf: ByteArray): Boolean {
        if (buf.size < 4) return true
        return (buf[3].toInt() and 0xff) in 2..4
    }

    /** ext2/3/4 superblock (at item+1024); validate block-size / state / revision. */
    fun ext(buf: ByteArray): Boolean {
        val sb = 1024
        if (buf.size < sb + 78) return true
        val logBlockSize = u32le(buf, sb + 24)
        val state = u16le(buf, sb + 58)
        val errors = u16le(buf, sb + 60)
        val revLevel = u32le(buf, sb + 76)
        return logBlockSize in 0..6 && state in 1..3 && errors in 1..3 && revLevel in 0..1
    }
}
