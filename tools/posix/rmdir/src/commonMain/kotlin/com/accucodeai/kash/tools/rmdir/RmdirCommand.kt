package com.accucodeai.kash.tools.rmdir

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`rmdir`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/rmdir.html).
 *
 * Removes empty directories. Errors if the directory is non-empty, doesn't
 * exist, or is a regular file.
 *
 * Supported options:
 *  - `-p` / `--parents` — after removing the target, also remove each parent
 *    that becomes empty. Stops walking up at the first non-empty parent;
 *    that is NOT treated as an error (POSIX behaviour).
 *
 * Exit 0 on success, 1 on any per-operand error (other operands still
 * attempted). Errors go to stderr as `rmdir: <path>: <reason>`.
 */
public class RmdirCommand :
    Command,
    CommandSpec {
    override val name: String = "rmdir"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var removeParents = false
        val operands = mutableListOf<String>()
        var endOfOptions = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOptions -> {
                    operands += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "-p" || a == "--parents" -> {
                    removeParents = true
                }

                a == "--ignore-fail-on-non-empty" -> {
                    Unit
                }

                // GNU compat, no-op (we already don't error on -p parents)
                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'p' -> {
                                removeParents = true
                            }

                            'v' -> {
                                Unit
                            }

                            else -> {
                                ctx.stderr.writeUtf8("rmdir: invalid option -- '$ch'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("rmdir: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        var anyError = false
        for (operand in operands) {
            val abs = Paths.resolve(ctx.process.cwd, operand)
            if (!removeOne(ctx, operand, abs)) {
                anyError = true
                continue
            }
            if (removeParents) {
                var cur = Paths.parent(abs)
                while (cur != "/" && cur.isNotEmpty()) {
                    if (!ctx.process.fs.exists(cur)) break
                    if (!ctx.process.fs.isDirectory(cur)) break
                    val entries =
                        try {
                            ctx.process.fs.list(cur)
                        } catch (_: Exception) {
                            break
                        }
                    if (entries.isNotEmpty()) break
                    try {
                        ctx.process.fs.remove(cur)
                    } catch (_: Exception) {
                        break
                    }
                    cur = Paths.parent(cur)
                }
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    private suspend fun removeOne(
        ctx: CommandContext,
        operand: String,
        abs: String,
    ): Boolean {
        if (!ctx.process.fs.exists(abs)) {
            ctx.stderr.writeUtf8("rmdir: $operand: No such file or directory\n")
            return false
        }
        if (!ctx.process.fs.isDirectory(abs)) {
            ctx.stderr.writeUtf8("rmdir: $operand: Not a directory\n")
            return false
        }
        val entries =
            try {
                ctx.process.fs.list(abs)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("rmdir: $operand: ${e.message ?: "I/O error"}\n")
                return false
            }
        if (entries.isNotEmpty()) {
            ctx.stderr.writeUtf8("rmdir: $operand: Directory not empty\n")
            return false
        }
        return try {
            ctx.process.fs.remove(abs)
            true
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("rmdir: $operand: ${e.message ?: "I/O error"}\n")
            false
        }
    }
}
