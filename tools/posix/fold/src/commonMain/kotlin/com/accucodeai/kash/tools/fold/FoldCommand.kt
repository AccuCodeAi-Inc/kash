package com.accucodeai.kash.tools.fold

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `fold` — POSIX line-wrapping utility.
 *
 * Flags:
 * - `-w WIDTH` (also `-WIDTH`, e.g. `fold -80`): maximum width per output
 *   chunk; default 80.
 * - `-b`: count bytes (UTF-8) instead of display columns.
 * - `-s`: break at the last blank within WIDTH if one exists; otherwise
 *   fall back to a hard break at WIDTH.
 *
 * Column accounting in the default (non-`-b`) mode:
 * - `\t` advances column to the next multiple of 8.
 * - `\b` decrements column (clamped at 0).
 * - `\r` resets column to 0.
 * - every other codepoint counts as one column.
 *
 * Operands: zero or more files; `-` and the absent-operand case read stdin.
 * The terminating `\n` of each input line is emitted as-is (it does not
 * count toward width).
 */
public class FoldCommand :
    Command,
    CommandSpec {
    override val name: String = "fold"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Opts(
        val width: Int,
        val byteMode: Boolean,
        val spaceBreak: Boolean,
        val files: List<String>,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parse(args, ctx) ?: return CommandResult(exitCode = 2)
        val o = parsed

        var anyErr = false
        if (o.files.isEmpty()) {
            foldSource(ctx.stdin, o, ctx)
        } else {
            for (f in o.files) {
                if (f == "-") {
                    foldSource(ctx.stdin, o, ctx)
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, f)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("fold: $f: No such file or directory\n")
                        anyErr = true
                        continue
                    }
                    foldSource(ctx.process.fs.source(abs), o, ctx)
                }
            }
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    private suspend fun foldSource(
        src: SuspendSource,
        o: Opts,
        ctx: CommandContext,
    ) {
        val bytes = src.readAllBytes()
        if (bytes.isEmpty()) return
        // Split on '\n' into lines (last segment may have no trailing newline).
        var lineStart = 0
        var i = 0
        while (i <= bytes.size) {
            val atEnd = i == bytes.size
            val isNl = !atEnd && bytes[i] == 0x0A.toByte()
            if (isNl || atEnd) {
                val lineBytes = bytes.copyOfRange(lineStart, i)
                foldLine(lineBytes, o, ctx)
                if (isNl) {
                    ctx.stdout.writeUtf8("\n")
                }
                lineStart = i + 1
            }
            if (atEnd) break
            i++
        }
    }

    /**
     * Wrap a single logical line (no trailing newline in [lineBytes]).
     * Emits zero or more chunks separated by `\n`; the *last* chunk for the
     * line has no trailing newline — the caller writes the original line's
     * `\n` (if present) after.
     */
    private suspend fun foldLine(
        lineBytes: ByteArray,
        o: Opts,
        ctx: CommandContext,
    ) {
        if (lineBytes.isEmpty()) return
        // Decode into a list of "units": each unit has the raw bytes that
        // produced one logical step. In -b mode the cost is the byte length;
        // in column mode the cost is computed from the codepoint.
        val units = decodeUnits(lineBytes)

        // Current chunk: bytes accumulated and their running width.
        val chunk = mutableListOf<CharUnit>()
        var width = 0

        suspend fun emitChunk(upTo: Int) {
            // emit units[0 until upTo] from chunk
            val sb = StringBuilder()
            for (k in 0 until upTo) sb.append(chunk[k].text)
            ctx.stdout.writeUtf8(sb.toString())
            ctx.stdout.writeUtf8("\n")
            // Drop the emitted units; the remaining ones become the start of
            // the next chunk. Width is recomputed.
            val remaining = chunk.subList(upTo, chunk.size).toMutableList()
            chunk.clear()
            chunk.addAll(remaining)
            width = recomputeWidth(chunk, o)
        }

        for (u in units) {
            val newWidth = applyWidth(width, u, o)
            if (newWidth > o.width && chunk.isNotEmpty()) {
                // Need to break before adding u.
                if (o.spaceBreak) {
                    // Find last whitespace boundary at or before current end.
                    var sp = -1
                    for (k in chunk.indices.reversed()) {
                        if (chunk[k].isBlank) {
                            sp = k
                            break
                        }
                    }
                    if (sp >= 0) {
                        emitChunk(sp + 1)
                    } else {
                        emitChunk(chunk.size)
                    }
                } else {
                    emitChunk(chunk.size)
                }
                // After emission, try adding u to the (now smaller) chunk.
                chunk.add(u)
                width = applyWidth(width, u, o)
                // If a single unit alone exceeds width (e.g. tab to col 8 with
                // -w 4) just leave it — POSIX accepts this.
            } else {
                chunk.add(u)
                width = newWidth
            }
        }
        // Flush remaining chunk without trailing newline (caller adds the
        // original line's newline if present).
        if (chunk.isNotEmpty()) {
            val sb = StringBuilder()
            for (u in chunk) sb.append(u.text)
            ctx.stdout.writeUtf8(sb.toString())
        }
    }

    private fun applyWidth(
        cur: Int,
        u: CharUnit,
        o: Opts,
    ): Int {
        if (o.byteMode) return cur + u.byteLen
        return when (u.kind) {
            UnitKind.TAB -> ((cur / 8) + 1) * 8
            UnitKind.BS -> if (cur > 0) cur - 1 else 0
            UnitKind.CR -> 0
            UnitKind.NORMAL -> cur + 1
        }
    }

    private fun recomputeWidth(
        units: List<CharUnit>,
        o: Opts,
    ): Int {
        var w = 0
        for (u in units) w = applyWidth(w, u, o)
        return w
    }

    private enum class UnitKind { NORMAL, TAB, BS, CR }

    /** One decoded character ready to emit. */
    private data class CharUnit(
        val text: String,
        val byteLen: Int,
        val kind: UnitKind,
        val isBlank: Boolean,
    )

    private fun decodeUnits(bytes: ByteArray): List<CharUnit> {
        val out = mutableListOf<CharUnit>()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val cpLen: Int =
                when {
                    b0 < 0x80 -> 1

                    b0 < 0xC0 -> 1

                    // stray continuation; consume one byte
                    b0 < 0xE0 -> 2

                    b0 < 0xF0 -> 3

                    b0 < 0xF8 -> 4

                    else -> 1
                }
            val end = minOf(i + cpLen, bytes.size)
            val slice = bytes.copyOfRange(i, end)
            val text =
                if (cpLen == 1) {
                    b0.toChar().toString()
                } else {
                    // Decode UTF-8 to String for output.
                    decodeUtf8(slice)
                }
            val kind =
                when {
                    cpLen == 1 && b0 == 0x09 -> UnitKind.TAB
                    cpLen == 1 && b0 == 0x08 -> UnitKind.BS
                    cpLen == 1 && b0 == 0x0D -> UnitKind.CR
                    else -> UnitKind.NORMAL
                }
            val isBlank = cpLen == 1 && (b0 == 0x20 || b0 == 0x09)
            out += CharUnit(text = text, byteLen = end - i, kind = kind, isBlank = isBlank)
            i = end
        }
        return out
    }

    private fun decodeUtf8(b: ByteArray): String {
        if (b.size == 1) return (b[0].toInt() and 0xFF).toChar().toString()
        val b0 = b[0].toInt() and 0xFF
        var cp =
            when {
                b0 < 0xE0 -> b0 and 0x1F
                b0 < 0xF0 -> b0 and 0x0F
                else -> b0 and 0x07
            }
        for (i in 1 until b.size) {
            cp = (cp shl 6) or (b[i].toInt() and 0x3F)
        }
        return if (cp < 0x10000) {
            cp.toChar().toString()
        } else {
            val v = cp - 0x10000
            val hi = (0xD800 + (v shr 10)).toChar()
            val lo = (0xDC00 + (v and 0x3FF)).toChar()
            "$hi$lo"
        }
    }

    private suspend fun parse(
        args: List<String>,
        ctx: CommandContext,
    ): Opts? {
        var width = 80
        var byteMode = false
        var spaceBreak = false
        val files = mutableListOf<String>()
        var endOfOpts = false

        suspend fun usage(msg: String) {
            ctx.stderr.writeUtf8("fold: $msg\n")
        }

        var i = 0
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

                a == "-b" -> {
                    byteMode = true
                }

                a == "-s" -> {
                    spaceBreak = true
                }

                a == "-w" -> {
                    if (i + 1 >= args.size) {
                        usage("option requires an argument -- 'w'")
                        return null
                    }
                    val v = args[++i].toIntOrNull()
                    if (v == null || v <= 0) {
                        usage("invalid number of columns: '${args[i]}'")
                        return null
                    }
                    width = v
                }

                a.startsWith("-w") && a.length > 2 -> {
                    val v = a.substring(2).toIntOrNull()
                    if (v == null || v <= 0) {
                        usage("invalid number of columns: '${a.substring(2)}'")
                        return null
                    }
                    width = v
                }

                // Combined short flags and/or legacy -N (e.g. -bs10, -80, -sb)
                a.startsWith("-") && a.length > 1 && a[1].isDigit() -> {
                    val v = a.substring(1).toIntOrNull()
                    if (v == null || v <= 0) {
                        usage("invalid number of columns: '${a.substring(1)}'")
                        return null
                    }
                    width = v
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Combined letters: -bs, -sb, etc. Also accept embedded digits.
                    val rest = a.substring(1)
                    var k = 0
                    while (k < rest.length) {
                        val c = rest[k]
                        when (c) {
                            'b' -> {
                                byteMode = true
                            }

                            's' -> {
                                spaceBreak = true
                            }

                            'w' -> {
                                // -wN or -w N
                                val tail = rest.substring(k + 1)
                                if (tail.isNotEmpty()) {
                                    val v = tail.toIntOrNull()
                                    if (v == null || v <= 0) {
                                        usage("invalid number of columns: '$tail'")
                                        return null
                                    }
                                    width = v
                                    k = rest.length
                                } else {
                                    if (i + 1 >= args.size) {
                                        usage("option requires an argument -- 'w'")
                                        return null
                                    }
                                    val v = args[++i].toIntOrNull()
                                    if (v == null || v <= 0) {
                                        usage("invalid number of columns: '${args[i]}'")
                                        return null
                                    }
                                    width = v
                                    k = rest.length
                                }
                            }

                            in '0'..'9' -> {
                                // Inline width digits after letter flags: -sb10
                                val tail = rest.substring(k)
                                val v = tail.toIntOrNull()
                                if (v == null || v <= 0) {
                                    usage("invalid number of columns: '$tail'")
                                    return null
                                }
                                width = v
                                k = rest.length
                            }

                            else -> {
                                usage("invalid option -- '$c'")
                                return null
                            }
                        }
                        k++
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return Opts(width = width, byteMode = byteMode, spaceBreak = spaceBreak, files = files)
    }
}
