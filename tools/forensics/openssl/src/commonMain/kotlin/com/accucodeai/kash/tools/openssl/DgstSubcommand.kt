package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.hash.HashAlg
import com.accucodeai.kash.hash.hashStream
import dev.whyoleg.cryptography.CryptographyAlgorithmId
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.MD5
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA224
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.SHA384
import dev.whyoleg.cryptography.algorithms.SHA512

// openssl dgst -<alg> hex/binary output, optional -out file, optional files.
// stdin produces bare hex; named files produce "ALG(file)= hex"; -binary
// emits the raw digest bytes with no label.
@OptIn(DelicateCryptographyApi::class)
internal object DgstSubcommand {
    private val ALGS: Map<String, Pair<HashAlg, String>> =
        mapOf(
            "md5" to (HashAlg.MD5 to "MD5"),
            "sha1" to (HashAlg.SHA1 to "SHA1"),
            "sha224" to (HashAlg.SHA224 to "SHA224"),
            "sha256" to (HashAlg.SHA256 to "SHA256"),
            "sha384" to (HashAlg.SHA384 to "SHA384"),
            "sha512" to (HashAlg.SHA512 to "SHA512"),
        )

    private val HELP =
        """
        Usage: openssl dgst [-<alg>] [-hex|-binary] [-c] [-r]
                            [-out FILE] [-hmac KEY] [file...]

          -<alg>            md5, sha1, sha224, sha256 (default), sha384, sha512
          -hex              hex output (default)
          -binary           raw digest bytes
          -c                colon-separated hex (every two digits)
          -r                coreutils format: "<hex>  <name>"
          -out FILE         write to FILE instead of stdout
          -hmac KEY         HMAC with KEY (raw bytes)
          -hmac-env VAR     HMAC with key from environment variable VAR
          -hmac-stdin       HMAC with key from stdin
          -list             list supported digest algorithms
          -help, -h         this help

        Algorithm shortcuts: 'openssl sha256 file' is equivalent to
        'openssl dgst -sha256 file'.

        Not supported in this build: -sign/-verify/-prverify/-signature (RSA/EC),
        -mac/-macopt (use -hmac for HMAC), -xoflen (no SHAKE), -fips-fingerprint,
        -engine, -provider*, -rand/-writerand. Deferred to v3.
        """.trimIndent() + "\n"

    suspend fun run(
        args: List<String>,
        ctx: CommandContext,
        fixedAlg: String?,
    ): CommandResult {
        var alg: String = fixedAlg ?: "sha256"
        var binary = false
        var colon = false
        var coreutils = false
        var outFile: String? = null
        var hmacKey: String? = null
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-list" -> {
                    for (name in ALGS.keys.sorted()) ctx.stdout.writeLine(name)
                    return CommandResult()
                }

                a == "-c" -> {
                    colon = true
                }

                a == "-r" -> {
                    coreutils = true
                }

                a == "--" -> {
                    i++
                    while (i < args.size) {
                        files += args[i]
                        i++
                    }
                    continue
                }

                a == "-hex" -> {
                    binary = false
                }

                a == "-binary" -> {
                    binary = true
                }

