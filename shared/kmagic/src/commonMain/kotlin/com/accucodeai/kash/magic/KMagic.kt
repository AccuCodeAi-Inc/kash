package com.accucodeai.kash.magic

/**
 * Content-based file type identification by magic-number signature, in the
 * spirit of `file(1)` — but a pure, dependency-free byte matcher rather than a
 * libmagic DSL interpreter.
 *
 * KMagic never does I/O. Callers feed it a **prefix** of the file's bytes;
 * [PEEK_BYTES] is the recommended prefix size. Reading only a bounded prefix
 * (rather than the whole file) is the point — a multi-gigabyte file is
 * identified from its first few kilobytes, exactly like real `file`.
 *
 * Signatures are drawn from the Wikipedia "List of file signatures" plus the
 * common formats `file` recognizes. See [Signatures].
 */
public object KMagic {
    /**
     * Recommended number of leading bytes to hand to [identify] / [detect].
     *
     * The deepest signature (`tar`, at offset 257) needs ~265 bytes; 8 KiB
     * gives ample headroom and a representative sample for text
     * classification. Files identified purely from a prefix may be
     * misclassified if a distinguishing byte lies past this window — that is
     * the same bounded-buffer tradeoff libmagic makes.
     */
    public const val PEEK_BYTES: Int = 8192

    /**
     * Bytes handed to a refiner/validator when a hit is found mid-blob. The
     * deepest reader is the ext superblock validator (~item+1102); everything
     * else is well under that, so 2 KiB is ample headroom.
     */
    private const val REFINE_WINDOW: Int = 2048

    /**
     * Best-effort exact length of an item of type [match] starting at [start]
     * in [blob], or null if it can't be derived cheaply (caller falls back to
     * carve-to-next). Exact for PNG and BMP today.
     */
    public fun measure(
        blob: ByteArray,
        start: Int,
        match: MagicMatch,
    ): Long? = measureLength(blob, start, match)

    /**
     * Identify [prefix]: try signatures first, then fall back to text/data
     * classification. Always returns a result. An empty input is reported as
     * `empty`.
     */
    public fun identify(prefix: ByteArray): MagicMatch {
        if (prefix.isEmpty()) return MagicMatch("empty", "inode/x-empty")
        return detect(prefix) ?: classifyText(prefix)
    }

    /**
     * Run only the signature table over [prefix]. Returns null when no
     * signature matches (the caller may then try [classifyText]).
     */
    public fun detect(prefix: ByteArray): MagicMatch? {
        if (prefix.isEmpty()) return null
        for (sig in Signatures.candidatesFor(prefix[0].toInt() and 0xff)) {
            if (!sig.matchesAt(prefix)) continue
            val refine = sig.refine
            if (refine != null) {
                val refined = refine(prefix) ?: continue
                return refined
            }
            return sig.match
        }
        return null
    }

    /**
     * Slide every signature across [blob] and report each position where one
     * matches — the carving / binwalk primitive. Unlike [detect] (which only
     * looks at the file start), this finds embedded items at any offset.
     *
     * Results are sorted by offset. A single position may yield multiple hits
     * (overlapping signatures); all are reported. Refined signatures that
     * reject (their refine returns null) are dropped. This is content-only:
     * text/data classification is never applied here.
     *
     * Cost is roughly O(blob.size × avg signatures per lead byte); the lead-byte
     * index keeps the inner loop short.
     */
    public fun scan(blob: ByteArray): List<Carving> = scanRange(blob, 0, blob.size)

    /**
     * How far before a magic-byte position a hit can start — the backward
     * context a chunked/streaming scan must retain (the largest signature
     * offset). See [scanRange].
     */
    public val scanLookBehind: Int get() = Signatures.maxOffset

    /**
     * How far past a magic-byte position a scan must be able to read to test a
     * match and run refiners — the forward context a streaming window needs.
     */
    public val scanLookAhead: Int get() = REFINE_WINDOW + Signatures.maxPatternLen

