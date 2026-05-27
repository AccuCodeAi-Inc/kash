package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.hash.HashAlg
import com.accucodeai.kash.hash.hashBytes

/**
 * Implementations of Poul-Henning Kamp's MD5-crypt (`$1$`), Apache APR1
 * (`$apr1$`), and Ulrich Drepper's SHA-256-crypt (`$5$`) / SHA-512-crypt
 * (`$6$`).
 *
 * These follow the published specs exactly; tested against the standard
 * test vectors from glibc's documentation.
 */
internal object Crypt {
    private const val ITOA64 = "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun md5Crypt(
        password: ByteArray,
        salt: String,
        magic: String = "\$1\$",
    ): String {
        // PHK md5-crypt. magic is either "$1$" (default) or "$apr1$" for APR.
        // salt is at most 8 chars, may itself contain "$"-trimmed values.
        val saltClean = trimSalt(salt, 8)
        val saltBytes = saltClean.encodeToByteArray()

        // ctx = password + magic + salt
        val ctx = ByteArrayBuilder()
        ctx.append(password)
        ctx.append(magic.encodeToByteArray())
        ctx.append(saltBytes)

        // alt = md5(password + salt + password)
        val alt = md5(concat(password, saltBytes, password))

        // append alt repeated for password length
        var pl = password.size
        var off = 0
        while (pl > 0) {
            val n = if (pl > 16) 16 else pl
            ctx.appendRange(alt, 0, n)
            pl -= n
            off += n
        }

        // For each bit of password length, from low to high: 0 bit → first
        // password byte, 1 bit → 0 byte.
        var i = password.size
        while (i != 0) {
            if ((i and 1) != 0) {
                ctx.appendByte(0)
            } else {
                ctx.appendByte(password[0])
            }
            i = i ushr 1
        }

        var digest = md5(ctx.toByteArray())

        // 1000 rounds.
        for (round in 0 until 1000) {
            val b = ByteArrayBuilder()
            if ((round and 1) != 0) b.append(password) else b.append(digest)
            if (round % 3 != 0) b.append(saltBytes)
            if (round % 7 != 0) b.append(password)
            if ((round and 1) != 0) b.append(digest) else b.append(password)
            digest = md5(b.toByteArray())
        }

        // Custom base64 of digest.
        val sb = StringBuilder()
        sb.append(magic).append(saltClean).append('$')
        sb.append(b64From24(digest, 0, 6, 12, 4))
        sb.append(b64From24(digest, 1, 7, 13, 4))
        sb.append(b64From24(digest, 2, 8, 14, 4))
        sb.append(b64From24(digest, 3, 9, 15, 4))
        sb.append(b64From24(digest, 4, 10, 5, 4))
        sb.append(b64From24(0, 0, digest[11].toInt() and 0xff, 2))
        return sb.toString()
    }

    fun shaCrypt(
        password: ByteArray,
        salt: String,
        bits: Int,
        roundsOverride: Int? = null,
    ): String {
        // Drepper's spec (sha-crypt). bits is 256 or 512.
        val isSha512 = bits == 512
        val alg = if (isSha512) HashAlg.SHA512 else HashAlg.SHA256
        val dlen = if (isSha512) 64 else 32

        // Parse salt: optional "rounds=N$" prefix.
        var rounds = roundsOverride ?: 5000
        var roundsSpecified = roundsOverride != null
        var saltSrc = salt
        if (saltSrc.startsWith("rounds=")) {
            val idx = saltSrc.indexOf('$')
            if (idx > 0) {
                val n = saltSrc.substring(7, idx).toIntOrNull()
                if (n != null) {
                    rounds = n
                    roundsSpecified = true
                }
                saltSrc = saltSrc.substring(idx + 1)
            }
        }
        rounds = rounds.coerceIn(1000, 999_999_999)
        val saltClean = trimSalt(saltSrc, 16)
        val saltBytes = saltClean.encodeToByteArray()

        // Step 1-3: alt = sha(password + salt + password)
        val alt = hashBytes(alg, concat(password, saltBytes, password))

        // Step 4-8: A = sha(password + salt + alt-repeat for password.length)
        val a = ByteArrayBuilder()
        a.append(password)
        a.append(saltBytes)
        var cnt = password.size
        while (cnt > dlen) {
            a.append(alt)
            cnt -= dlen
        }
        a.appendRange(alt, 0, cnt)
        // Step 9: for each bit of password length, low→high: 1→alt, 0→password
        var n = password.size
        while (n != 0) {
            if ((n and 1) != 0) a.append(alt) else a.append(password)
            n = n ushr 1
        }
        var digestA = hashBytes(alg, a.toByteArray())

        // Step 13: DP = sha(password * password.size)
        val dpBuf = ByteArrayBuilder()
        repeat(password.size) { dpBuf.append(password) }
        val dpFull = hashBytes(alg, dpBuf.toByteArray())
        val p = expand(dpFull, password.size, dlen)

        // Step 17-19: DS = sha(salt * (16 + A[0]))
        val dsBuf = ByteArrayBuilder()
        val times = 16 + (digestA[0].toInt() and 0xff)
        repeat(times) { dsBuf.append(saltBytes) }
        val dsFull = hashBytes(alg, dsBuf.toByteArray())
        val s = expand(dsFull, saltBytes.size, dlen)

        // Step 21: rounds iterations.
        for (i in 0 until rounds) {
            val c = ByteArrayBuilder()
            if ((i and 1) != 0) c.append(p) else c.append(digestA)
            if (i % 3 != 0) c.append(s)
            if (i % 7 != 0) c.append(p)
            if ((i and 1) != 0) c.append(digestA) else c.append(p)
            digestA = hashBytes(alg, c.toByteArray())
        }

        // Output.
        val sb = StringBuilder()
        sb.append(if (isSha512) "\$6\$" else "\$5\$")
        if (roundsSpecified) sb.append("rounds=").append(rounds).append('$')
        sb.append(saltClean).append('$')
        sb.append(encodeShaCrypt(digestA, isSha512))
        return sb.toString()
    }

    private fun encodeShaCrypt(
        d: ByteArray,
        sha512: Boolean,
    ): String {
        val sb = StringBuilder()
        if (sha512) {
            // Spec defines a specific permutation for sha512.
            sb.append(b64From24(d, 0, 21, 42, 4))
            sb.append(b64From24(d, 22, 43, 1, 4))
            sb.append(b64From24(d, 44, 2, 23, 4))
            sb.append(b64From24(d, 3, 24, 45, 4))
            sb.append(b64From24(d, 25, 46, 4, 4))
            sb.append(b64From24(d, 47, 5, 26, 4))
            sb.append(b64From24(d, 6, 27, 48, 4))
            sb.append(b64From24(d, 28, 49, 7, 4))
            sb.append(b64From24(d, 50, 8, 29, 4))
            sb.append(b64From24(d, 9, 30, 51, 4))
            sb.append(b64From24(d, 31, 52, 10, 4))
            sb.append(b64From24(d, 53, 11, 32, 4))
            sb.append(b64From24(d, 12, 33, 54, 4))
            sb.append(b64From24(d, 34, 55, 13, 4))
            sb.append(b64From24(d, 56, 14, 35, 4))
            sb.append(b64From24(d, 15, 36, 57, 4))
            sb.append(b64From24(d, 37, 58, 16, 4))
            sb.append(b64From24(d, 59, 17, 38, 4))
            sb.append(b64From24(d, 18, 39, 60, 4))
            sb.append(b64From24(d, 40, 61, 19, 4))
            sb.append(b64From24(d, 62, 20, 41, 4))
            sb.append(b64From24(0, 0, d[63].toInt() and 0xff, 2))
        } else {
            sb.append(b64From24(d, 0, 10, 20, 4))
            sb.append(b64From24(d, 21, 1, 11, 4))
            sb.append(b64From24(d, 12, 22, 2, 4))
            sb.append(b64From24(d, 3, 13, 23, 4))
            sb.append(b64From24(d, 24, 4, 14, 4))
            sb.append(b64From24(d, 15, 25, 5, 4))
            sb.append(b64From24(d, 6, 16, 26, 4))
            sb.append(b64From24(d, 27, 7, 17, 4))
            sb.append(b64From24(d, 18, 28, 8, 4))
            sb.append(b64From24(d, 9, 19, 29, 4))
            sb.append(b64From24(0, d[31].toInt() and 0xff, d[30].toInt() and 0xff, 3))
        }
        return sb.toString()
    }

    private fun expand(
        full: ByteArray,
        len: Int,
        dlen: Int,
    ): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off + dlen <= len) {
            full.copyInto(out, off, 0, dlen)
            off += dlen
        }
        if (off < len) full.copyInto(out, off, 0, len - off)
        return out
    }

    private fun md5(data: ByteArray): ByteArray = hashBytes(HashAlg.MD5, data)

    private fun trimSalt(
        salt: String,
        max: Int,
    ): String {
        val dollar = salt.indexOf('$')
        val clean = if (dollar >= 0) salt.substring(0, dollar) else salt
        return if (clean.length > max) clean.substring(0, max) else clean
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        var n = 0
        for (p in parts) n += p.size
        val out = ByteArray(n)
        var i = 0
        for (p in parts) {
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }

    /** Encode three bytes as `n` base64 (crypt alphabet) characters. */
    private fun b64From24(
        b2: Int,
        b1: Int,
        b0: Int,
        n: Int,
    ): String {
        var v = ((b2 and 0xff) shl 16) or ((b1 and 0xff) shl 8) or (b0 and 0xff)
        val sb = StringBuilder()
        repeat(n) {
            sb.append(ITOA64[v and 0x3f])
            v = v ushr 6
        }
        return sb.toString()
    }

    private fun b64From24(
        d: ByteArray,
        i2: Int,
        i1: Int,
        i0: Int,
        n: Int,
    ): String = b64From24(d[i2].toInt(), d[i1].toInt(), d[i0].toInt(), n)
}

internal class ByteArrayBuilder {
    private var buf: ByteArray = ByteArray(64)
    private var sz: Int = 0

    fun append(b: ByteArray) {
        ensure(sz + b.size)
        b.copyInto(buf, sz)
        sz += b.size
    }

    fun appendRange(
        b: ByteArray,
        from: Int,
        len: Int,
    ) {
        ensure(sz + len)
        b.copyInto(buf, sz, from, from + len)
        sz += len
    }

    fun appendByte(b: Byte) {
        ensure(sz + 1)
        buf[sz++] = b
    }

    fun toByteArray(): ByteArray = buf.copyOf(sz)

    private fun ensure(need: Int) {
        if (need <= buf.size) return
        var cap = buf.size
        while (cap < need) cap *= 2
        buf = buf.copyOf(cap)
    }
}
