package com.accucodeai.kash.tools.rm

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`rm`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/rm.html).
 *
 * Supported options:
 *  - `-r` / `-R` / `--recursive` — recurse into directories.
 *  - `-f` / `--force` — ignore missing files; never prompt.
 *  - `-i` / `--interactive` — request confirmation before each removal.
 *    **Best-effort stub:** kash has no TTY model yet, so `-i` is accepted
 *    and recorded but in a non-interactive context (always, for now) it
 *    behaves like the default — i.e. it does NOT actually prompt. Once a
 *    TTY abstraction lands, this should grow real prompting.
 *  - `-d` / `--dir` — allow removing an empty directory without `-r`.
 *
 * `/` is refused without `--no-preserve-root`. This matches GNU's default
 * `--preserve-root` and prevents catastrophic accidents in the FS.
 *
 * Exit 0 on success, 1 on any per-operand error (other operands still
 * attempted). Errors go to stderr as `rm: <path>: <reason>`.
 */
public class RmCommand :
    Command,
    CommandSpec {
    override val name: String = "rm"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var recursive = false
        var force = false

        @Suppress("UNUSED_VARIABLE")
        var interactive = false // accepted, see KDoc
        var dirEmpty = false
        var preserveRoot = true
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

                a == "--recursive" -> {
                    recursive = true
                }

                a == "--force" -> {
                    force = true
                }

                a == "--interactive" -> {
                    interactive = true
                }

                a == "--dir" -> {
                    dirEmpty = true
                }

                a == "--preserve-root" -> {
                    preserveRoot = true
                }

                a == "--no-preserve-root" -> {
                    preserveRoot = false
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'r', 'R' -> {
                                recursive = true
                            }

                            'f' -> {
                                force = true
                                interactive = false
                            }

                            'i' -> {
                                interactive = true
                                force = false
                            }

                            'd' -> {
                                dirEmpty = true
                            }

                            'v' -> {
                                Unit
                            }

                            else -> {
                                ctx.stderr.writeUtf8("rm: invalid option -- '$ch'\n")
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
            if (force) return CommandResult(exitCode = 0)
            ctx.stderr.writeUtf8("rm: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        var anyError = false
        for (operand in operands) {
            val abs = Paths.resolve(ctx.process.cwd, operand)
            if (preserveRoot && abs == "/") {
                ctx.stderr.writeUtf8(
                    "rm: it is dangerous to operate recursively on '/'\nrm: use --no-preserve-root to override this failsafe\n",
                )
                anyError = true
                continue
            }
            if (!ctx.process.fs.exists(abs)) {
                if (force) continue
                ctx.stderr.writeUtf8("rm: $operand: No such file or directory\n")
                anyError = true
                continue
            }
            val isDir = ctx.process.fs.isDirectory(abs)
            if (isDir) {
                if (recursive) {
                    if (!removeRecursive(ctx, operand, abs)) anyError = true
                } else if (dirEmpty) {
                    val entries =
                        try {
                            ctx.process.fs.list(abs)
                        } catch (e: Exception) {
                            ctx.stderr.writeUtf8("rm: $operand: ${e.message ?: "I/O error"}\n")
                            anyError = true
                            continue
                        }
                    if (entries.isNotEmpty()) {
                        ctx.stderr.writeUtf8("rm: $operand: Directory not empty\n")
                        anyError = true
                        continue
                    }
                    try {
                        ctx.process.fs.remove(abs)
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("rm: $operand: ${e.message ?: "I/O error"}\n")
                        anyError = true
                    }
                } else {
                    ctx.stderr.writeUtf8("rm: $operand: Is a directory\n")
                    anyError = true
                }
            } else {
                try {
                    ctx.process.fs.remove(abs)
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("rm: $operand: ${e.message ?: "I/O error"}\n")
                    anyError = true
                }
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }

    /**
     * Depth-first delete of [abs]. Per-entry failures are reported but the
     * walk continues so as much as possible is cleaned up. Returns true iff
     * everything under (and including) [abs] was removed.
     */
    private suspend fun removeRecursive(
        ctx: CommandContext,
        displayBase: String,
        abs: String,
    ): Boolean {
        var ok = true
        if (ctx.process.fs.isDirectory(abs)) {
            val entries =
                try {
                    ctx.process.fs.list(abs)
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("rm: $displayBase: ${e.message ?: "I/O error"}\n")
                    return false
                }
            val basePrefix = if (abs == "/") "/" else "$abs/"
            val displayPrefix = if (displayBase.endsWith("/")) displayBase else "$displayBase/"
            for (entry in entries) {
                if (!removeRecursive(ctx, "$displayPrefix$entry", "$basePrefix$entry")) ok = false
            }
        }
        try {
            ctx.process.fs.remove(abs)
        } catch (e: Exception) {
            ctx.stderr.writeUtf8("rm: $displayBase: ${e.message ?: "I/O error"}\n")
            ok = false
        }
        return ok
    }
}
