package com.accucodeai.kash.tools.base64

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

/**
 * `base64` — base64 encode or decode bytes.
 *
 * - Default: encode stdin (or file operand) and write a wrapped block to stdout
 *   with a trailing newline. Wrap column defaults to 76; `-w 0` disables wrap.
 * - `-d` / `--decode`: decode input. By default, any character outside the
 *   standard alphabet is an error; `-i` / `--ignore-garbage` filters them
 *   silently (including embedded whitespace).
 * - File operand of `-` (or no operand) reads from stdin.
 */
public class Base64Command :
    Command,
    CommandSpec {
    override val name: String = "base64"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var decode = false
        var ignoreGarbage = false
        var wrap = 76
        val files = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-d" || a == "--decode" -> {
                    decode = true
                }

                a == "-i" || a == "--ignore-garbage" -> {
                    ignoreGarbage = true
                }

                a == "-w" || a == "--wrap" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("base64: option requires an argument -- w\n")
                        return CommandResult(exitCode = 2)
                    }
                    val v = args[i + 1].toIntOrNull()
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("base64: invalid wrap size: '${args[i + 1]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    wrap = v
                    i += 2
                    continue
                }

                a.startsWith("--wrap=") -> {
                    val v = a.substring("--wrap=".length).toIntOrNull()
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("base64: invalid wrap size: '${a.substring("--wrap=".length)}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    wrap = v
                }

                a.startsWith("-w") && a.length > 2 -> {
                    val v = a.substring(2).toIntOrNull()
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("base64: invalid wrap size: '${a.substring(2)}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    wrap = v
                }

                a == "-" -> {
                    files += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("base64: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        if (files.size > 1) {
            ctx.stderr.writeUtf8("base64: extra operand '${files[1]}'\n")
            return CommandResult(exitCode = 2)
        }

        val input: ByteArray =
            if (files.isEmpty() || files[0] == "-") {
                readAllBytes(ctx.stdin)
            } else {
                val path = resolvePath(ctx.process.cwd, files[0])
                val src: SuspendSource =
                    try {
                        ctx.process.fs.source(path)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("base64: ${files[0]}: No such file or directory\n")
                        return CommandResult(exitCode = 1)
                    }
                try {
                    readAllBytes(src)
                } finally {
                    src.close()
                }
            }

        return if (decode) {
            doDecode(input, ignoreGarbage, ctx)
        } else {
            doEncode(input, wrap, ctx)
        }
    }

    private suspend fun doEncode(
        input: ByteArray,
        wrap: Int,
        ctx: CommandContext,
    ): CommandResult {
        val encoded = base64Encode(input)
        val sb = StringBuilder()
        if (wrap == 0) {
            sb.append(encoded)
        } else {
            var i = 0
            while (i < encoded.length) {
                val end = minOf(i + wrap, encoded.length)
                sb.append(encoded, i, end)
                sb.append('\n')
                i = end
            }
        }
        // Trailing newline after the wrapped block (or after the unwrapped output).
        if (sb.isEmpty() || sb[sb.length - 1] != '\n') sb.append('\n')
        ctx.stdout.writeUtf8(sb.toString())
        return CommandResult()
    }

    private suspend fun doDecode(
        input: ByteArray,
        ignoreGarbage: Boolean,
        ctx: CommandContext,
    ): CommandResult {
        // Convert input bytes to a string of ASCII characters. base64 alphabet
        // is ASCII; treat any byte as its low-8-bit char for filtering.
        val raw =
            buildString(input.size) {
                for (b in input) append((b.toInt() and 0xff).toChar())
            }
        val cleaned: String =
            if (ignoreGarbage) {
                val sb = StringBuilder(raw.length)
                for (c in raw) {
                    if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                        c == '+' || c == '/' || c == '='
                    ) {
                        sb.append(c)
                    }
                }
                sb.toString()
            } else {
                // Strict: only strip newlines/CR (coreutils strict mode still
                // tolerates line breaks; everything else is invalid).
                val sb = StringBuilder(raw.length)
                for (c in raw) {
                    if (c == '\n' || c == '\r') continue
                    sb.append(c)
                }
                sb.toString()
            }
        val bytes: ByteArray =
            try {
                base64DecodeStrict(cleaned)
            } catch (e: IllegalArgumentException) {
                ctx.stderr.writeUtf8("base64: invalid input\n")
                return CommandResult(exitCode = 1)
            }
        ctx.stdout.writeBytes(bytes)
        return CommandResult()
    }
}
