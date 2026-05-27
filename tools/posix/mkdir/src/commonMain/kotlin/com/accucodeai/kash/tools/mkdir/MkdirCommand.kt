package com.accucodeai.kash.tools.mkdir

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX [`mkdir`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/mkdir.html).
 *
 * Supported options:
 *  - `-p` — create parent directories as needed; do NOT error if the target
 *    directory already exists.
 *  - `-m MODE` / `--mode=MODE` — apply MODE to each created directory. Accepts
 *    octal (`755`, `0755`). [com.accucodeai.kash.fs.FileSystem.mkdirs] doesn't
 *    take a mode argument, so we call it then `chmod` the result. Symbolic
 *    modes (`u+x`) are accepted but not applied yet.
 *
 * Exit 0 on success, 1 on any per-operand error (other operands are still
 * attempted, matching POSIX). Errors go to stderr as
 * `mkdir: <path>: <reason>`.
 */
public class MkdirCommand :
    Command,
    CommandSpec {
    override val name: String = "mkdir"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var parents = false
        var mode: String? = null
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
                    parents = true
                }

                a == "-m" || a == "--mode" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("mkdir: option requires an argument -- 'm'\n")
                        return CommandResult(exitCode = 2)
                    }
                    mode = args[i + 1]
                    i++
                }

                a.startsWith("--mode=") -> {
                    mode = a.removePrefix("--mode=")
                }

                a.startsWith("-m") && a.length > 2 -> {
                    mode = a.substring(2)
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    // Bundled flags like -pv — accept -p, ignore -v (verbose unsupported).
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'p' -> {
                                parents = true
                            }

                            'v' -> {
                                Unit
                            }

                            else -> {
                                ctx.stderr.writeUtf8("mkdir: invalid option -- '$ch'\n")
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

        val modeBits: Int? =
            if (mode == null) {
                null
            } else if (mode.all { it in '0'..'7' }) {
                mode.toInt(8) and 0xFFF
            } else if (looksLikeMode(mode)) {
                null // symbolic accepted, not applied yet
            } else {
                ctx.stderr.writeUtf8("mkdir: invalid mode: '$mode'\n")
                return CommandResult(exitCode = 1)
            }

        if (operands.isEmpty()) {
            ctx.stderr.writeUtf8("mkdir: missing operand\n")
            return CommandResult(exitCode = 1)
        }

        var anyError = false
        for (operand in operands) {
            val abs = Paths.resolve(ctx.process.cwd, operand)
            try {
                if (parents) {
                    // -p: create intermediates, succeed if dir already exists.
                    // Error only if a non-dir already occupies the path.
                    if (ctx.process.fs.exists(abs) && !ctx.process.fs.isDirectory(abs)) {
                        ctx.stderr.writeUtf8("mkdir: $operand: File exists\n")
                        anyError = true
                        continue
                    }
                    // POSIX: `-m MODE` overrides umask; without `-m`, default
                    // mode is 0777 & ~umask. modeBits != null means `-m` was
                    // given and chmod runs after to set the exact bits.
                    val effectiveMode =
                        if (modeBits !=
                            null
                        ) {
                            0b111_111_111
                        } else {
                            0b111_111_111 and ctx.process.umask.inv()
                        }
                    ctx.process.fs.mkdirs(abs, mode = effectiveMode)
                } else {
                    // Plain mkdir: parent must exist, target must not exist.
                    val parent = Paths.parent(abs)
                    if (!ctx.process.fs.exists(parent)) {
                        ctx.stderr.writeUtf8("mkdir: $operand: No such file or directory\n")
                        anyError = true
                        continue
                    }
                    if (!ctx.process.fs.isDirectory(parent)) {
                        ctx.stderr.writeUtf8("mkdir: $operand: Not a directory\n")
                        anyError = true
                        continue
                    }
                    if (ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("mkdir: $operand: File exists\n")
                        anyError = true
                        continue
                    }
                    // POSIX: `-m MODE` overrides umask; without `-m`, default
                    // mode is 0777 & ~umask. modeBits != null means `-m` was
                    // given and chmod runs after to set the exact bits.
                    val effectiveMode =
                        if (modeBits !=
                            null
                        ) {
                            0b111_111_111
                        } else {
                            0b111_111_111 and ctx.process.umask.inv()
                        }
                    ctx.process.fs.mkdirs(abs, mode = effectiveMode)
                }
                if (modeBits != null) {
                    try {
                        ctx.process.fs.chmod(abs, modeBits)
                    } catch (_: UnsupportedOperationException) {
                        // FS doesn't model mode; -m becomes informational.
                    }
                }
            } catch (e: Exception) {
                ctx.stderr.writeUtf8("mkdir: $operand: ${e.message ?: "I/O error"}\n")
                anyError = true
            }
        }
        return CommandResult(exitCode = if (anyError) 1 else 0)
    }
}

/** Accept octal (e.g. `755`, `0755`) or symbolic-ish (`u+x`, `a=r`) modes. */
private fun looksLikeMode(s: String): Boolean {
    if (s.isEmpty()) return false
    if (s.all { it in '0'..'7' }) return true
    // Very loose check for symbolic — must contain one of +-= and only sane chars.
    val allowed = "ugoarwxstXACMUGO+-=,"
    return s.any { it in "+-=" } && s.all { it in allowed }
}
