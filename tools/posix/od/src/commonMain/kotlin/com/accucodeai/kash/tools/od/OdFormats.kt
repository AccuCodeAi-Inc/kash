package com.accucodeai.kash.tools.od

/** A single `-t` format specifier. */
internal sealed class OdFormat {
    abstract fun width(): Int

    /** Bytes consumed by one output cell. For [Char]/[NamedChar] this is 1. */
    abstract fun bytesPerUnit(): Int

    /** Render a single unit starting at [offset] in [bytes]; returns a left-padded cell. */
    abstract fun render(
        bytes: ByteArray,
        offset: Int,
    ): String

    /** Named ASCII chars (`-t a`): 1 byte → 3-char field (nul, soh, …, sp, '!', …, del). */
    object NamedChar : OdFormat() {
        override fun width(): Int = 3

        override fun bytesPerUnit(): Int = 1

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val b = bytes[offset].toInt() and 0x7F // strip high bit per POSIX
            val s = NAMES[b]
            return s.padStart(3)
        }

        private val NAMES =
            arrayOf(
                "nul",
                "soh",
                "stx",
                "etx",
                "eot",
                "enq",
                "ack",
                "bel",
                "bs",
                "ht",
                "nl",
                "vt",
                "ff",
                "cr",
                "so",
                "si",
                "dle",
                "dc1",
                "dc2",
                "dc3",
                "dc4",
                "nak",
                "syn",
                "etb",
                "can",
                "em",
                "sub",
                "esc",
                "fs",
                "gs",
                "rs",
                "us",
                "sp",
            ) +
                (33..126).map { "${it.toChar()}" }.toTypedArray() +
                arrayOf("del")
    }

    /** Printable chars or C escape (`-t c`). 1 byte → 3-char field. */
    object Char1 : OdFormat() {
        override fun width(): Int = 3

        override fun bytesPerUnit(): Int = 1

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val b = bytes[offset].toInt() and 0xFF
            val s =
                when (b) {
                    0 -> "\\0"

                    7 -> "\\a"

                    8 -> "\\b"

                    9 -> "\\t"

                    10 -> "\\n"

                    11 -> "\\v"

                    12 -> "\\f"

                    13 -> "\\r"

                    // Byte 0x5C (`\`) renders as a single printable `\`.
                    // POSIX `od -c` leaves the choice implementation-
                    // defined; BSD/macOS od (and the system that generated
                    // bash's conformance corpus) emits a single `\`, while
                    // GNU od emits `\\` to disambiguate from escape
                    // sequences. We match the corpus.
                    else -> if (b in 32..126) b.toChar().toString() else octal(b.toLong(), 3)
                }
            return s.padStart(3)
        }
    }

    /** Decimal signed integer of [size] bytes. */
    class DecSigned(
        val size: Int,
    ) : OdFormat() {
        private val cellWidth: Int =
            when (size) {
                1 -> 4
                2 -> 6
                4 -> 11
                8 -> 20
                else -> error("invalid size $size")
            }

        override fun width(): Int = cellWidth

        override fun bytesPerUnit(): Int = size

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val v = readSigned(bytes, offset, size)
            return v.toString().padStart(cellWidth)
        }
    }

    /** Decimal unsigned integer of [size] bytes. */
    class DecUnsigned(
        val size: Int,
    ) : OdFormat() {
        private val cellWidth: Int =
            when (size) {
                1 -> 3
                2 -> 5
                4 -> 10
                8 -> 20
                else -> error("invalid size $size")
            }

        override fun width(): Int = cellWidth

        override fun bytesPerUnit(): Int = size

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val v = readUnsigned(bytes, offset, size)
            return v.toString().padStart(cellWidth)
        }
    }

    /** Octal unsigned of [size] bytes. */
    class Octal(
        val size: Int,
    ) : OdFormat() {
        private val cellWidth: Int =
            when (size) {
                1 -> 3
                2 -> 6
                4 -> 11
                8 -> 22
                else -> error("invalid size $size")
            }

        override fun width(): Int = cellWidth

        override fun bytesPerUnit(): Int = size

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val v = readUnsigned(bytes, offset, size)
            return octal(v, cellWidth)
        }
    }

    /** Hex unsigned of [size] bytes. */
    class Hex(
        val size: Int,
    ) : OdFormat() {
        private val cellWidth: Int =
            when (size) {
                1 -> 2
                2 -> 4
                4 -> 8
                8 -> 16
                else -> error("invalid size $size")
            }

        override fun width(): Int = cellWidth

        override fun bytesPerUnit(): Int = size

        override fun render(
            bytes: ByteArray,
            offset: Int,
        ): String {
            val v = readUnsigned(bytes, offset, size)
            return hex(v, cellWidth)
        }
    }
}

