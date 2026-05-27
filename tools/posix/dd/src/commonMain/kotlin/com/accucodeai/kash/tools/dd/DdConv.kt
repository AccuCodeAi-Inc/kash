package com.accucodeai.kash.tools.dd

/**
 * Pure byte-level conversions for `dd conv=...`. Each function takes a
 * ByteArray (the *input* block as read) and returns a ByteArray (the bytes
 * to forward to the output buffer).
 *
 * Order of application within a block (POSIX):
 *  1. `swab`           (byte-pair swap, on raw input bytes)
 *  2. `block`/`unblock` (record reformat)
 *  3. `lcase`/`ucase`   (ASCII case transform)
 *  4. `sync`            (pad up to ibs with NULs) — only when no block/unblock
 *
 * `ascii`/`ebcdic` translation tables are NOT implemented; the parser accepts
 * the flag but [applyConv] throws [UnsupportedOperationException] for now.
 */
public object DdConv {
    public fun swab(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val out = bytes.copyOf()
        var i = 0
        while (i + 1 < out.size) {
            val tmp = out[i]
            out[i] = out[i + 1]
            out[i + 1] = tmp
            i += 2
        }
        // Odd trailing byte preserved unchanged.
        return out
    }

    public fun lcase(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i] = if (b in 0x41..0x5A) (b + 0x20).toByte() else bytes[i]
        }
        return out
    }

    public fun ucase(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            out[i] = if (b in 0x61..0x7A) (b - 0x20).toByte() else bytes[i]
        }
        return out
    }

    /** Pad [bytes] with NUL up to [size]. If already >= size, return unchanged. */
    public fun sync(
        bytes: ByteArray,
        size: Int,
    ): ByteArray {
        if (bytes.size >= size) return bytes
        val out = ByteArray(size)
        bytes.copyInto(out)
        // remaining bytes already zero
        return out
    }

    /**
     * `conv=block`: treat input as newline-terminated records; for each
     * record, pad/truncate to [cbs] with trailing spaces (0x20). Newlines
     * are stripped.
     */
    public fun block(
        bytes: ByteArray,
        cbs: Int,
        truncCounter: IntArray,
    ): ByteArray {
        if (cbs <= 0) return bytes
        val out = ArrayList<Byte>(bytes.size + cbs)
        var lineStart = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == '\n'.code.toByte()) {
                appendBlock(out, bytes, lineStart, i, cbs, truncCounter)
                lineStart = i + 1
            }
            i++
        }
        // Trailing partial line without newline: also pad.
        if (lineStart < bytes.size) {
            appendBlock(out, bytes, lineStart, bytes.size, cbs, truncCounter)
        }
        return out.toByteArray()
    }

    private fun appendBlock(
        out: ArrayList<Byte>,
        src: ByteArray,
        start: Int,
        end: Int,
        cbs: Int,
        truncCounter: IntArray,
    ) {
        val len = end - start
        val emit = if (len > cbs) cbs else len
        for (k in 0 until emit) out += src[start + k]
        if (len > cbs) truncCounter[0]++
        // pad with spaces
        for (k in emit until cbs) out += 0x20.toByte()
    }

    /**
     * `conv=unblock`: treat input as fixed-length [cbs] records; strip trailing
     * spaces and append a newline.
     */
    public fun unblock(
        bytes: ByteArray,
        cbs: Int,
    ): ByteArray {
        if (cbs <= 0) return bytes
        val out = ArrayList<Byte>(bytes.size + bytes.size / cbs)
        var i = 0
        while (i < bytes.size) {
            val end = (i + cbs).coerceAtMost(bytes.size)
            // strip trailing spaces
            var j = end - 1
            while (j >= i && bytes[j] == 0x20.toByte()) j--
            for (k in i..j) out += bytes[k]
            out += '\n'.code.toByte()
            i = end
        }
        return out.toByteArray()
    }
}
