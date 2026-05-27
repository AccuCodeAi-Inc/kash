package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import dev.whyoleg.cryptography.random.CryptographyRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * `openssl rand [-hex] [-base64] [-A] [-out FILE] NUM` — emit NUM CSPRNG bytes.
 *
 * Default: raw bytes to stdout.
 * `-hex`     lowercase hex (one line, no wrapping — matches openssl).
 * `-base64`  base64. Wrapped at 64 cols unless `-A` is given.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object RandSubcommand {
    private val HELP =
        """
        Usage: openssl rand [-out FILE] [-base64|-hex] [-A] NUM

          -hex       output as lowercase hex (no wrap)
          -base64    output as base64 (64-col wrap unless -A)
          -A         single-line base64
          -out FILE  write to FILE instead of stdout
          -help, -h  this help

        NUM is the number of bytes. Accepts suffixes K, M, G, T (binary
        multiples). Default output is raw bytes.

        Not supported in this build: -rand, -writerand, -provider*.
        """.trimIndent() + "\n"

    suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var hex = false
        var b64 = false
        var oneLine = false
        var outFile: String? = null
        var num: Int? = null

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-hex" -> {
                    hex = true
                }

                a == "-base64" -> {
                    b64 = true
                }

                a == "-A" -> {
                    oneLine = true
                }

                a == "-out" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl rand: -out requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    outFile = args[i + 1]
                    i += 2
                    continue
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    ctx.stderr.writeUtf8("openssl rand: unknown option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    if (num != null) {
                        ctx.stderr.writeUtf8("openssl rand: only one length argument allowed\n")
                        return CommandResult(exitCode = 1)
                    }
                    num = parseSize(a)
                    if (num == null || num < 0) {
                        ctx.stderr.writeUtf8("openssl rand: invalid length '$a'\n")
                        return CommandResult(exitCode = 1)
                    }
                }
            }
            i++
        }
        if (num == null) {
            ctx.stderr.writeUtf8("openssl rand: missing length argument\n")
            return CommandResult(exitCode = 1)
        }
        if (hex && b64) {
            ctx.stderr.writeUtf8("openssl rand: -hex and -base64 are mutually exclusive\n")
            return CommandResult(exitCode = 1)
        }

        val bytes = CryptographyRandom.Default.nextBytes(num)

        val payload: ByteArray =
            when {
                hex -> {
                    bytesToHex(bytes).encodeToByteArray()
                }

                b64 -> {
                    val enc = Base64.encode(bytes)
                    val sb = StringBuilder()
                    if (oneLine) {
                        sb.append(enc).append('\n')
                    } else {
                        var p = 0
                        while (p < enc.length) {
                            val end = minOf(p + 64, enc.length)
                            sb.append(enc, p, end).append('\n')
                            p = end
                        }
                        if (enc.isEmpty()) sb.append('\n')
                    }
                    sb.toString().encodeToByteArray()
                }

                else -> {
                    bytes
                }
            }

        val sink =
            if (outFile != null) {
                try {
                    ctx.process.fs.sink(resolvePath(ctx.process.cwd, outFile), append = false, mode = 0b110_100_100)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("openssl rand: cannot open -out: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                ctx.stdout
            }
        try {
            sink.writeBytes(payload)
            sink.flush()
        } finally {
            if (outFile != null) sink.close()
        }
        return CommandResult(exitCode = 0)
    }

    private fun parseSize(s: String): Int? {
        if (s.isEmpty()) return null
        val last = s.last()
        val mult: Long =
            when (last) {
                'K', 'k' -> 1L shl 10
                'M', 'm' -> 1L shl 20
                'G', 'g' -> 1L shl 30
                'T', 't' -> 1L shl 40
                else -> 0L
            }
        val n =
            if (mult == 0L) {
                s.toLongOrNull() ?: return null
            } else {
                val head = s.substring(0, s.length - 1).toLongOrNull() ?: return null
                head * mult
            }
        if (n < 0 || n > Int.MAX_VALUE) return null
        return n.toInt()
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
}
