package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import dev.whyoleg.cryptography.BinarySize.Companion.bits
import dev.whyoleg.cryptography.BinarySize.Companion.bytes
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.PBKDF2
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA512
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * `openssl enc` — symmetric encryption / decryption with password-derived keys.
 *
 * Supported ciphers:
 *   -aes-128-cbc -aes-192-cbc -aes-256-cbc
 *   -aes-128-ctr -aes-256-ctr
 *   -aes-128-gcm -aes-256-gcm
 *
 *   -e            encrypt (default)
 *   -d            decrypt
 *   -k PASS       password literal
 *   -pass pass:P  password literal
 *   -pass env:V   read password from env var V
 *   -pbkdf2       REQUIRED — legacy MD5-EVP_BytesToKey is unsupported
 *   -iter N       PBKDF2 iterations (default 10000)
 *   -md DIGEST    PBKDF2 PRF (sha256 default; sha1/sha512 also accepted)
 *   -S HEX        explicit salt (hex). Default: 8 random bytes.
 *   -nosalt       no salt envelope; requires -S
 *   -a / -base64  base64 input/output
 *   -A            (with -a) single-line base64
 *   -in PATH      input file (default stdin)
 *   -out PATH     output file (default stdout)
 *
 * Encryption framing (when not -nosalt):
 *
 *   Salted__ || salt[8] || ciphertext [|| tag (GCM)]
 *
 * GCM tag is always 16 bytes appended after the ciphertext, before base64.
 */
@OptIn(ExperimentalEncodingApi::class, DelicateCryptographyApi::class)
internal object EncSubcommand {
    private val HELP =
        """
        Usage: openssl enc -<cipher> [-e|-d] [options]

        Ciphers: aes-128-cbc, aes-192-cbc, aes-256-cbc, aes-128-ctr,
                 aes-256-ctr, aes-128-gcm, aes-256-gcm

          -e               encrypt (default)
          -d               decrypt
          -in FILE         input file (default stdin)
          -out FILE        output file (default stdout)
          -a, -base64      base64 input/output
          -A               single-line base64
          -nopad           disable PKCS#7 padding (CBC only)

        Password-derived key (REQUIRES -pbkdf2):
          -k PASS          password literal
          -pass pass:P     password literal
          -pass env:V      read password from env var V
          -kfile FILE      read password from first line of FILE
          -pbkdf2          required for password mode
          -iter N          PBKDF2 iterations (default 10000)
          -md DIGEST       PBKDF2 PRF: sha256 (default), sha1, sha512
          -S HEX           explicit salt (8 bytes)
          -nosalt          no salt envelope; requires -S
          -p               print derived key and IV to stderr, then proceed
          -P               print derived key and IV, then exit

        Raw key mode (no password / no PBKDF2):
          -K HEX           raw cipher key (hex)
          -iv HEX          raw initialization vector (hex)

          -list            list supported ciphers
          -ciphers         alias for -list
          -help, -h        this help

        Not supported in this build: -z (zlib), -none (NULL cipher),
        -debug, -bufsize, -v, -saltlen, -provider*, -engine, -rand,
        -writerand, -skeymgmt/-skeyopt/-skeyuri.
        """.trimIndent() + "\n"

    private data class CipherSpec(
        val name: String,
        val keyBytes: Int,
        val ivBytes: Int,
        val mode: Mode,
    )

    private enum class Mode { CBC, CTR, GCM }

    private val CIPHERS: Map<String, CipherSpec> =
        mapOf(
            "aes-128-cbc" to CipherSpec("aes-128-cbc", 16, 16, Mode.CBC),
            "aes-192-cbc" to CipherSpec("aes-192-cbc", 24, 16, Mode.CBC),
            "aes-256-cbc" to CipherSpec("aes-256-cbc", 32, 16, Mode.CBC),
            "aes-128-ctr" to CipherSpec("aes-128-ctr", 16, 16, Mode.CTR),
            "aes-256-ctr" to CipherSpec("aes-256-ctr", 32, 16, Mode.CTR),
            "aes-128-gcm" to CipherSpec("aes-128-gcm", 16, 12, Mode.GCM),
            "aes-256-gcm" to CipherSpec("aes-256-gcm", 32, 12, Mode.GCM),
        )

