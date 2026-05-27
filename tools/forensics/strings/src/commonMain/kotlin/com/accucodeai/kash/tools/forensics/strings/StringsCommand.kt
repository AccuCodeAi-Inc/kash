package com.accucodeai.kash.tools.forensics.strings

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * `strings` — print printable character sequences from binary files.
 *
 * Flags (GNU/BSD compatible subset):
 *  - `-n N` / `--bytes=N` / legacy `-N` (e.g. `-10`): minimum run length (default 4).
 *  - `-t {o,d,x}` / `--radix=R`: print the byte offset of each run at the start
 *    of its line. Format is `<offset><single space><string>`; the offset is left
 *    padded to width 7 in the requested radix (matching GNU `%7lo`/`%7ld`/`%7lx`).
 *  - `-a` / `--all`: scan the whole file. This is already the default here, so it
 *    is accepted as a no-op (the `-d`/data-section scan mode is not implemented).
 *  - `-f` / `--print-file-name`: prefix each line with `FILE: `.
 *  - `-e {s,S,b,l,B,L}` / `--encoding=E`: scan encoding.
 *      s = 7-bit ASCII (default), S = 8-bit, l/b = 16-bit LE/BE, L/B = 32-bit LE/BE.
 *      A wide unit is printable iff its high bytes are zero and its low byte is a
 *      printable ASCII char.
 *
 * Operands: zero or more files; `-` ≡ stdin; no operand ≡ stdin.
 *
 * Streaming: each input is read in [chunkSize]-byte chunks via `readAtMostTo`. A
 * run in progress, plus partial wide-unit bytes, are carried across chunk
 * boundaries together with a running byte offset, so arbitrarily large binaries
 * never need to be slurped whole. [chunkSize] is internal so tests can force a
 * boundary mid-run.
 */