                a == "-out" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl dgst: -out requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    outFile = args[i + 1]
                    i += 2
                    continue
                }

                a == "-hmac" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl dgst: -hmac requires a key argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    hmacKey = args[i + 1]
                    i += 2
                    continue
                }

                a == "-hmac-env" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl dgst: -hmac-env requires a variable name\n")
                        return CommandResult(exitCode = 1)
                    }
                    val varName = args[i + 1]
                    val v = ctx.process.env[varName]
                    if (v == null) {
                        ctx.stderr.writeUtf8("openssl dgst: -hmac-env $varName: variable not set\n")
                        return CommandResult(exitCode = 1)
                    }
                    hmacKey = v
                    i += 2
                    continue
                }

                a == "-hmac-stdin" -> {
                    // First line of stdin, no trailing newline.
                    val all = ctx.stdin.readAllBytes()
                    val text = all.decodeToString()
                    hmacKey = text.substringBefore('\n')
                }

                a == "-mac" || a == "-macopt" -> {
                    ctx.stderr.writeUtf8("openssl dgst: $a is not yet supported in this build\n")
                    return CommandResult(exitCode = 1)
                }

                a.startsWith("-") && a.length > 1 -> {
                    val tag = a.substring(1).lowercase()
                    if (tag in ALGS) {
                        alg = tag
                    } else {
                        ctx.stderr.writeUtf8("openssl dgst: unknown option or algorithm: $a\n")
                        return CommandResult(exitCode = 1)
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        val pair = ALGS[alg]!!
        val hashAlg = pair.first
        val label = pair.second

        val outSink: SuspendSink = openOutSink(ctx, outFile) ?: return CommandResult(exitCode = 1)

        var anyErr = false
        try {
            if (hmacKey != null) {
                val digestId = digestIdFor(alg)
                if (digestId == null) {
                    ctx.stderr.writeUtf8("openssl dgst: -hmac with $alg is not supported\n")
                    return CommandResult(exitCode = 1)
                }
                val macLabel = "HMAC-$label"
                if (files.isEmpty()) {
                    val hex = computeHmac(hmacKey, digestId, ctx.stdin.readAllBytes())
                    emit(outSink, binary, colon, coreutils, hex, macLabel, null)
                } else {
                    for (arg in files) {
                        val bytes = readBytes(arg, ctx)
                        if (bytes == null) {
                            anyErr = true
                        } else {
                            val hex = computeHmac(hmacKey, digestId, bytes)
                            val displayName = if (arg == "-") "stdin" else arg
                            emit(outSink, binary, colon, coreutils, hex, macLabel, displayName)
                        }
                    }
                }
            } else if (files.isEmpty()) {
                val hex = hashStream(hashAlg, ctx.stdin)
                emit(outSink, binary, colon, coreutils, hex, label, null)
            } else {
                for (arg in files) {
                    val hex = computeOne(arg, hashAlg, ctx)
                    if (hex == null) {
                        anyErr = true
                    } else {
                        val displayName = if (arg == "-") "stdin" else arg
                        emit(outSink, binary, colon, coreutils, hex, label, displayName)
                    }
                }
            }
            outSink.flush()
        } finally {
            if (outFile != null) outSink.close()
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    private fun digestIdFor(alg: String): CryptographyAlgorithmId<Digest>? =
        when (alg) {
            "md5" -> MD5
            "sha1" -> SHA1
            "sha224" -> SHA224
            "sha256" -> SHA256
            "sha384" -> SHA384
            "sha512" -> SHA512
            else -> null
        }

    private suspend fun computeHmac(
        key: String,
        digest: CryptographyAlgorithmId<Digest>,
        data: ByteArray,
    ): String {
        val hmac = cryptographyProvider().get(HMAC)
        val hk = hmac.keyDecoder(digest).decodeFromByteArray(HMAC.Key.Format.RAW, key.encodeToByteArray())
        val sig = hk.signatureGenerator().generateSignature(data)
        return bytesToHex(sig)
    }

    private suspend fun readBytes(
        arg: String,
        ctx: CommandContext,
    ): ByteArray? {
        if (arg == "-") return ctx.stdin.readAllBytes()
        val path = resolvePath(ctx.process.cwd, arg)
        return try {
            val src = ctx.process.fs.source(path)
            try {
                src.readAllBytes()
            } finally {
                src.close()
            }
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("openssl dgst: $arg: No such file or directory\n")
            null
        }
    }

    private fun bytesToHex(b: ByteArray): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (byte in b) {
            val v = byte.toInt() and 0xff
            sb.append(hex[v ushr 4]).append(hex[v and 0x0f])
        }
        return sb.toString()
    }

    private suspend fun openOutSink(
        ctx: CommandContext,
        outFile: String?,
    ): SuspendSink? {
        if (outFile == null) return ctx.stdout
        return try {
            val path = resolvePath(ctx.process.cwd, outFile)
            ctx.process.fs.sink(path, append = false, mode = 0b110_100_100)
        } catch (e: Throwable) {
            ctx.stderr.writeUtf8("openssl dgst: cannot open -out: ${e.message}\n")
            null
        }
    }

    private suspend fun computeOne(
        arg: String,
        hashAlg: HashAlg,
        ctx: CommandContext,
    ): String? {
        if (arg == "-") return hashStream(hashAlg, ctx.stdin)
        val path = resolvePath(ctx.process.cwd, arg)
        return try {
            val src = ctx.process.fs.source(path)
            val r = hashStream(hashAlg, src)
            src.close()
            r
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("openssl dgst: $arg: No such file or directory\n")
            null
        }
    }

    private suspend fun emit(
        sink: SuspendSink,
        binary: Boolean,
        colon: Boolean,
        coreutils: Boolean,
        hex: String,
        label: String,
        fname: String?,
    ) {
        val rendered = if (colon) hex.chunked(2).joinToString(":") else hex
        when {
            binary -> sink.writeBytes(hexToBytes(hex))
            coreutils -> sink.writeLine(if (fname == null) "$rendered  -" else "$rendered  $fname")
            fname == null -> sink.writeLine(rendered)
            else -> sink.writeLine("$label($fname)= $rendered")
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }
}