    private val MAGIC: ByteArray =
        byteArrayOf(
            'S'.code.toByte(),
            'a'.code.toByte(),
            'l'.code.toByte(),
            't'.code.toByte(),
            'e'.code.toByte(),
            'd'.code.toByte(),
            '_'.code.toByte(),
            '_'.code.toByte(),
        )

    suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var cipherName: String? = null
        var decrypt = false
        var password: String? = null
        var pbkdf2 = false
        var iter = 10000
        var mdName = "sha256"
        var saltHex: String? = null
        var nosalt = false
        var nopad = false
        var base64Mode = false
        var oneLine = false
        var inFile: String? = null
        var outFile: String? = null
        var rawKeyHex: String? = null
        var rawIvHex: String? = null
        var printKeyIv = false
        var printAndExit = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-list" || a == "-ciphers" -> {
                    for (name in CIPHERS.keys.sorted()) ctx.stdout.writeLine(name)
                    return CommandResult()
                }

                a == "-e" -> {
                    decrypt = false
                }

                a == "-d" -> {
                    decrypt = true
                }

                a == "-pbkdf2" -> {
                    pbkdf2 = true
                }

                a == "-nosalt" -> {
                    nosalt = true
                }

                a == "-nopad" -> {
                    nopad = true
                }

                a == "-p" -> {
                    printKeyIv = true
                }

                a == "-P" -> {
                    printKeyIv = true
                    printAndExit = true
                }

                a == "-a" || a == "-base64" -> {
                    base64Mode = true
                }

                a == "-A" -> {
                    oneLine = true
                }

                a == "-k" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    password = args[i + 1]
                    i += 2
                    continue
                }

                a == "-pass" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    val spec = args[i + 1]
                    password =
                        when {
                            spec.startsWith("pass:") -> {
                                spec.removePrefix("pass:")
                            }

                            spec.startsWith("env:") -> {
                                val v = spec.removePrefix("env:")
                                ctx.process.env[v]
                                    ?: return usage(ctx, "-pass env:$v: variable not set")
                            }

                            else -> {
                                return usage(ctx, "-pass: unsupported source '$spec' (use pass: or env:)")
                            }
                        }
                    i += 2
                    continue
                }

                a == "-iter" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    iter = args[i + 1].toIntOrNull()
                        ?: return usage(ctx, "-iter: invalid integer '${args[i + 1]}'")
                    if (iter <= 0) return usage(ctx, "-iter: must be positive")
                    i += 2
                    continue
                }

                a == "-md" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    mdName = args[i + 1].lowercase()
                    i += 2
                    continue
                }

                a == "-S" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    saltHex = args[i + 1]
                    i += 2
                    continue
                }

                a == "-K" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    rawKeyHex = args[i + 1]
                    i += 2
                    continue
                }

                a == "-iv" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    rawIvHex = args[i + 1]
                    i += 2
                    continue
                }

                a == "-kfile" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    val path = resolvePath(ctx.process.cwd, args[i + 1])
                    password =
                        try {
                            val src = ctx.process.fs.source(path)
                            try {
                                val all = src.readAllBytes().decodeToString()
                                all.substringBefore('\n')
                            } finally {
                                src.close()
                            }
                        } catch (_: FileNotFound) {
                            return usage(ctx, "-kfile: $path: No such file or directory")
                        }
                    i += 2
                    continue
                }

                a == "-in" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    inFile = args[i + 1]
                    i += 2
                    continue
                }

                a == "-out" -> {
                    if (i + 1 >= args.size) return usage(ctx, "$a requires an argument")
                    outFile = args[i + 1]
                    i += 2
                    continue
                }

                a.startsWith("-") && a.length > 1 -> {
                    val cname = a.removePrefix("-").lowercase()
                    if (cname in CIPHERS) {
                        cipherName = cname
                    } else {
                        return usage(ctx, "unknown option or cipher: $a")
                    }
                }

                else -> {
                    return usage(ctx, "unexpected operand: $a")
                }
            }
            i++
        }

        val spec =
            CIPHERS[cipherName]
                ?: return usage(ctx, "no cipher selected (try -aes-256-cbc, -aes-256-gcm, ...)")

        val rawMode = rawKeyHex != null || rawIvHex != null
        if (rawMode) {
            if (password != null || pbkdf2 || saltHex != null) {
                return usage(ctx, "-K/-iv cannot be combined with password options")
            }
            if (rawKeyHex == null) return usage(ctx, "-K is required with -iv")
            if (rawIvHex == null && spec.mode != Mode.GCM) {
                return usage(ctx, "-iv is required with -K for ${spec.name}")
            }
        } else {
            if (!pbkdf2) {
                ctx.stderr.writeUtf8("openssl: enc: legacy MD5-KDF is not supported; pass -pbkdf2 (or use -K/-iv)\n")
                return CommandResult(exitCode = 1)
            }
            if (password == null) {
                ctx.stderr.writeUtf8(
                    "openssl: enc: no password supplied; use -k, -pass pass:..., -pass env:VAR, or -K/-iv\n",
                )
                return CommandResult(exitCode = 1)
            }
        }
        val mdAlg: CryptographyAlgorithmId<Digest> =
            when (mdName) {
                "sha256" -> SHA256
                "sha1" -> SHA1
                "sha512" -> SHA512
                else -> return usage(ctx, "-md: unsupported digest '$mdName' (sha256/sha1/sha512)")
            }
        if (nosalt && saltHex == null && !rawMode) {
            return usage(ctx, "-nosalt requires -S to supply a salt explicitly")
        }
        if (nopad && spec.mode != Mode.CBC) {
            return usage(ctx, "-nopad only applies to CBC ciphers")
        }

        val input: ByteArray =
            if (printAndExit) {
                ByteArray(0)
            } else {
                try {
                    readInput(ctx, inFile)
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("openssl enc: $inFile: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
            }

        val cooked: ByteArray =
            if (!printAndExit && decrypt && base64Mode) {
                try {
                    decodeBase64Lenient(input)
                } catch (e: IllegalArgumentException) {
                    ctx.stderr.writeUtf8("openssl enc: invalid base64 input: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                input
            }

        // Resolve key + iv (and maybe-print) before the encrypt/decrypt step.
        val resolvedKey: ByteArray
        val resolvedIv: ByteArray
        val saltBytes: ByteArray?
        try {
            if (rawMode) {
                resolvedKey = hexDecode(rawKeyHex!!)
                resolvedIv =
                    if (rawIvHex != null) {
                        hexDecode(rawIvHex)
                    } else {
                        // GCM with -K but no -iv: derive a deterministic zero IV (matches openssl behavior).
                        ByteArray(spec.ivBytes)
                    }
                saltBytes = null
            } else {
                val salt =
                    when {
                        saltHex != null -> {
                            hexDecode(saltHex)
                        }

                        nosalt -> {
                            ByteArray(0)
                        }

                        decrypt -> {
                            // Read header for salt — but we need to peek at cooked.
                            if (cooked.size < MAGIC.size + 8 || !cooked.startsWithMagic()) {
                                throw CryptoFailure("bad magic number")
                            }
                            cooked.copyOfRange(MAGIC.size, MAGIC.size + 8)
                        }

                        else -> {
                            CryptographyRandom.Default.nextBytes(8)
                        }
                    }
                val (k, iv) = deriveKeyIv(password!!, salt, spec, iter, mdAlg)
                resolvedKey = k
                resolvedIv = iv
                saltBytes = salt
            }
        } catch (e: CryptoFailure) {
            ctx.stderr.writeUtf8("openssl enc: ${e.message}\n")
            return CommandResult(exitCode = 1)
        }

        if (printKeyIv) {
            if (saltBytes != null && saltBytes.isNotEmpty()) {
                ctx.stderr.writeUtf8("salt=${bytesToHexUpper(saltBytes)}\n")
            }
            ctx.stderr.writeUtf8("key=${bytesToHexUpper(resolvedKey)}\n")
            ctx.stderr.writeUtf8("iv =${bytesToHexUpper(resolvedIv)}\n")
            if (printAndExit) return CommandResult()
        }

        val outBytes: ByteArray =
            try {
                if (decrypt) {
                    doDecryptResolved(cooked, resolvedKey, resolvedIv, spec, nopad, saltBytes, rawMode, nosalt)
                } else {
                    doEncryptResolved(cooked, resolvedKey, resolvedIv, spec, nopad, saltBytes, rawMode, nosalt)
                }
            } catch (e: CryptoFailure) {
                ctx.stderr.writeUtf8("openssl enc: ${e.message}\n")
                return CommandResult(exitCode = 1)
            } catch (e: Throwable) {
                ctx.stderr.writeUtf8("openssl enc: bad decrypt\n")
                return CommandResult(exitCode = 1)
            }

        val finalBytes: ByteArray =
            if (!decrypt && base64Mode) {
                val enc = Base64.encode(outBytes)
                val sb = StringBuilder()
                if (oneLine) {
                    sb.append(enc).append('\n')
                } else {
                    var p = 0
                    while (p < enc.length) {
                        val end = minOf(p + 76, enc.length)
                        sb.append(enc, p, end).append('\n')
                        p = end
                    }
                    if (enc.isEmpty()) sb.append('\n')
                }
                sb.toString().encodeToByteArray()
            } else {
                outBytes
            }

        return writeOutput(ctx, outFile, finalBytes)
    }

    private suspend fun doEncryptResolved(
        input: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        spec: CipherSpec,
        nopad: Boolean,
        salt: ByteArray?,
        rawMode: Boolean,
        nosalt: Boolean,
    ): ByteArray {
        val ciphertext = encryptCore(input, key, iv, spec, nopad)
        // Salted__ envelope only applies to password-derived mode, with salt and !-nosalt.
        return if (!rawMode && !nosalt && salt != null && salt.isNotEmpty()) {
            val saltForHeader = if (salt.size == 8) salt else padOrTruncate(salt, 8)
            ByteArray(MAGIC.size + 8 + ciphertext.size).also { out ->
                MAGIC.copyInto(out, 0)
                saltForHeader.copyInto(out, MAGIC.size)
                ciphertext.copyInto(out, MAGIC.size + 8)
            }
        } else {
            ciphertext
        }
    }

    private suspend fun doDecryptResolved(
        input: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        spec: CipherSpec,
        nopad: Boolean,
        salt: ByteArray?,
        rawMode: Boolean,
        nosalt: Boolean,
    ): ByteArray {
        val body: ByteArray =
            when {
                rawMode || nosalt -> {
                    input
                }

                input.size >= MAGIC.size + 8 && input.startsWithMagic() -> {
                    input.copyOfRange(MAGIC.size + 8, input.size)
                }

                else -> {
                    input
                }
            }
        return decryptCore(body, key, iv, spec, nopad)
    }

    private suspend fun deriveKeyIv(
        password: String,
        salt: ByteArray,
        spec: CipherSpec,
        iter: Int,
        md: CryptographyAlgorithmId<Digest>,
    ): Pair<ByteArray, ByteArray> {
        val total = spec.keyBytes + spec.ivBytes
        val pbk = cryptographyProvider().get(PBKDF2)
        val derivation = pbk.secretDerivation(md, iter, total.bytes, salt)
        val out = derivation.deriveSecretToByteArray(password.encodeToByteArray())
        val key = out.copyOfRange(0, spec.keyBytes)
        val iv = out.copyOfRange(spec.keyBytes, total)
        return key to iv
    }

    private suspend fun encryptCore(
        plaintext: ByteArray,
        keyBytes: ByteArray,
        iv: ByteArray,
        spec: CipherSpec,
        nopad: Boolean = false,
    ): ByteArray {
        val provider = cryptographyProvider()
        return when (spec.mode) {
            Mode.CBC -> {
                val key = provider.get(AES.CBC).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                if (nopad && plaintext.size % 16 != 0) {
                    throw CryptoFailure("-nopad: plaintext length not a multiple of 16")
                }
                key.cipher(padding = !nopad).encryptWithIv(iv, plaintext)
            }

            Mode.CTR -> {
                val key = provider.get(AES.CTR).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                key.cipher().encryptWithIv(iv, plaintext)
            }

            Mode.GCM -> {
                val key = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                // Returns ciphertext || tag(16 bytes), matching openssl's enc framing.
                key.cipher(tagSize = 128.bits).encryptWithIv(iv, plaintext)
            }
        }
    }

    private suspend fun decryptCore(
        body: ByteArray,
        keyBytes: ByteArray,
        iv: ByteArray,
        spec: CipherSpec,
        nopad: Boolean = false,
    ): ByteArray {
        val provider = cryptographyProvider()
        return try {
            when (spec.mode) {
                Mode.CBC -> {
                    val key = provider.get(AES.CBC).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                    key.cipher(padding = !nopad).decryptWithIv(iv, body)
                }

                Mode.CTR -> {
                    val key = provider.get(AES.CTR).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                    key.cipher().decryptWithIv(iv, body)
                }

                Mode.GCM -> {
                    val key = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, keyBytes)
                    key.cipher(tagSize = 128.bits).decryptWithIv(iv, body)
                }
            }
        } catch (e: Throwable) {
            throw CryptoFailure("bad decrypt")
        }
    }

    private fun bytesToHexUpper(b: ByteArray): String {
        val hex = "0123456789ABCDEF"
        val sb = StringBuilder(b.size * 2)
        for (byte in b) {
            val v = byte.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    private suspend fun readInput(
        ctx: CommandContext,
        inFile: String?,
    ): ByteArray =
        if (inFile != null) {
            val src = ctx.process.fs.source(resolvePath(ctx.process.cwd, inFile))
            try {
                src.readAllBytes()
            } finally {
                src.close()
            }
        } else {
            ctx.stdin.readAllBytes()
        }

    private suspend fun writeOutput(
        ctx: CommandContext,
        outFile: String?,
        bytes: ByteArray,
    ): CommandResult {
        val sink =
            if (outFile != null) {
                try {
                    ctx.process.fs.sink(resolvePath(ctx.process.cwd, outFile), append = false, mode = 0b110_100_100)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("openssl enc: cannot open -out: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                ctx.stdout
            }
        try {
            sink.writeBytes(bytes)
            sink.flush()
        } finally {
            if (outFile != null) sink.close()
        }
        return CommandResult(exitCode = 0)
    }

    private suspend fun usage(
        ctx: CommandContext,
        msg: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("openssl enc: $msg\n")
        return CommandResult(exitCode = 1)
    }

    private class CryptoFailure(
        message: String,
    ) : RuntimeException(message)

    private fun ByteArray.startsWithMagic(): Boolean {
        if (this.size < MAGIC.size) return false
        for (i in MAGIC.indices) if (this[i] != MAGIC[i]) return false
        return true
    }

    private fun padOrTruncate(
        src: ByteArray,
        n: Int,
    ): ByteArray {
        val out = ByteArray(n)
        src.copyInto(out, 0, 0, minOf(src.size, n))
        return out
    }

    private fun hexDecode(s: String): ByteArray {
        require(s.length % 2 == 0) { "salt hex must have even length" }
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = s[i * 2].digitToInt(16)
            val lo = s[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun decodeBase64Lenient(bytes: ByteArray): ByteArray {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val c = (b.toInt() and 0xff).toChar()
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            sb.append(c)
        }
        return Base64.decode(sb.toString())
    }
}
