package com.accucodeai.kash.tools.comm

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `comm` — compare two sorted files line by line and emit three
 * tab-separated columns:
 *   - col 1: lines only in FILE1
 *   - col 2: lines only in FILE2
 *   - col 3: lines in both files
 *
 * Flags:
 *   - `-1`, `-2`, `-3` suppress the respective column. When a leading column
 *     is suppressed, later columns shift left (no leading tabs for absent cols).
 *   - `--check-order` / `--nocheck-order` accepted but treated as no-ops.
 *   - `--` ends option parsing.
 *
 * Operands: exactly two files. `-` means stdin. Both inputs must already be
 * sorted (we do not enforce nor reorder).
 */
public class CommCommand :
    Command,
    CommandSpec {
    override val name: String = "comm"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var suppress1 = false
        var suppress2 = false
        var suppress3 = false
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
            } else if (a == "--check-order" || a == "--nocheck-order") {
                // accepted no-op
            } else if (a.startsWith("--")) {
                ctx.stderr.writeUtf8("comm: unrecognized option '$a'\n")
                return CommandResult(exitCode = 2)
            } else if (a.startsWith("-") && a.length > 1) {
                for (c in a.substring(1)) {
                    when (c) {
                        '1' -> {
                            suppress1 = true
                        }

                        '2' -> {
                            suppress2 = true
                        }

                        '3' -> {
                            suppress3 = true
                        }

                        else -> {
                            ctx.stderr.writeUtf8("comm: invalid option -- '$c'\n")
                            return CommandResult(exitCode = 2)
                        }
                    }
                }
            } else {
                operands += a
            }
            i++
        }

        if (operands.size != 2) {
            ctx.stderr.writeUtf8("comm: expected exactly two file operands\n")
            return CommandResult(exitCode = 2)
        }

        val src1 =
            openOperand(ctx, operands[0])
                ?: return CommandResult(exitCode = 1)
        val src2 =
            openOperand(ctx, operands[1])
                ?: run {
                    closeIfFile(src1, operands[0])
                    return CommandResult(exitCode = 1)
                }

        try {
            compare(src1, src2, ctx, suppress1, suppress2, suppress3)
        } finally {
            closeIfFile(src1, operands[0])
            closeIfFile(src2, operands[1])
        }
        return CommandResult()
    }

    private suspend fun openOperand(
        ctx: CommandContext,
        op: String,
    ): SuspendSource? {
        if (op == "-") return ctx.stdin
        val abs = Paths.resolve(ctx.process.cwd, op)
        return try {
            ctx.process.fs.source(abs)
        } catch (_: FileNotFound) {
            ctx.stderr.writeUtf8("comm: $op: No such file or directory\n")
            null
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("comm: $op: ${e.message ?: "read error"}\n")
            null
        }
    }

    private fun closeIfFile(
        src: SuspendSource,
        op: String,
    ) {
        if (op != "-") {
            try {
                src.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun compare(
        a: SuspendSource,
        b: SuspendSource,
        ctx: CommandContext,
        suppress1: Boolean,
        suppress2: Boolean,
        suppress3: Boolean,
    ) {
        // Column-2 leading tabs: one if col1 not suppressed.
        // Column-3 leading tabs: count of (col1, col2) not suppressed.
        val col2Prefix = if (suppress1) "" else "\t"
        val col3Prefix =
            buildString {
                if (!suppress1) append('\t')
                if (!suppress2) append('\t')
            }

        var lineA: String? = a.readUtf8LineOrNull()
        var lineB: String? = b.readUtf8LineOrNull()

        while (lineA != null && lineB != null) {
            val cmp = lineA.compareTo(lineB)
            when {
                cmp < 0 -> {
                    if (!suppress1) ctx.stdout.writeUtf8("$lineA\n")
                    lineA = a.readUtf8LineOrNull()
                }

                cmp > 0 -> {
                    if (!suppress2) ctx.stdout.writeUtf8("$col2Prefix$lineB\n")
                    lineB = b.readUtf8LineOrNull()
                }

                else -> {
                    if (!suppress3) ctx.stdout.writeUtf8("$col3Prefix$lineA\n")
                    lineA = a.readUtf8LineOrNull()
                    lineB = b.readUtf8LineOrNull()
                }
            }
        }
        while (lineA != null) {
            if (!suppress1) ctx.stdout.writeUtf8("$lineA\n")
            lineA = a.readUtf8LineOrNull()
        }
        while (lineB != null) {
            if (!suppress2) ctx.stdout.writeUtf8("$col2Prefix$lineB\n")
            lineB = b.readUtf8LineOrNull()
        }
    }
}
