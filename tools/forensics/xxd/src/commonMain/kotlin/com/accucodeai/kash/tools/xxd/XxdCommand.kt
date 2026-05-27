package com.accucodeai.kash.tools.xxd

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

private fun resolvePath(
    cwd: String,
    path: String,
): String = if (path.startsWith("/")) path else "$cwd/$path"

private val HELP_TEXT =
    """
    Usage: xxd [options] [infile [outfile]]
           xxd -r[evert] [options] [infile [outfile]]
           xxd -h[elp]

    Make a hex dump or revert one to bytes. Reads stdin / writes stdout if
    infile / outfile are omitted or '-'.

    Options:
      -a, -autoskip      replace runs of NUL lines with a single '*'
      -c, -cols COLS     bytes per line (default 16; -p default 30)
      -d                 show offsets in decimal instead of hex
      -e                 little-endian hex dump (default group size 4)
      -g, -groupsize N   group hex bytes (default 2; -e default 4; 0 = no grouping)
      -h, -help          this help and exit
      -l, -len LEN       stop after LEN bytes of input
      -o OFFSET          add OFFSET to displayed file position
      -p, -ps, -plain    plain hex dump (no offsets, no ASCII column)
      -r, -revert        reverse: parse hex dump back to bytes
      -s [+|-]N          start at byte offset N (negative = from end)
      -u                 uppercase hex output
      -v, -version       version and exit

    Numbers accept decimal, hexadecimal (0x..), or octal (0..) form.

    Not supported in this build: -b/-bits, -i/-include, -E/-EBCDIC, -C, -n,
    -R, -seek.
    """.trimIndent()

private const val VERSION = "kash-xxd 0.2"

/**
 * Parse a number per xxd's man page: 0x.. hex, leading 0 octal, else decimal.
 * Accepts an optional leading sign for offsets.
 */
private fun parseNum(s: String): Long? {
    if (s.isEmpty()) return null
    val (sign, rest) =
        when (s[0]) {
            '+' -> 1L to s.substring(1)
            '-' -> -1L to s.substring(1)
            else -> 1L to s
        }
    if (rest.isEmpty()) return null
    val v =
        when {
            rest.startsWith("0x") || rest.startsWith("0X") -> {
                rest.substring(2).toLongOrNull(16)
            }

            rest.length > 1 && rest[0] == '0' -> {
                rest.toLongOrNull(8)
            }

            else -> {
                rest.toLongOrNull(10)
            }
        } ?: return null
    return sign * v
}

