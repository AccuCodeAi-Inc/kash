package com.accucodeai.kash.tools.rev

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `rev` — reverse the characters of every input line.
 *
 * Operates on Unicode codepoints, so non-BMP characters (surrogate pairs in
 * Kotlin's UTF-16 in-memory representation) are kept intact rather than being
 * split across the reversal. Combining marks are not given any special
 * handling, which matches util-linux `rev`.
 *
 * With no operands, reads stdin. Operands are file paths; `-` means stdin.
 * `--` ends option parsing. `--help` is accepted and produces a brief usage
 * message.
 *
 * Trailing newline behavior: if a line ends in `\n` the reversed line is also
 * `\n`-terminated; if the last line lacks `\n` the reversed output also lacks
 * one — matching util-linux.
 */
public class RevCommand :
    Command,
    CommandSpec {
    override val name: String = "rev"
    override val kind: CommandKind = CommandKind.TOOL
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val operands = mutableListOf<String>()
        var endOfOpts = false
        for (a in args) {
            if (endOfOpts) {
                operands += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-") {
                operands += a
            } else if (a == "--help") {
                ctx.stdout.writeUtf8("Usage: rev [FILE]...\nReverse characters in each line.\n")
                return CommandResult(exitCode = 0)
            } else if (a.length > 1 && a.startsWith("-")) {
                ctx.stderr.writeUtf8("rev: invalid option: $a\n")
                return CommandResult(exitCode = 2)
            } else {
                operands += a
            }
        }

        var exit = 0
        if (operands.isEmpty()) {
            processText(ctx.stdin.readUtf8Text(), ctx)
        } else {
            for (op in operands) {
                if (op == "-") {
                    processText(ctx.stdin.readUtf8Text(), ctx)
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, op)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("rev: $op: No such file or directory\n")
                        exit = 1
                        continue
                    }
                    val text =
                        try {
                            ctx.process.fs
                                .source(abs)
                                .readUtf8Text()
                        } catch (e: Exception) {
                            ctx.stderr.writeUtf8("rev: $op: ${e.message ?: "read error"}\n")
                            exit = 1
                            continue
                        }
                    processText(text, ctx)
                }
            }
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun processText(
        text: String,
        ctx: CommandContext,
    ) {
        if (text.isEmpty()) return
        // Walk the text by line, preserving trailing-newline state per line.
        var start = 0
        val len = text.length
        val sb = StringBuilder()
        while (start < len) {
            var end = text.indexOf('\n', start)
            val hasNl: Boolean
            val lineEnd: Int
            if (end == -1) {
                hasNl = false
                lineEnd = len
            } else {
                hasNl = true
                lineEnd = end
            }
            sb.setLength(0)
            reverseCodepointsInto(text, start, lineEnd, sb)
            if (hasNl) sb.append('\n')
            ctx.stdout.writeUtf8(sb.toString())
            start = if (hasNl) lineEnd + 1 else len
        }
    }

    /**
     * Reverse [text] over `[start, end)` by codepoints into [out].
     * Iterating with [Character.charCount] (1 for BMP, 2 for surrogate pairs)
     * keeps astral codepoints (emoji, CJK ext B, etc.) intact.
     */
    private fun reverseCodepointsInto(
        text: String,
        start: Int,
        end: Int,
        out: StringBuilder,
    ) {
        var i = end
        while (i > start) {
            // Walk backwards: if previous unit is a low surrogate and the one
            // before is a high surrogate, take both as one codepoint.
            val lo = text[i - 1]
            if (lo.isLowSurrogate() && i - 2 >= start && text[i - 2].isHighSurrogate()) {
                out.append(text[i - 2])
                out.append(lo)
                i -= 2
            } else {
                out.append(lo)
                i -= 1
            }
        }
    }
}
