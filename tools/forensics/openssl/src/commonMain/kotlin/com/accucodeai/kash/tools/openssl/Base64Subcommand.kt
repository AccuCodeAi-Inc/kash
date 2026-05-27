package com.accucodeai.kash.tools.openssl

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * `openssl base64` — encode/decode base64.
 *
 *   -e        encode (default)
 *   -d / -D   decode
 *   -A        single-line (no 64-col wrap)
 *   -in FILE  read from FILE instead of stdin
 *   -out FILE write to FILE instead of stdout
 *
 * Default encode wraps at 64 columns (matching OpenSSL); -A turns wrapping
 * off. Decode tolerates whitespace (newlines/spaces) but rejects other
 * non-alphabet characters.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Subcommand {
    private val HELP =
        """
        Usage: openssl base64 [-e|-d] [-A] [-in FILE] [-out FILE]

          -e         encode (default)
          -d, -D     decode
          -A         single-line output (no 64-col wrap on encode;
                     also tolerates a single-line block on decode)
          -in FILE   read from FILE instead of stdin
          -out FILE  write to FILE instead of stdout
          -help, -h  this help

        Alias for 'openssl enc -a' with no cipher.
        """.trimIndent() + "\n"

    suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var decode = false
        var oneLine = false
        var inFile: String? = null
        var outFile: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-help" || a == "-h" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-e" -> {
                    decode = false
                }

                a == "-d" || a == "-D" -> {
                    decode = true
                }

                a == "-A" -> {
                    oneLine = true
                }

                a == "-in" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl base64: -in requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    inFile = args[i + 1]
                    i += 2
                    continue
                }

                a == "-out" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("openssl base64: -out requires an argument\n")
                        return CommandResult(exitCode = 1)
                    }
                    outFile = args[i + 1]
                    i += 2
                    continue
                }

                a == "--" -> {
                    // openssl tolerates trailing garbage here; we just stop.
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("openssl base64: unknown option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    ctx.stderr.writeUtf8("openssl base64: unexpected operand: $a\n")
                    return CommandResult(exitCode = 1)
                }
            }
            i++
        }

        val input: ByteArray =
            if (inFile != null) {
                val path = resolvePath(ctx.process.cwd, inFile)
                try {
                    val src = ctx.process.fs.source(path)
                    try {
                        src.readAllBytes()
                    } finally {
                        src.close()
                    }
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("openssl base64: $inFile: No such file or directory\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                ctx.stdin.readAllBytes()
            }

        val sink =
            if (outFile != null) {
                try {
                    val path = resolvePath(ctx.process.cwd, outFile)
                    ctx.process.fs.sink(path, append = false, mode = 0b110_100_100)
                } catch (e: Throwable) {
                    ctx.stderr.writeUtf8("openssl base64: cannot open -out: ${e.message}\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                ctx.stdout
            }

        try {
            if (decode) {
                // Strip whitespace; reject anything else outside the alphabet.
                val cleaned = StringBuilder()
                for (b in input) {
                    val c = (b.toInt() and 0xff).toChar()
                    if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
                    cleaned.append(c)
                }
                val decoded =
                    try {
                        Base64.decode(cleaned.toString())
                    } catch (e: IllegalArgumentException) {
                        ctx.stderr.writeUtf8("openssl base64: invalid base64 input\n")
                        return CommandResult(exitCode = 1)
                    }
                sink.writeBytes(decoded)
            } else {
                val encoded = Base64.encode(input)
                if (oneLine) {
                    sink.writeUtf8(encoded)
                    sink.writeUtf8("\n")
                } else {
                    var p = 0
                    while (p < encoded.length) {
                        val end = minOf(p + 64, encoded.length)
                        sink.writeUtf8(encoded.substring(p, end))
                        sink.writeUtf8("\n")
                        p = end
                    }
                    if (encoded.isEmpty()) sink.writeUtf8("\n")
                }
            }
            sink.flush()
        } finally {
            if (outFile != null) sink.close()
        }
        return CommandResult(exitCode = 0)
    }
}
