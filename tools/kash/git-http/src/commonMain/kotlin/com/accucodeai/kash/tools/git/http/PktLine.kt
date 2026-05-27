package com.accucodeai.kash.tools.git.http

/**
 * The git "pkt-line" framing the smart-HTTP protocol uses.
 *
 * Each line is a 4-character ASCII hex length prefix counting itself
 * plus the payload (so `"0006a\n"` is a 6-byte frame carrying `"a\n"`).
 * The two special lengths `"0000"` (flush-pkt) and `"0001"` (delim-pkt,
 * protocol v2) carry no payload.
 *
 * Reference: `Documentation/technical/pack-protocol.txt` in the
 * upstream git source tree.
 */
internal object PktLine {
    const val FLUSH = "0000"

    /** Encode [payload] (raw bytes) as one pkt-line frame. */
    fun encode(payload: ByteArray): ByteArray {
        val len = payload.size + 4
        require(len <= 0xFFFF) { "pkt-line: payload too large ($len > 65535)" }
        val hdr = lenToHex(len).encodeToByteArray()
        val out = ByteArray(len)
        hdr.copyInto(out, 0)
        payload.copyInto(out, 4)
        return out
    }

    /** Encode the [text] payload (UTF-8 encoded). Does NOT append a trailing newline. */
    fun encodeText(text: String): ByteArray = encode(text.encodeToByteArray())

    /** A literal `"0000"` flush-pkt. */
    val flush: ByteArray = FLUSH.encodeToByteArray()

    private fun lenToHex(len: Int): String {
        val hex = "0123456789abcdef"
        val a = hex[(len ushr 12) and 0xf]
        val b = hex[(len ushr 8) and 0xf]
        val c = hex[(len ushr 4) and 0xf]
        val d = hex[len and 0xf]
        return "$a$b$c$d"
    }
}

/**
 * One framed entry returned by [PktLineReader]. `payload == null` denotes
 * a flush-pkt (length `0000`); `delim == true` denotes the v2 delim-pkt
 * (`0001`). Tools usually only care about non-control payloads.
 */
internal data class PktFrame(
    val payload: ByteArray?,
    val delim: Boolean = false,
) {
    val isFlush: Boolean get() = payload == null && !delim
}

/**
 * Forward-cursoring pkt-line reader. The smart-HTTP response is buffered
 * (see `HttpResponse.body`), so we keep it simple: read by byte offset
 * into the underlying [data], no streaming.
 */
internal class PktLineReader(
    private val data: ByteArray,
    initialOffset: Int = 0,
) {
    var offset: Int = initialOffset
        private set

    val remaining: Int get() = data.size - offset

    /**
     * Read the next pkt-line. Returns null when we're at the end of the
     * buffer. Throws on a malformed length prefix or a truncated frame.
     */
    fun next(): PktFrame? {
        if (remaining <= 0) return null
        require(remaining >= 4) { "pkt-line: truncated header at offset=$offset" }
        val hdr = data.decodeToString(offset, offset + 4)
        val len = hdr.toIntOrNull(16) ?: error("pkt-line: bad length prefix '$hdr' at offset=$offset")
        offset += 4
        return when (len) {
            0 -> {
                PktFrame(payload = null)
            }

            1 -> {
                PktFrame(payload = null, delim = true)
            }

            in 2..3 -> {
                error("pkt-line: invalid short length $len")
            }

            else -> {
                val payloadLen = len - 4
                require(remaining >= payloadLen) {
                    "pkt-line: truncated payload (want=$payloadLen have=$remaining) at offset=$offset"
                }
                val payload = data.copyOfRange(offset, offset + payloadLen)
                offset += payloadLen
                PktFrame(payload = payload)
            }
        }
    }

    /** Skip frames until (and including) the next flush-pkt. */
    fun consumeFlush() {
        while (true) {
            val frame = next() ?: error("pkt-line: ran out of input before flush")
            if (frame.isFlush) return
        }
    }
}
