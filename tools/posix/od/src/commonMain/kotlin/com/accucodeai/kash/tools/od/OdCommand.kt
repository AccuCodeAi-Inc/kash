package com.accucodeai.kash.tools.od

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `od` — dump bytes in various formats.
 *
 * Flags:
 *  - `-A RADIX`            address radix: d / o / x / n.
 *  - `-j BYTES`            skip BYTES of input first.
 *  - `-N BYTES`            dump at most BYTES bytes.
 *  - `-t TYPE`             output type spec (a, c, d[1248], u[1248], o[1248], x[1248]).
 *  - `-a -b -c -d -o -x`   POSIX short-form aliases for common `-t` specs.
 *  - `-h -i -l`            GNU/BSD short-form aliases (hex short, dec short, dec long).
 *  - `-v`                  show duplicate lines (default collapses them with `*`).
 *  - `-w[BYTES]`           bytes per line (default 16).
 *  - SIZE accepts k/K/m/M/g/G/b suffixes.
 *
 * Operands: zero or more files; `-` ≡ stdin; missing operand ≡ stdin.
 *
 * Output: for each input line of `width` bytes, prints the address column
 * (in `-A` radix) followed by, for each `-t` formatter, a row of formatted
 * cells. Duplicate consecutive lines are collapsed to `*` unless `-v` is set.
 * The final line shows the end address on its own.
 */