public class StringsCommand :
    Command,
    CommandSpec {
    override val name: String = "strings"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    internal enum class Radix { NONE, OCT, DEC, HEX }

    internal enum class Encoding(
        val unitBytes: Int,
        val bigEndian: Boolean,
    ) {
        ASCII7(1, false), // s — 7-bit
        ASCII8(1, false), // S — 8-bit
        UTF16LE(2, false), // l
        UTF16BE(2, true), // b
        UTF32LE(4, false), // L
        UTF32BE(4, true), // B
    }

    internal data class Opts(
        val minLen: Int = 4,
        val radix: Radix = Radix.NONE,
        val printFileName: Boolean = false,
        val encoding: Encoding = Encoding.ASCII7,
        val files: List<String> = emptyList(),
    )

    /** Chunk size for streaming reads; overridable in tests to force a boundary. */
    internal var chunkSize: Long = 64L * 1024L

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args, ctx)
        if (parsed.handled) return CommandResult(exitCode = parsed.exitCode ?: 0)
        val opts = parsed.opts ?: return CommandResult(exitCode = parsed.exitCode ?: 2)

        val inputs = opts.files.ifEmpty { listOf("-") }
        var anyErr = false
        for (name in inputs) {
            val src: SuspendSource =
                if (name == "-") {
                    ctx.stdin
                } else {
                    val path = Paths.resolve(ctx.process.cwd, name)
                    if (!ctx.process.fs.exists(path)) {
                        ctx.stderr.writeUtf8("strings: '$name': No such file or directory\n")
                        anyErr = true
                        continue
                    }
                    try {
                        ctx.process.fs.source(path)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("strings: '$name': No such file or directory\n")
                        anyErr = true
                        continue
                    }
                }
            val label = if (opts.printFileName) (if (name == "-") "{standard input}" else name) else null
            scan(src, opts, label, ctx)
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    /**
     * Stream [src], emitting every printable run of at least [Opts.minLen] units.
     * State carried across chunk reads: the accumulated run [run] and its start
     * offset [runStart], plus [carry] bytes that did not complete a wide unit and
     * the running byte offset [offset].
     */
    private suspend fun scan(
        src: SuspendSource,
        opts: Opts,
        label: String?,
        ctx: CommandContext,
    ) {
        val enc = opts.encoding
        val unit = enc.unitBytes
        val run = StringBuilder()
        var runStart = 0L
        var offset = 0L
        var carry = ByteArray(0)

        val buf = Buffer()
        while (src.readAtMostTo(buf, chunkSize) > 0L) {
            val chunk = buf.readByteArray()
            val data: ByteArray
            val base: Long
            if (carry.isEmpty()) {
                data = chunk
                base = offset
            } else {
                data = carry + chunk
                base = offset - carry.size
            }
            var i = 0
            while (i + unit <= data.size) {
                val ch = decodeUnit(data, i, enc)
                val unitOffset = base + i
                if (ch != null) {
                    if (run.isEmpty()) runStart = unitOffset
                    run.append(ch)
                } else if (run.isNotEmpty()) {
                    flush(run, runStart, opts, label, ctx)
                }
                i += unit
            }
            // Bytes that didn't complete a unit are carried to the next chunk.
            carry = if (i < data.size) data.copyOfRange(i, data.size) else ByteArray(0)
            offset = base + i
        }
        if (run.isNotEmpty()) flush(run, runStart, opts, label, ctx)
    }

    /**
     * Decode the unit at [pos] in [data] under [enc]. Returns the printable char,
     * or null if the unit is non-printable (which terminates a run).
     */
    private fun decodeUnit(
        data: ByteArray,
        pos: Int,
        enc: Encoding,
    ): Char? =
        when (enc) {
            Encoding.ASCII7 -> {
                val v = data[pos].toInt() and 0xFF
                if (isPrintable7(v)) v.toChar() else null
            }

            Encoding.ASCII8 -> {
                val v = data[pos].toInt() and 0xFF
                if (isPrintable8(v)) v.toChar() else null
            }

            else -> {
                // Wide unit: printable iff every high byte is zero and the low
                // byte is a printable 7-bit char.
                val low: Int
                var highZero = true
                if (enc.bigEndian) {
                    low = data[pos + enc.unitBytes - 1].toInt() and 0xFF
                    for (k in 0 until enc.unitBytes - 1) {
                        if (data[pos + k].toInt() != 0) highZero = false
                    }
                } else {
                    low = data[pos].toInt() and 0xFF
                    for (k in 1 until enc.unitBytes) {
                        if (data[pos + k].toInt() != 0) highZero = false
                    }
                }
                if (highZero && isPrintable7(low)) low.toChar() else null
            }
        }

    private fun isPrintable7(v: Int): Boolean = (v in 0x20..0x7E) || v == 0x09

    private fun isPrintable8(v: Int): Boolean = (v in 0x20..0x7E) || v == 0x09 || v >= 0xA0

    private suspend fun flush(
        run: StringBuilder,
        runStart: Long,
        opts: Opts,
        label: String?,
        ctx: CommandContext,
    ) {
        if (run.length >= opts.minLen) {
            val sb = StringBuilder()
            if (label != null) {
                sb.append(label).append(": ")
            }
            if (opts.radix != Radix.NONE) {
                sb.append(formatOffset(runStart, opts.radix)).append(' ')
            }
            sb.append(run).append('\n')
            ctx.stdout.writeUtf8(sb.toString())
        }
        run.setLength(0)
    }

    internal fun formatOffset(
        off: Long,
        radix: Radix,
    ): String {
        val s =
            when (radix) {
                Radix.OCT -> off.toString(8)
                Radix.DEC -> off.toString(10)
                Radix.HEX -> off.toString(16)
                Radix.NONE -> ""
            }
        return s.padStart(7)
    }

    // ---------- arg parsing ----------

    internal data class ParseResult(
        val opts: Opts? = null,
        val handled: Boolean = false,
        val exitCode: Int? = null,
    )

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): ParseResult {
        var minLen = 4
        var radix = Radix.NONE
        var printFileName = false
        var encoding = Encoding.ASCII7
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0

        suspend fun err(msg: String): ParseResult {
            ctx.stderr.writeUtf8("strings: $msg\n")
            return ParseResult(exitCode = 1)
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

                a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return ParseResult(handled = true, exitCode = 0)
                }

                a == "-n" || a == "--bytes" -> {
                    if (i + 1 >= args.size) return err("option requires an argument -- 'n'")
                    minLen = args[++i].toIntOrNull() ?: return err("invalid minimum string length '${args[i]}'")
                }

                a.startsWith("--bytes=") -> {
                    val raw = a.substring("--bytes=".length)
                    minLen = raw.toIntOrNull() ?: return err("invalid minimum string length '$raw'")
                }

                a.startsWith("-n") && a.length > 2 -> {
                    val raw = a.substring(2)
                    minLen = raw.toIntOrNull() ?: return err("invalid minimum string length '$raw'")
                }

                a == "-t" || a == "--radix" -> {
                    if (i + 1 >= args.size) return err("option requires an argument -- 't'")
                    radix = parseRadix(args[++i]) ?: return err("invalid argument '${args[i]}' for '-t'")
                }

                a.startsWith("--radix=") -> {
                    val raw = a.substring("--radix=".length)
                    radix = parseRadix(raw) ?: return err("invalid argument '$raw' for '--radix'")
                }

                a.startsWith("-t") && a.length == 3 -> {
                    radix = parseRadix(a.substring(2)) ?: return err("invalid argument '${a.substring(2)}' for '-t'")
                }

                a == "-e" || a == "--encoding" -> {
                    if (i + 1 >= args.size) return err("option requires an argument -- 'e'")
                    encoding = parseEncoding(args[++i]) ?: return err("invalid argument '${args[i]}' for '-e'")
                }

                a.startsWith("--encoding=") -> {
                    val raw = a.substring("--encoding=".length)
                    encoding = parseEncoding(raw) ?: return err("invalid argument '$raw' for '--encoding'")
                }

                a.startsWith("-e") && a.length == 3 -> {
                    encoding =
                        parseEncoding(a.substring(2)) ?: return err("invalid argument '${a.substring(2)}' for '-e'")
                }

                a == "-a" || a == "--all" -> {
                    // Whole-file scan is already the default; no-op.
                }

                a == "-f" || a == "--print-file-name" -> {
                    printFileName = true
                }

                // Legacy `-N` numeric min length, e.g. `-6`, `-10`.
                a.length > 1 && a[0] == '-' && a.drop(1).all { it.isDigit() } -> {
                    minLen = a.drop(1).toInt()
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("strings: unrecognized option '$a'\n")
                    return ParseResult(exitCode = 2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("strings: invalid option -- '${a.substring(1)}'\n")
                    return ParseResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        if (minLen < 1) return err("invalid minimum string length '$minLen'")

        return ParseResult(
            opts =
                Opts(
                    minLen = minLen,
                    radix = radix,
                    printFileName = printFileName,
                    encoding = encoding,
                    files = files,
                ),
        )
    }

    private fun parseRadix(s: String): Radix? =
        when (s) {
            "o" -> Radix.OCT
            "d" -> Radix.DEC
            "x" -> Radix.HEX
            else -> null
        }

    private fun parseEncoding(s: String): Encoding? =
        when (s) {
            "s" -> Encoding.ASCII7
            "S" -> Encoding.ASCII8
            "l" -> Encoding.UTF16LE
            "b" -> Encoding.UTF16BE
            "L" -> Encoding.UTF32LE
            "B" -> Encoding.UTF32BE
            else -> null
        }

    private fun helpText(): String =
        "Usage: strings [OPTION]... [FILE]...\n" +
            "Print printable character sequences (>= 4 long, by default) in FILE(s).\n" +
            "With no FILE, or when FILE is -, read standard input.\n\n" +
            "  -a, --all                 scan the entire file (default; accepted)\n" +
            "  -f, --print-file-name     print the name of the file before each string\n" +
            "  -n, --bytes=MIN, -MIN     minimum string length (default 4)\n" +
            "  -t, --radix={o,d,x}       print the byte offset in octal/decimal/hex\n" +
            "  -e, --encoding={s,S,b,l,B,L}  select character encoding\n" +
            "      --help                display this help and exit\n"
}
