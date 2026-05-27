package com.accucodeai.kash.tools.hexdump

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

private val HELP =
    """
    Usage: hexdump [-bcCdovx] [-n LEN] [-s OFFSET] [file ...]

      -b           one-byte octal display
      -c           one-byte character display
      -C           canonical hex+ASCII display (the common form)
      -d           two-byte decimal display
      -o           two-byte octal display
      -x           two-byte hex display (default)
      -n LEN       interpret only LEN bytes
      -s OFFSET    skip OFFSET bytes from input start
                   prefixes: 0x.. hex, 0.. octal, else decimal
                   suffixes: b (×512), k (×1024), m (×1048576), g (×1073741824)
      -v           do not collapse repeated lines to '*'
      -h, --help   this help

    Not supported in this build: -e FORMAT_STRING, -f FORMAT_FILE.
    """.trimIndent() + "\n"

private enum class Mode { X, B, C_CHAR, BIG_C, D, O }

public class HexdumpCommand :
    Command,
    CommandSpec {
    override val name: String = "hexdump"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mode = Mode.X
        var skip = 0L
        var limit = -1L
        var verbose = false
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
            when (a) {
                "--" -> {
                    endOfOpts = true
                }

                "-h", "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                "-b" -> {
                    mode = Mode.B
                }

                "-c" -> {
                    mode = Mode.C_CHAR
                }

                "-C" -> {
                    mode = Mode.BIG_C
                }

                "-d" -> {
                    mode = Mode.D
                }

                "-o" -> {
                    mode = Mode.O
                }

                "-x" -> {
                    mode = Mode.X
                }

                "-v" -> {
                    verbose = true
                }

                "-n" -> {
                    val v = args.getOrNull(i + 1)?.let { parseSize(it) }
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("hexdump: invalid -n value\n")
                        return CommandResult(exitCode = 1)
                    }
                    limit = v
                    i += 2
                    continue
                }

                "-s" -> {
                    val v = args.getOrNull(i + 1)?.let { parseSize(it) }
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("hexdump: invalid -s value\n")
                        return CommandResult(exitCode = 1)
                    }
                    skip = v
                    i += 2
                    continue
                }

                "-e", "-f" -> {
                    ctx.stderr.writeUtf8("hexdump: $a (format strings) is not supported in this build\n")
                    return CommandResult(exitCode = 1)
                }

                "-" -> {
                    files += a
                }

                else -> {
                    if (a.startsWith("-") && a.length > 1) {
                        // Allow stacked flags like -Cv.
                        if (a.all { it == '-' || it in "bcCdovx" }) {
                            for (c in a.substring(1)) {
                                when (c) {
                                    'b' -> mode = Mode.B
                                    'c' -> mode = Mode.C_CHAR
                                    'C' -> mode = Mode.BIG_C
                                    'd' -> mode = Mode.D
                                    'o' -> mode = Mode.O
                                    'x' -> mode = Mode.X
                                    'v' -> verbose = true
                                }
                            }
                        } else {
                            ctx.stderr.writeUtf8("hexdump: unknown option: $a\n")
                            return CommandResult(exitCode = 1)
                        }
                    } else {
                        files += a
                    }
                }
            }
            i++
        }

        val input = readAllInput(files, ctx) ?: return CommandResult(exitCode = 1)
        val sliced = sliceInput(input, skip, limit)
        val text = render(sliced, mode, verbose, baseOffset = skip)
        ctx.stdout.writeUtf8(text)
        return CommandResult()
    }

    private suspend fun readAllInput(
        files: List<String>,
        ctx: CommandContext,
    ): ByteArray? {
        if (files.isEmpty()) return ctx.stdin.readAllBytes()
        val out = mutableListOf<Byte>()
        for (f in files) {
            val bytes =
                if (f == "-") {
                    ctx.stdin.readAllBytes()
                } else {
                    val path = resolvePath(ctx.process.cwd, f)
                    try {
                        val src = ctx.process.fs.source(path)
                        try {
                            src.readAllBytes()
                        } finally {
                            src.close()
                        }
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("hexdump: $f: No such file or directory\n")
                        return null
                    }
                }
            for (b in bytes) out += b
        }
        return out.toByteArray()
    }

    private fun sliceInput(
        input: ByteArray,
        skip: Long,
        limit: Long,
    ): ByteArray {
        val start = minOf(skip.coerceAtLeast(0).toInt(), input.size)
        val end = if (limit < 0) input.size else minOf(start + limit.toInt(), input.size)
        if (start == 0 && end == input.size) return input
        return input.copyOfRange(start, end)
    }

    private fun render(
        input: ByteArray,
        mode: Mode,
        verbose: Boolean,
        baseOffset: Long,
    ): String {
        val sb = StringBuilder()
        val lineSize = 16
        var off = 0
        var prevLineContent: String? = null
        var starEmitted = false
        while (off < input.size) {
            val end = minOf(off + lineSize, input.size)
            val lineBytes = input.copyOfRange(off, end)
            val content = formatLineContent(lineBytes, mode, lineSize)
            val isFullLine = lineBytes.size == lineSize
            if (!verbose && isFullLine && content == prevLineContent) {
                if (!starEmitted) {
                    sb.append("*\n")
                    starEmitted = true
                }
            } else {
                starEmitted = false
                val offHex = offsetStr(baseOffset + off, mode)
                sb.append(offHex)
                if (content.isNotEmpty()) sb.append(content)
                sb.append('\n')
                prevLineContent = if (isFullLine) content else null
            }
            off = end
        }
        // Trailing line shows total bytes read in the same offset format.
        // Empty input produces no output at all (matches BSD/util-linux hexdump).
        if (input.isNotEmpty()) {
            sb.append(offsetStr(baseOffset + input.size, mode))
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun offsetStr(
        v: Long,
        mode: Mode,
    ): String {
        val width = if (mode == Mode.BIG_C) 8 else 7
        return v.toString(16).padStart(width, '0')
    }

    private fun formatLineContent(
        bytes: ByteArray,
        mode: Mode,
        lineSize: Int,
    ): String {
        val sb = StringBuilder()
        when (mode) {
            Mode.BIG_C -> {
                // "  XX XX XX XX XX XX XX XX  XX XX XX XX XX XX XX XX  |ASCII|"
                sb.append(' ')
                for (j in 0 until lineSize) {
                    if (j == 8) sb.append(' ')
                    if (j < bytes.size) {
                        val b = bytes[j].toInt() and 0xff
                        sb.append(' ')
                        sb.append(HEX_LO[b ushr 4])
                        sb.append(HEX_LO[b and 0x0f])
                    } else {
                        sb.append("   ")
                    }
                }
                sb.append("  |")
                for (b in bytes) {
                    val v = b.toInt() and 0xff
                    sb.append(if (v in 0x20..0x7e) v.toChar() else '.')
                }
                sb.append('|')
            }

            Mode.X -> {
                // "    WWWW    WWWW ..." where W = 4-hex little-endian 16-bit word.
                for (k in 0 until lineSize / 2) {
                    sb.append("    ")
                    val byteIdx = k * 2
                    if (byteIdx + 1 < bytes.size) {
                        val lo = bytes[byteIdx].toInt() and 0xff
                        val hi = bytes[byteIdx + 1].toInt() and 0xff
                        val v = (hi shl 8) or lo
                        sb.append(v.toString(16).padStart(4, '0'))
                    } else if (byteIdx < bytes.size) {
                        // odd trailing byte: pad high byte with zero.
                        val lo = bytes[byteIdx].toInt() and 0xff
                        sb.append(lo.toString(16).padStart(4, '0'))
                    } else {
                        sb.append("    ")
                    }
                }
            }

            Mode.D -> {
                // "   DDDDD" per 16-bit little-endian unsigned decimal (zero-padded 5 wide, 3 leading spaces).
                for (k in 0 until lineSize / 2) {
                    sb.append("   ")
                    val byteIdx = k * 2
                    if (byteIdx + 1 < bytes.size) {
                        val lo = bytes[byteIdx].toInt() and 0xff
                        val hi = bytes[byteIdx + 1].toInt() and 0xff
                        val v = (hi shl 8) or lo
                        sb.append(v.toString().padStart(5, '0'))
                    } else if (byteIdx < bytes.size) {
                        val lo = bytes[byteIdx].toInt() and 0xff
                        sb.append(lo.toString().padStart(5, '0'))
                    } else {
                        sb.append("     ")
                    }
                }
            }

            Mode.O -> {
                // "  OOOOOO" per 16-bit little-endian octal (6 zero-padded, 2 leading spaces).
                for (k in 0 until lineSize / 2) {
                    sb.append("  ")
                    val byteIdx = k * 2
                    if (byteIdx + 1 < bytes.size) {
                        val lo = bytes[byteIdx].toInt() and 0xff
                        val hi = bytes[byteIdx + 1].toInt() and 0xff
                        val v = (hi shl 8) or lo
                        sb.append(v.toString(8).padStart(6, '0'))
                    } else if (byteIdx < bytes.size) {
                        val lo = bytes[byteIdx].toInt() and 0xff
                        sb.append(lo.toString(8).padStart(6, '0'))
                    } else {
                        sb.append("      ")
                    }
                }
            }

            Mode.B -> {
                // " ooo" per byte (3 zero-padded octal, 1 leading space).
                for (j in 0 until lineSize) {
                    sb.append(' ')
                    if (j < bytes.size) {
                        val v = bytes[j].toInt() and 0xff
                        sb.append(v.toString(8).padStart(3, '0'))
                    } else {
                        sb.append("   ")
                    }
                }
            }

            Mode.C_CHAR -> {
                // " %3c" per byte. Escapes for low control chars, otherwise printable
                // chars as-is; high bytes shown as 3-digit octal.
                for (j in 0 until lineSize) {
                    sb.append(' ')
                    if (j < bytes.size) {
                        val v = bytes[j].toInt() and 0xff
                        val rendered = renderCharCell(v)
                        sb.append(rendered.padStart(3, ' '))
                    } else {
                        sb.append("   ")
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun renderCharCell(v: Int): String =
        when (v) {
            0x00 -> "\\0"
            0x07 -> "\\a"
            0x08 -> "\\b"
            0x09 -> "\\t"
            0x0a -> "\\n"
            0x0b -> "\\v"
            0x0c -> "\\f"
            0x0d -> "\\r"
            in 0x20..0x7e -> v.toChar().toString()
            else -> v.toString(8).padStart(3, '0')
        }

    private companion object {
        const val HEX_LO = "0123456789abcdef"
    }
}

/**
 * Parse a size per hexdump(1): 0x.. hex, leading 0 octal, else decimal; with
 * optional trailing b/k/m/g multipliers (512 / 1024 / 1048576 / 1073741824).
 */
internal fun parseSize(s: String): Long? {
    if (s.isEmpty()) return null
    val last = s.last()
    val (head, mult) =
        when (last) {
            'b' -> s.dropLast(1) to 512L
            'k' -> s.dropLast(1) to 1024L
            'm' -> s.dropLast(1) to 1048576L
            'g' -> s.dropLast(1) to 1073741824L
            else -> s to 1L
        }
    if (head.isEmpty()) return null
    val v =
        when {
            head.startsWith("0x") || head.startsWith("0X") -> head.substring(2).toLongOrNull(16)
            head.length > 1 && head[0] == '0' -> head.toLongOrNull(8)
            else -> head.toLongOrNull(10)
        } ?: return null
    if (v < 0) return null
    return v * mult
}