public class OdCommand :
    Command,
    CommandSpec {
    override val name: String = "od"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private enum class AddrRadix { DEC, OCT, HEX, NONE }

    private data class Opts(
        val formats: List<OdFormat> = listOf(OdFormat.Octal(2)),
        val addrRadix: AddrRadix = AddrRadix.OCT,
        val skipBytes: Long = 0,
        val readBytes: Long = -1, // -1 = unlimited
        val width: Int = 16,
        val showDuplicates: Boolean = false,
        val files: List<String> = emptyList(),
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args, ctx)
        if (parsed.handled) return CommandResult(exitCode = parsed.exitCode ?: 0)
        val opts = parsed.opts ?: return CommandResult(exitCode = parsed.exitCode ?: 2)

        // Concatenate input.
        val input: ByteArray =
            try {
                concatInputs(opts.files, ctx)
            } catch (e: NoSuchFileFault) {
                ctx.stderr.writeUtf8("od: ${e.path}: No such file or directory\n")
                return CommandResult(exitCode = 1)
            }

        val skipped =
            if (opts.skipBytes >= input.size) {
                ByteArray(0)
            } else {
                input.copyOfRange(opts.skipBytes.toInt(), input.size)
            }
        val data =
            if (opts.readBytes < 0 || opts.readBytes >= skipped.size) {
                skipped
            } else {
                skipped.copyOfRange(0, opts.readBytes.toInt())
            }

        emit(opts, data, ctx)
        return CommandResult(exitCode = 0)
    }

    private class NoSuchFileFault(
        val path: String,
    ) : RuntimeException()

    private suspend fun concatInputs(
        files: List<String>,
        ctx: CommandContext,
    ): ByteArray {
        if (files.isEmpty()) return ctx.stdin.readAllBytes()
        val parts = mutableListOf<ByteArray>()
        for (f in files) {
            val data =
                if (f == "-") {
                    ctx.stdin.readAllBytes()
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, f)
                    if (!ctx.process.fs.exists(abs)) throw NoSuchFileFault(f)
                    ctx.process.fs.readBytes(abs)
                }
            parts += data
        }
        var total = 0
        for (p in parts) total += p.size
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }

    private suspend fun emit(
        opts: Opts,
        data: ByteArray,
        ctx: CommandContext,
    ) {
        val addrCol: (Long) -> String = addrFormatter(opts.addrRadix)
        val width = opts.width
        if (data.isEmpty()) {
            // Emit only the trailing address line.
            val s = addrCol(opts.skipBytes)
            if (s.isNotEmpty()) ctx.stdout.writeUtf8(s + "\n")
            return
        }

        var off = 0
        var lastLineBytes: ByteArray? = null
        var inStar = false
        while (off < data.size) {
            val end = minOf(off + width, data.size)
            val line = data.copyOfRange(off, end)
            val absAddr = opts.skipBytes + off

            val isDup =
                !opts.showDuplicates &&
                    lastLineBytes != null &&
                    line.size == lastLineBytes.size &&
                    line.contentEquals(lastLineBytes)
            if (isDup) {
                if (!inStar) {
                    ctx.stdout.writeUtf8("*\n")
                    inStar = true
                }
            } else {
                inStar = false
                emitLine(opts, line, width, absAddr, addrCol, ctx)
                lastLineBytes = line
            }
            off = end
        }
        // Trailing address line (one past the end of input).
        val trailingAddr = opts.skipBytes + data.size
        val s = addrCol(trailingAddr)
        if (s.isNotEmpty()) ctx.stdout.writeUtf8(s + "\n")
    }

    private suspend fun emitLine(
        opts: Opts,
        line: ByteArray,
        fullWidth: Int,
        addr: Long,
        addrCol: (Long) -> String,
        ctx: CommandContext,
    ) {
        val addrStr = addrCol(addr)
        for ((fi, fmt) in opts.formats.withIndex()) {
            val prefix =
                if (fi == 0) {
                    if (addrStr.isEmpty()) "" else addrStr
                } else {
                    if (addrStr.isEmpty()) "" else " ".repeat(addrStr.length)
                }
            val sb = StringBuilder()
            sb.append(prefix)
            val unit = fmt.bytesPerUnit()
            var i = 0
            while (i + unit <= line.size) {
                sb.append(' ')
                sb.append(fmt.render(line, i))
                i += unit
            }
            // Pad final partial unit (zero-extended) — match the cell width but with spaces.
            if (i < line.size) {
                sb.append(' ')
                // Build a temp 'unit'-sized chunk zero-padded.
                val tmp = ByteArray(unit)
                line.copyInto(tmp, 0, i, line.size)
                sb.append(fmt.render(tmp, 0))
                i = line.size
            }
            // Right-pad with blank cells for missing trailing bytes.
            val unitsPerFullLine = fullWidth / unit
            val unitsDone = (line.size + unit - 1) / unit
            for (k in unitsDone until unitsPerFullLine) {
                sb.append(' ')
                sb.append(" ".repeat(fmt.width()))
            }
            sb.append('\n')
            ctx.stdout.writeUtf8(sb.toString())
        }
    }

    private fun addrFormatter(r: AddrRadix): (Long) -> String =
        when (r) {
            AddrRadix.DEC -> { v -> v.toString().padStart(7) }
            AddrRadix.OCT -> { v -> octal(v, 7) }
            AddrRadix.HEX -> { v -> hex(v, 7) }
            AddrRadix.NONE -> { _ -> "" }
        }

    // ---------- arg parsing ----------

    private data class ParseResult(
        val opts: Opts? = null,
        val handled: Boolean = false,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParseResult {
        var formats: MutableList<OdFormat> = mutableListOf()
        var addr = AddrRadix.OCT
        var skip = 0L
        var nbytes = -1L
        var width = 16
        var showDup = false
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0

        fun addShort(fmts: List<OdFormat>) {
            formats.addAll(fmts)
        }

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

                a == "-" -> {
                    files += a
                }

                a == "-A" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("od: option requires an argument -- 'A'\n")
                        return ParseResult(exitCode = 2)
                    }
                    addr = parseAddrRadix(args[++i]) ?: run {
                        ctx.stderr.writeUtf8("od: invalid -A radix: '${args[i]}'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                // Attached form: `-An`, `-Ax`, `-Ao`, `-Ad`. Real od accepts
                // both `-A x` and `-Ax`.
                a.length == 3 && a.startsWith("-A") -> {
                    addr = parseAddrRadix(a.substring(2)) ?: run {
                        ctx.stderr.writeUtf8("od: invalid -A radix: '${a.substring(2)}'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a.startsWith("--address-radix=") -> {
                    addr = parseAddrRadix(a.substring("--address-radix=".length)) ?: run {
                        ctx.stderr.writeUtf8("od: invalid --address-radix\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a == "-j" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("od: option requires an argument -- 'j'\n")
                        return ParseResult(exitCode = 2)
                    }
                    skip = parseSize(args[++i]) ?: run {
                        ctx.stderr.writeUtf8("od: invalid skip count: '${args[i]}'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a.startsWith("--skip-bytes=") -> {
                    skip = parseSize(a.substring("--skip-bytes=".length)) ?: run {
                        ctx.stderr.writeUtf8("od: invalid skip count\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a == "-N" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("od: option requires an argument -- 'N'\n")
                        return ParseResult(exitCode = 2)
                    }
                    nbytes = parseSize(args[++i]) ?: run {
                        ctx.stderr.writeUtf8("od: invalid byte count: '${args[i]}'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a.startsWith("--read-bytes=") -> {
                    nbytes = parseSize(a.substring("--read-bytes=".length)) ?: run {
                        ctx.stderr.writeUtf8("od: invalid byte count\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("od: option requires an argument -- 't'\n")
                        return ParseResult(exitCode = 2)
                    }
                    val spec = args[++i]
                    val fmts =
                        parseTypeSpec(spec) ?: run {
                            ctx.stderr.writeUtf8("od: invalid type string: '$spec'\n")
                            return ParseResult(exitCode = 1)
                        }
                    addShort(fmts)
                }

                a.startsWith("--format=") -> {
                    val spec = a.substring("--format=".length)
                    val fmts =
                        parseTypeSpec(spec) ?: run {
                            ctx.stderr.writeUtf8("od: invalid type string: '$spec'\n")
                            return ParseResult(exitCode = 1)
                        }
                    addShort(fmts)
                }

                a == "-a" -> {
                    addShort(listOf(OdFormat.NamedChar))
                }

                a == "-b" -> {
                    addShort(listOf(OdFormat.Octal(1)))
                }

                a == "-c" -> {
                    addShort(listOf(OdFormat.Char1))
                }

                a == "-d" -> {
                    addShort(listOf(OdFormat.DecUnsigned(2)))
                }

                a == "-o" -> {
                    addShort(listOf(OdFormat.Octal(2)))
                }

                a == "-x" -> {
                    addShort(listOf(OdFormat.Hex(2)))
                }

                a == "-h" -> {
                    addShort(listOf(OdFormat.Hex(2)))
                }

                a == "-i" -> {
                    addShort(listOf(OdFormat.DecSigned(2)))
                }

                a == "-l" -> {
                    addShort(listOf(OdFormat.DecSigned(4)))
                }

                a == "-s" -> {
                    addShort(listOf(OdFormat.DecSigned(2)))
                }

                a == "-v" || a == "--output-duplicates" -> {
                    showDup = true
                }

                a == "-w" -> {
                    // Optional argument; if next arg is numeric, consume it.
                    if (i + 1 < args.size && (args[i + 1].toIntOrNull() != null)) {
                        width = args[++i].toInt()
                    } else {
                        width = 32
                    }
                }

                a.startsWith("-w") && a.length > 2 && a[2].isDigit() -> {
                    width = a.substring(2).toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("od: invalid width: '${a.substring(2)}'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a == "--width" -> {
                    width = 32
                }

                a.startsWith("--width=") -> {
                    val raw = a.substring("--width=".length)
                    width = raw.toIntOrNull() ?: run {
                        ctx.stderr.writeUtf8("od: invalid width: '$raw'\n")
                        return ParseResult(exitCode = 1)
                    }
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return ParseResult(handled = true, exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("od (kash) 1.0\n")
                    return ParseResult(handled = true, exitCode = 0)
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("od: unrecognized option: $a\n")
                    return ParseResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("od: invalid option -- '${a.substring(1)}'\n")
                    return ParseResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        if (formats.isEmpty()) formats = mutableListOf(OdFormat.Octal(2))
        if (width <= 0) {
            ctx.stderr.writeUtf8("od: invalid width: $width\n")
            return ParseResult(exitCode = 1)
        }
        // Width must be a multiple of every format's unit size — round up if not.
        var w = width
        for (f in formats) {
            val u = f.bytesPerUnit()
            if (w % u != 0) w = ((w + u - 1) / u) * u
        }

        return ParseResult(
            opts =
                Opts(
                    formats = formats,
                    addrRadix = addr,
                    skipBytes = skip,
                    readBytes = nbytes,
                    width = w,
                    showDuplicates = showDup,
                    files = files,
                ),
        )
    }

    private fun parseAddrRadix(s: String): AddrRadix? =
        when (s) {
            "d" -> AddrRadix.DEC
            "o" -> AddrRadix.OCT
            "x" -> AddrRadix.HEX
            "n" -> AddrRadix.NONE
            else -> null
        }

    private fun parseSize(s: String): Long? {
        if (s.isEmpty()) return null
        // Accept hex (0x...), octal (0...), or decimal with optional unit suffix.
        val (body, mult) =
            when (val last = s.last()) {
                'b' -> {
                    s.dropLast(1) to 512L
                }

                'k', 'K' -> {
                    s.dropLast(1) to 1024L
                }

                'm', 'M' -> {
                    s.dropLast(1) to 1024L * 1024L
                }

                'g', 'G' -> {
                    s.dropLast(1) to 1024L * 1024L * 1024L
                }

                else -> {
                    if (last.isDigit() || last in 'a'..'f' || last in 'A'..'F' || last == 'x' || last == 'X') {
                        s to 1L
                    } else {
                        return null
                    }
                }
            }
        val v =
            when {
                body.startsWith("0x") || body.startsWith("0X") -> {
                    body.substring(2).toLongOrNull(16)
                }

                body.length > 1 && body.startsWith("0") && body.all { it in '0'..'7' } -> {
                    body.toLongOrNull(8)
                }

                else -> {
                    body.toLongOrNull()
                }
            } ?: return null
        return v * mult
    }

    private fun helpText(): String =
        "Usage: od [OPTIONS] [FILE...]\n" +
            "Dump FILE(s) (default: stdin) in various formats.\n" +
            "Options: -A {d|o|x|n}, -j BYTES, -N BYTES, -t TYPE, -a, -b, -c, -d, -o, -x, -v, -w[BYTES]\n"
}