    /**
     * Like [scan] but only considers magic-byte positions in
     * `[fromMagicPos, toMagicPos)` of [buf]. Enables single-pass streaming over
     * arbitrarily large inputs: feed overlapping windows and scan disjoint
     * owned ranges so no hit is reported twice. A hit's reported offset is the
     * embedded item's start (`magicPos - signatureOffset`), which may fall
     * before [fromMagicPos]; callers add their window's absolute base.
     */
    public fun scanRange(
        buf: ByteArray,
        fromMagicPos: Int,
        toMagicPos: Int,
    ): List<Carving> {
        val out = ArrayList<Carving>()
        val lo = maxOf(0, fromMagicPos)
        val hi = minOf(buf.size, toMagicPos)
        var q = lo
        while (q < hi) {
            val b = buf[q].toInt() and 0xff
            for (sig in Signatures.scanCandidatesFor(b)) {
                emitIfMatch(buf, q - sig.offset, sig, out)
            }
            if (Signatures.wildcardLead.isNotEmpty()) {
                for (sig in Signatures.wildcardLead) emitIfMatch(buf, q, sig, out)
            }
            q++
        }
        out.sortBy { it.offset }
        return out
    }

    private fun emitIfMatch(
        blob: ByteArray,
        base: Int,
        sig: Signature,
        out: MutableList<Carving>,
    ) {
        if (base < 0 || !sig.matchesAtBase(blob, base)) return
        val refine = sig.refine
        val match =
            if (refine != null) {
                val end = minOf(base + REFINE_WINDOW, blob.size)
                refine(blob.copyOfRange(base, end)) ?: return
            } else {
                sig.match
            }
        out += Carving(base.toLong(), match)
    }

    /**
     * Classify [prefix] as ASCII text, UTF-8 text, or binary `data` by scanning
     * its bytes. Mirrors `file`'s text heuristic: printable ASCII (plus the
     * common control whitespace) is text; otherwise valid UTF-8 with high-bit
     * bytes is UTF-8 text; anything else is `data`.
     */
    public fun classifyText(prefix: ByteArray): MagicMatch {
        if (prefix.isEmpty()) return MagicMatch("empty", "inode/x-empty")

        var hasHighBit = false
        var sawCr = false
        var sawLf = false
        for (b in prefix) {
            when (val v = b.toInt() and 0xff) {
                in 0x20..0x7e -> {
                    Unit
                }

                0x09, 0x0c -> {
                    Unit
                }

                0x0a -> {
                    sawLf = true
                }

                0x0d -> {
                    sawCr = true
                }

                0x07, 0x08, 0x0b, 0x1b -> {
                    Unit
                }

                else -> {
                    if (v < 0x80) {
                        return MagicMatch("data", "application/octet-stream")
                    } else {
                        hasHighBit = true
                    }
                }
            }
        }
        if (hasHighBit) {
            return if (isValidUtf8(prefix)) {
                MagicMatch("UTF-8 Unicode text", "text/plain; charset=utf-8")
            } else {
                MagicMatch("data", "application/octet-stream")
            }
        }
        val terminators =
            when {
                sawCr && sawLf -> ", with CRLF line terminators"
                sawCr -> ", with CR line terminators"
                else -> ""
            }
        return MagicMatch("ASCII text$terminators", "text/plain; charset=us-ascii")
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            val extra =
                when {
                    b < 0x80 -> 0
                    b in 0xc2..0xdf -> 1
                    b in 0xe0..0xef -> 2
                    b in 0xf0..0xf4 -> 3
                    else -> return false
                }
            // A truncated multibyte sequence at the very end of a *prefix* is
            // not proof of invalidity — the continuation may lie past the
            // window. Treat such truncation as still-valid.
            if (i + extra >= bytes.size) return true
            for (k in 1..extra) {
                if ((bytes[i + k].toInt() and 0xc0) != 0x80) return false
            }
            i += extra + 1
        }
        return true
    }
}
