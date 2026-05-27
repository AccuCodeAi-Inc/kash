package com.accucodeai.kash.tools.mkfifo

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `mkfifo` — create FIFO special files.
 *
 * kash's [com.accucodeai.kash.fs.FileSystem] doesn't expose a `mknod`/FIFO
 * creation primitive (no consumer reads such files via blocking I/O). We
 * approximate by creating an empty regular file with the requested mode —
 * good enough for scripts that probe `[ -e fifo ]` or attempt to `cat` it
 * (a regular empty file just hits EOF, which mirrors a fifo that was never
 * written to).
 *
 * Forms:
 *  - `mkfifo [-m MODE] PATH...`
 *  - `mkfifo --mode=MODE PATH...`
 *
 * Mode parsing accepts:
 *  - Octal: `0644`, `644`.
 *  - Symbolic (subset): not supported v1; surfaces a clear error.
 *
 * Default mode is `0666` minus the calling process's umask. Each operand is
 * attempted independently; the overall exit is 0 only if every operand
 * succeeds, otherwise 1.
 */
public class MkfifoCommand :
    Command,
    CommandSpec {
    override val name: String = "mkfifo"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var explicitMode: Int? = null
        val operands = mutableListOf<String>()
        var endOfOpts = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOpts -> {
                    operands += a
                }

                a == "--" -> {
                    endOfOpts = true
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8("Usage: mkfifo [-m MODE] PATH...\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("mkfifo (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-m" || a == "--mode" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mkfifo: option requires an argument -- 'm'\n")
                        return CommandResult(exitCode = 2)
                    }
                    explicitMode =
                        parseMode(args[i + 1]) ?: run {
                            ctx.stderr.writeUtf8("mkfifo: invalid mode: '${args[i + 1]}'\n")
                            return CommandResult(exitCode = 1)
                        }
                    i++
                }

                a.startsWith("--mode=") -> {
                    val v = a.substring("--mode=".length)
                    explicitMode =
                        parseMode(v) ?: run {
                            ctx.stderr.writeUtf8("mkfifo: invalid mode: '$v'\n")
                            return CommandResult(exitCode = 1)
                        }
                }

                a == "-" -> {
                    operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("mkfifo: invalid option -- '${a.substring(1)}'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("mkfifo: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        val mode = explicitMode ?: (0b110_110_110 and ctx.process.umask.inv())
        var anyError = false
        for (operand in operands) {
            val abs = Paths.resolve(ctx.process.cwd, operand)
            if (ctx.process.fs.exists(abs)) {
                ctx.stderr.writeUtf8("mkfifo: cannot create fifo '$operand': File exists\n")
                anyError = true
                continue
            }
            val parent = Paths.parent(abs)
            if (parent.isNotEmpty() && !ctx.process.fs.exists(parent)) {
                ctx.stderr.writeUtf8("mkfifo: cannot create fifo '$operand': No such file or directory\n")
                anyError = true
                continue
            }
            if (parent.isNotEmpty() && !ctx.process.fs.isDirectory(parent)) {
                ctx.stderr.writeUtf8("mkfifo: cannot create fifo '$operand': Not a directory\n")
                anyError = true
                continue
            }
            try {
                // Fallback: write a 0-byte regular file as the FIFO placeholder.
                ctx.process.fs.writeBytes(abs, ByteArray(0), mode = mode and 0xFFF)
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("mkfifo: cannot create fifo '$operand': ${e.message ?: "I/O error"}\n")
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }
}

/**
 * Parse a numeric (octal) mode string. Returns the 12-bit mode value or
 * null if [s] cannot be parsed. A leading `0` is permitted but not required.
 * Symbolic modes (`u+rwx`) are out of scope for v1.
 */
internal fun parseMode(s: String): Int? {
    if (s.isEmpty()) return null
    if (!s.all { it in '0'..'7' }) return null
    var value = 0
    for (c in s) {
        value = (value shl 3) or (c - '0')
        if (value > 0xFFF) return null
    }
    return value
}
