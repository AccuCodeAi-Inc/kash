package com.accucodeai.kash.tools.wc

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
 * `wc` — POSIX word/line/byte/char counter.
 *
 * Flags:
 * - `-l` lines, `-w` words, `-c` bytes, `-m` chars (UTF-8 codepoints),
 *   `-L` max line length (bash extension).
 * - Combined short flags supported (`-lw`, `-lwc`).
 * - `--` ends option parsing.
 *
 * Operands: zero or more file paths. No operands ⇒ read stdin (filename
 * column omitted). Operand `-` means stdin.
 *
 * Default (no flags) is `-l -w -c`. Output columns appear in the fixed
 * POSIX order `lines words chars bytes max-line-length`, filtered to whichever
 * were requested. Each column is right-aligned to width 7 (a deliberate
 * simplification over dynamic widths from the inputs).
 *
 * Missing files emit `wc: <path>: No such file or directory` on stderr, the
 * other operands still process, and the overall exit code becomes 1.
 */
public class WcCommand :
    Command,
    CommandSpec {
    override val name: String = "wc"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Counts(
        var lines: Long = 0,
        var words: Long = 0,
        var chars: Long = 0,
        var bytes: Long = 0,
        var maxLine: Long = 0,
    ) {
        operator fun plusAssign(other: Counts) {
            lines += other.lines
            words += other.words
            chars += other.chars
            bytes += other.bytes
            if (other.maxLine > maxLine) maxLine = other.maxLine
        }
    }

    private data class Flags(
        val lines: Boolean,
        val words: Boolean,
        val chars: Boolean,
        val bytes: Boolean,
        val maxLine: Boolean,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val flagsSelected = mutableSetOf<Char>()
        val operands = mutableListOf<String>()
        var endOfOpts = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                operands += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-") {
                operands += a
            } else if (a.startsWith("-") && a.length > 1) {
                for (c in a.substring(1)) {
                    if (c == 'l' || c == 'w' || c == 'c' || c == 'm' || c == 'L') {
                        flagsSelected += c
                    } else {
                        ctx.stderr.writeUtf8("wc: invalid option -- '$c'\n")
                        return CommandResult(exitCode = 2)
                    }
                }
            } else {
                operands += a
            }
            i++
        }
        val flags =
            if (flagsSelected.isEmpty()) {
                Flags(lines = true, words = true, chars = false, bytes = true, maxLine = false)
            } else {
                Flags(
                    lines = 'l' in flagsSelected,
                    words = 'w' in flagsSelected,
                    chars = 'm' in flagsSelected,
                    bytes = 'c' in flagsSelected,
                    maxLine = 'L' in flagsSelected,
                )
            }

        var exit = 0
        val total = Counts()
        val multi = operands.size > 1

        if (operands.isEmpty()) {
            val c = countBytes(ctx.stdin.readAllBytes())
            ctx.stdout.writeUtf8(formatRow(c, flags, label = null))
        } else {
            for (op in operands) {
                if (op == "-") {
                    val c = countBytes(ctx.stdin.readAllBytes())
                    ctx.stdout.writeUtf8(formatRow(c, flags, label = "-"))
                    total += c
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, op)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("wc: $op: No such file or directory\n")
                        exit = 1
                        continue
                    }
                    val bytes =
                        try {
                            ctx.process.fs
                                .source(abs)
                                .readAllBytes()
                        } catch (e: Exception) {
                            ctx.stderr.writeUtf8("wc: $op: ${e.message ?: "read error"}\n")
                            exit = 1
                            continue
                        }
                    val c = countBytes(bytes)
                    ctx.stdout.writeUtf8(formatRow(c, flags, label = op))
                    total += c
                }
            }
            if (multi) {
                ctx.stdout.writeUtf8(formatRow(total, flags, label = "total"))
            }
        }
        return CommandResult(exitCode = exit)
    }

    /**
     * Count over a UTF-8 byte payload. Words are runs of non-whitespace per
     * POSIX. `chars` is codepoint count (decoded from UTF-8). `maxLine` is the
     * longest line's codepoint count (excluding the terminating newline).
     */
    private fun countBytes(bytes: ByteArray): Counts {
        val c = Counts()
        c.bytes = bytes.size.toLong()
        var inWord = false
        var curLine = 0L
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            // Decode one UTF-8 codepoint (best-effort; stray continuation bytes count as 1).
            val cpLen: Int =
                if (b0 < 0x80) {
                    1
                } else if (b0 < 0xC0) {
                    1
                } else if (b0 < 0xE0) {
                    2
                } else if (b0 < 0xF0) {
                    3
                } else if (b0 < 0xF8) {
                    4
                } else {
                    1
                }
            val cpEnd = minOf(i + cpLen, bytes.size)
            c.chars++
            // Newline handling — only the ASCII '\n' counts as a line terminator.
            if (cpLen == 1 && b0 == 0x0A) {
                c.lines++
                if (curLine > c.maxLine) c.maxLine = curLine
                curLine = 0
                inWord = false
            } else {
                curLine++
                val ws = cpLen == 1 && isPosixSpace(b0)
                if (ws) {
                    inWord = false
                } else if (!inWord) {
                    inWord = true
                    c.words++
                }
            }
            i = cpEnd
        }
        // Trailing line without newline — its length still contributes to maxLine.
        if (curLine > c.maxLine) c.maxLine = curLine
        return c
    }

    private fun isPosixSpace(b: Int): Boolean =
        b == 0x20 || // space
            b == 0x09 || // tab
            b == 0x0A || // newline (handled above, included for completeness)
            b == 0x0B || // vertical tab
            b == 0x0C || // form feed
            b == 0x0D // carriage return

    private fun formatRow(
        c: Counts,
        f: Flags,
        label: String?,
    ): String {
        val parts = mutableListOf<String>()
        if (f.lines) parts += pad(c.lines)
        if (f.words) parts += pad(c.words)
        if (f.chars) parts += pad(c.chars)
        if (f.bytes) parts += pad(c.bytes)
        if (f.maxLine) parts += pad(c.maxLine)
        val row = parts.joinToString(" ")
        return if (label != null) "$row $label\n" else "$row\n"
    }

    private fun pad(v: Long): String = v.toString().padStart(7, ' ')
}