internal fun octal(
    v: Long,
    width: Int,
): String {
    val s = v.toString(8)
    return if (s.length >= width) s else "0".repeat(width - s.length) + s
}

internal fun hex(
    v: Long,
    width: Int,
): String {
    val s = v.toString(16)
    return if (s.length >= width) s else "0".repeat(width - s.length) + s
}

/** Read little-endian unsigned integer of [size] bytes; values < 8 byte returned in low bits. */
internal fun readUnsigned(
    bytes: ByteArray,
    offset: Int,
    size: Int,
): Long {
    var v = 0L
    val limit = minOf(size, bytes.size - offset)
    for (i in 0 until limit) {
        v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
    }
    return v
}

/** Read little-endian signed integer of [size] bytes (sign-extended). */
internal fun readSigned(
    bytes: ByteArray,
    offset: Int,
    size: Int,
): Long {
    val u = readUnsigned(bytes, offset, size)
    // Sign-extend if high bit of the top occupied byte is set.
    val limit = minOf(size, bytes.size - offset)
    if (limit < size) return u // partial trailing — treat as unsigned
    val signMask = 1L shl (8 * size - 1)
    return if (u and signMask != 0L) {
        if (size == 8) u else u or ((-1L) shl (8 * size))
    } else {
        u
    }
}

/**
 * Parse a `-t TYPE` argument: one or more letters, each optionally followed by a size.
 * E.g. "x1", "o2", "d4", "c", "a", "x1d2" → [Hex(1), DecSigned(2)].
 *
 * Returns null on malformed input.
 */
internal fun parseTypeSpec(spec: String): List<OdFormat>? {
    if (spec.isEmpty()) return null
    val out = mutableListOf<OdFormat>()
    var i = 0
    while (i < spec.length) {
        val c = spec[i]
        i++
        // Read optional trailing digits as size.
        val sizeStart = i
        while (i < spec.length && spec[i].isDigit()) i++
        val sizeStr = spec.substring(sizeStart, i)

        // 'C'/'S'/'I'/'L' size shortcuts (GNU): C=char, S=short, I=int, L=long.
        // For brevity we accept these as size aliases when they follow the format letter.
        // POSIX only mandates numeric sizes — char-form sizes are a common extension.
        val numericSize: Int? = sizeStr.toIntOrNull()

        when (c) {
            'a' -> {
                if (sizeStr.isNotEmpty()) return null
                out += OdFormat.NamedChar
            }

            'c' -> {
                if (sizeStr.isNotEmpty()) return null
                out += OdFormat.Char1
            }

            'd' -> {
                val sz = numericSize ?: 4
                if (sz !in setOf(1, 2, 4, 8)) return null
                out += OdFormat.DecSigned(sz)
            }

            'u' -> {
                val sz = numericSize ?: 4
                if (sz !in setOf(1, 2, 4, 8)) return null
                out += OdFormat.DecUnsigned(sz)
            }

            'o' -> {
                val sz = numericSize ?: 4
                if (sz !in setOf(1, 2, 4, 8)) return null
                out += OdFormat.Octal(sz)
            }

            'x' -> {
                val sz = numericSize ?: 4
                if (sz !in setOf(1, 2, 4, 8)) return null
                out += OdFormat.Hex(sz)
            }

            'f' -> {
                // Floating-point not supported; reject so caller can error cleanly.
                return null
            }

            else -> {
                return null
            }
        }
    }
    return out
}
