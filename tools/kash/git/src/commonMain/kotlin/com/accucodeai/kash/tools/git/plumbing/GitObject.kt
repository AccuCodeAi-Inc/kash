package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.hash.sha1
import com.accucodeai.kash.hash.toHex

/**
 * The four object types git stores. `tag` is annotated tags; we read &
 * write all four. Lightweight tags are not objects — they're refs that
 * point straight at a commit, so they don't appear here.
 */
public enum class ObjectType(
    public val token: String,
) {
    BLOB("blob"),
    TREE("tree"),
    COMMIT("commit"),
    TAG("tag"),
    ;

    public companion object {
        public fun ofToken(token: String): ObjectType =
            entries.firstOrNull { it.token == token }
                ?: error("unknown git object type: $token")
    }
}

/**
 * Encode the on-disk framing of a git object — what git zlib-compresses
 * into `.git/objects/<aa>/<rest>` and what the SHA is computed over.
 * Framing is `<token> <len>\0<payload>` (ASCII length, NUL separator).
 */
public fun framedObject(
    type: ObjectType,
    payload: ByteArray,
): ByteArray {
    val header = "${type.token} ${payload.size}".encodeToByteArray()
    val out = ByteArray(header.size + 1 + payload.size)
    header.copyInto(out, 0)
    out[header.size] = 0
    payload.copyInto(out, header.size + 1)
    return out
}

/** Compute the lowercase-hex SHA-1 git would assign to an object with this payload. */
public fun objectSha(
    type: ObjectType,
    payload: ByteArray,
): String = toHex(sha1(framedObject(type, payload)))

/**
 * Parse `<token> <len>\0<payload>` back into its parts. Verifies the
 * declared length matches the payload bytes; throws on mismatch.
 */
public data class ParsedObject(
    val type: ObjectType,
    val payload: ByteArray,
)

public fun parseFramedObject(bytes: ByteArray): ParsedObject {
    val nul = bytes.indexOf(0.toByte())
    require(nul > 0) { "object framing: missing NUL separator" }
    val header = bytes.decodeToString(0, nul)
    val sp = header.indexOf(' ')
    require(sp > 0) { "object framing: missing space in header" }
    val type = ObjectType.ofToken(header.substring(0, sp))
    val declared =
        header.substring(sp + 1).toIntOrNull()
            ?: error("object framing: bad length '${header.substring(sp + 1)}'")
    val payload = bytes.copyOfRange(nul + 1, bytes.size)
    require(payload.size == declared) {
        "object framing: declared length $declared, got ${payload.size}"
    }
    return ParsedObject(type, payload)
}

private fun ByteArray.indexOf(b: Byte): Int {
    for (i in indices) if (this[i] == b) return i
    return -1
}