public class XxdCommand :
    Command,
    CommandSpec {
    override val name: String = "xxd"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var plain = false
        var reverse = false
        var upper = false
        var autoskip = false
        var decimalOffset = false
        var littleEndian = false
        var cols = -1
        var group = -1
        var skip = 0L
        var limit = -1L
        var addOffset = 0L
        val positional = mutableListOf<String>()

        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                positional += a
                i++
                continue
            }
            // The man page tells us the lazy parser only inspects the first
            // option letter unless an argument is required; that means -plain,
            // -ps, -postscript are all -p. We accept the explicit long forms
            // listed in the man page rather than implementing greedy matching.
            when (a) {
                "--" -> {
                    endOfOpts = true
                }

                "-h", "-help", "--help" -> {
                    ctx.stdout.writeUtf8(HELP_TEXT)
                    ctx.stdout.writeUtf8("\n")
                    return CommandResult()
                }

                "-v", "-version", "--version" -> {
                    ctx.stdout.writeUtf8(VERSION + "\n")
                    return CommandResult()
                }

                "-p", "-ps", "-postscript", "-plain" -> {
                    plain = true
                }

                "-r", "-revert" -> {
                    reverse = true
                }

                "-u" -> {
                    upper = true
                }

                "-a", "-autoskip" -> {
                    autoskip = true
                }

                "-d" -> {
                    decimalOffset = true
                }

                "-e" -> {
                    littleEndian = true
                }

                "-c", "-cols" -> {
                    val v = args.getOrNull(i + 1)?.let { parseNum(it) }
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("xxd: invalid -c value\n")
                        return CommandResult(exitCode = 1)
                    }
                    cols = v.toInt()
                    i += 2
                    continue
                }

                "-g", "-groupsize" -> {
                    val v = args.getOrNull(i + 1)?.let { parseNum(it) }
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("xxd: invalid -g value\n")
                        return CommandResult(exitCode = 1)
                    }
                    group = v.toInt()
                    i += 2
                    continue
                }

                "-s" -> {
                    val v = args.getOrNull(i + 1)?.let { parseNum(it) }
                    if (v == null) {
                        ctx.stderr.writeUtf8("xxd: invalid -s value\n")
                        return CommandResult(exitCode = 1)
                    }
                    skip = v
                    i += 2
                    continue
                }

                "-l", "-len" -> {
                    val v = args.getOrNull(i + 1)?.let { parseNum(it) }
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("xxd: invalid -l value\n")
                        return CommandResult(exitCode = 1)
                    }
                    limit = v
                    i += 2
                    continue
                }

                "-o" -> {
                    val v = args.getOrNull(i + 1)?.let { parseNum(it) }
                    if (v == null) {
                        ctx.stderr.writeUtf8("xxd: invalid -o value\n")
                        return CommandResult(exitCode = 1)
                    }
                    addOffset = v
                    i += 2
                    continue
                }

                "-b", "-bits",
                "-i", "-include",
                "-E", "-EBCDIC",
                "-C", "-capitalize",
                "-n", "-name",
                "-R", "-seek",
                -> {
                    ctx.stderr.writeUtf8("xxd: option $a is not supported in this build\n")
                    return CommandResult(exitCode = 1)
                }

                "-" -> {
                    positional += a
                }

                else -> {
                    if (a.startsWith("-") && a.length > 1) {
                        ctx.stderr.writeUtf8("xxd: unknown option: $a\n")
                        return CommandResult(exitCode = 1)
                    }
                    positional += a
                }
            }
            i++
        }

        if (positional.size > 2) {
            ctx.stderr.writeUtf8("xxd: extra operand '${positional[2]}'\n")
            return CommandResult(exitCode = 1)
        }
        val infile = positional.getOrNull(0)
        val outfile = positional.getOrNull(1)

        val input: ByteArray = readInput(infile, ctx) ?: return CommandResult(exitCode = 1)

        val outputBytes: ByteArray?
        val outputText: String?
        if (reverse) {
            val r = doReverse(input, plain, ctx) ?: return CommandResult(exitCode = 1)
            outputBytes = r
            outputText = null
        } else {
            val sliced = sliceInput(input, skip, limit)
            outputBytes = null
            outputText =
                renderDump(
                    sliced,
                    plain = plain,
                    upper = upper,
                    autoskip = autoskip,
                    decimalOffset = decimalOffset,
                    littleEndian = littleEndian,
                    colsArg = cols,
                    groupArg = group,
                    baseOffset = (if (skip > 0) skip else 0L) + addOffset,
                )
        }

        return writeOutput(outfile, outputText, outputBytes, ctx)
    }

    private suspend fun readInput(
        file: String?,
        ctx: CommandContext,
    ): ByteArray? {
        if (file == null || file == "-") return ctx.stdin.readAllBytes()
        val path = resolvePath(ctx.process.cwd, file)
        val src =
            try {
                ctx.process.fs.source(path)
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("xxd: $file: No such file or directory\n")
                return null
            }
        return try {
            src.readAllBytes()
        } finally {
            src.close()
        }
    }

    private suspend fun writeOutput(
        outfile: String?,
        text: String?,
        bytes: ByteArray?,
        ctx: CommandContext,
    ): CommandResult {
        if (outfile == null || outfile == "-") {
            if (text != null) ctx.stdout.writeUtf8(text)
            if (bytes != null) ctx.stdout.writeBytes(bytes)
            return CommandResult()
        }
        val path = resolvePath(ctx.process.cwd, outfile)
        val sink = ctx.process.fs.sink(path)
        try {
            if (text != null) sink.writeUtf8(text)
            if (bytes != null) sink.writeBytes(bytes)
        } finally {
            sink.close()
        }
        return CommandResult()
    }

    private fun sliceInput(
        input: ByteArray,
        skip: Long,
        limit: Long,
    ): ByteArray {
        // Negative skip means "from end of input"; positive is from start.
        val start =
            when {
                skip == 0L -> 0
                skip > 0 -> minOf(skip.toInt(), input.size)
                else -> maxOf(0, input.size + skip.toInt())
            }
        val end = if (limit < 0) input.size else minOf(start + limit.toInt(), input.size)
        if (start == 0 && end == input.size) return input
        return input.copyOfRange(start, end)
    }

    private fun renderDump(
        input: ByteArray,
        plain: Boolean,
        upper: Boolean,
        autoskip: Boolean,
        decimalOffset: Boolean,
        littleEndian: Boolean,
        colsArg: Int,
        groupArg: Int,
        baseOffset: Long,
    ): String {
        val hexChars = if (upper) "0123456789ABCDEF" else "0123456789abcdef"
        if (plain) {
            val cols = if (colsArg < 0) 30 else colsArg
            val sb = StringBuilder()
            if (cols == 0) {
                for (b in input) {
                    val v = b.toInt() and 0xff
                    sb.append(hexChars[v ushr 4])
                    sb.append(hexChars[v and 0xf])
                }
                sb.append('\n')
                return sb.toString()
            }
            var i = 0
            while (i < input.size) {
                val end = minOf(i + cols, input.size)
                for (j in i until end) {
                    val v = input[j].toInt() and 0xff
                    sb.append(hexChars[v ushr 4])
                    sb.append(hexChars[v and 0xf])
                }
                sb.append('\n')
                i = end
            }
            if (sb.isEmpty()) sb.append('\n')
            return sb.toString()
        }

        val cols = if (colsArg <= 0) 16 else colsArg
        val group = if (groupArg < 0) (if (littleEndian) 4 else 2) else groupArg
        val sb = StringBuilder()
        var off = 0
        var inSkip = false
        while (off < input.size) {
            val end = minOf(off + cols, input.size)
            val isFullLine = (end - off) == cols
            if (autoskip && isFullLine && isAllZero(input, off, end)) {
                if (!inSkip) {
                    sb.append("*\n")
                    inSkip = true
                }
                off = end
                continue
            }
            inSkip = false
            val absOffset = baseOffset + off
            val offStr =
                if (decimalOffset) {
                    absOffset.toString().padStart(8, '0')
                } else {
                    absOffset.toString(16).padStart(8, '0').let {
                        if (upper) it.uppercase() else it
                    }
                }
            sb.append(offStr).append(": ")

            val hexCol = StringBuilder()
            for (j in 0 until cols) {
                val byteIdx =
                    if (littleEndian) {
                        // group bytes are read little-endian: within each group
                        // emit bytes in reverse order, padding short trailing
                        // groups with spaces. Operate on absolute column index.
                        val g = group.coerceAtLeast(1)
                        val groupStart = (j / g) * g
                        val withinGroup = j - groupStart
                        val swappedWithin = (g - 1) - withinGroup
                        groupStart + swappedWithin
                    } else {
                        j
                    }
                if (byteIdx < end - off) {
                    val v = input[off + byteIdx].toInt() and 0xff
                    hexCol.append(hexChars[v ushr 4])
                    hexCol.append(hexChars[v and 0xf])
                } else {
                    hexCol.append("  ")
                }
                val nextInGroup = group != 0 && (j + 1) % group == 0 && j + 1 < cols
                if (nextInGroup) hexCol.append(' ')
            }
            sb.append(hexCol).append("  ")

            for (j in off until end) {
                val v = input[j].toInt() and 0xff
                sb.append(if (v in 0x20..0x7e) v.toChar() else '.')
            }
            sb.append('\n')
            off = end
        }
        return sb.toString()
    }

    private fun isAllZero(
        a: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        for (i in start until end) if (a[i].toInt() != 0) return false
        return true
    }

    private suspend fun doReverse(
        input: ByteArray,
        plain: Boolean,
        ctx: CommandContext,
    ): ByteArray? {
        val text =
            buildString(input.size) {
                for (b in input) append((b.toInt() and 0xff).toChar())
            }
        val out = ArrayList<Byte>(input.size / 2)
        if (plain) {
            // Plain mode: per the man page, "anything that looks like a pair of
            // hex digits is interpreted." We strip whitespace, then require
            // even-length pure hex.
            val sb = StringBuilder(text.length)
            for (c in text) if (!c.isWhitespace()) sb.append(c)
            val clean = sb.toString()
            if (clean.length % 2 != 0) {
                ctx.stderr.writeUtf8("xxd: odd number of hex digits\n")
                return null
            }
            var i = 0
            while (i < clean.length) {
                val hi = hexVal(clean[i])
                val lo = hexVal(clean[i + 1])
                if (hi < 0 || lo < 0) {
                    ctx.stderr.writeUtf8("xxd: non-hex character in input\n")
                    return null
                }
                out += ((hi shl 4) or lo).toByte()
                i += 2
            }
        } else {
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val afterColon = line.substring(colon + 1)
                val gap = afterColon.indexOf("  ")
                val hexRegion = if (gap >= 0) afterColon.substring(0, gap) else afterColon
                val sb = StringBuilder(hexRegion.length)
                for (c in hexRegion) if (!c.isWhitespace()) sb.append(c)
                val clean = sb.toString()
                if (clean.length % 2 != 0) {
                    ctx.stderr.writeUtf8("xxd: odd number of hex digits\n")
                    return null
                }
                var i = 0
                while (i < clean.length) {
                    val hi = hexVal(clean[i])
                    val lo = hexVal(clean[i + 1])
                    if (hi < 0 || lo < 0) {
                        ctx.stderr.writeUtf8("xxd: non-hex character in input\n")
                        return null
                    }
                    out += ((hi shl 4) or lo).toByte()
                    i += 2
                }
            }
        }
        return out.toByteArray()
    }

    private fun hexVal(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> 10 + (c - 'a')
            in 'A'..'F' -> 10 + (c - 'A')
            else -> -1
        }
}
