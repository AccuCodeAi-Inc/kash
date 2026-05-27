package com.accucodeai.kash.tools.ed

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `ed` — line-oriented text editor. Unlike `nano`/`vi`, ed is a pure
 * prompt loop and works fine without a TTY — `ed -s file < script` is the
 * canonical batch-edit form.
 *
 * Implements the POSIX command set: a/i/c/d/p/n/l/=/s/g/v/w/W/r/e/E/f/q/Q/
 * h/H/P/u/m/t/j/k/z, address arithmetic (`.`, `$`, `'a`, `/re/`, `?re?`,
 * `+N`, `-N`), and the `%`/`,`/`;` range shortcuts. Errors print POSIX-
 * standard `?`; the help command (`h`) carries a one-line explanation
 * (text is original, not derived from GNU ed's verbose messages).
 *
 * Flags: `-p STR` (set prompt; toggled with `P`); `-s` (suppress byte-
 * count diagnostics on r/w/e).
 */
public class EdCommand :
    Command,
    CommandSpec {
    override val name: String = "ed"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.FS_WRITE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args)
        when (parsed) {
            is Parsed.Usage -> {
                ctx.stderr.writeUtf8(USAGE)
                return CommandResult(exitCode = 2)
            }

            is Parsed.ShowVersion -> {
                ctx.stdout.writeUtf8("ed (kash) 1.0\n")
                return CommandResult()
            }

            is Parsed.Ok -> {
                Unit
            }
        }
        val interp =
            EdInterpreter(
                stdin = ctx.stdin,
                stdout = ctx.stdout,
                stderr = ctx.stderr,
                fs = ctx.fs,
                cwd = ctx.cwd,
                prompt = parsed.prompt,
                suppressDiagnostics = parsed.suppress,
                initialFile = parsed.file,
            )
        return CommandResult(exitCode = interp.run())
    }

    private sealed interface Parsed {
        data class Ok(
            val file: String?,
            val prompt: String?,
            val suppress: Boolean,
        ) : Parsed

        data object Usage : Parsed

        data object ShowVersion : Parsed
    }

    private fun parseArgs(args: List<String>): Parsed {
        var file: String? = null
        var prompt: String? = null
        var suppress = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-V" || a == "--version" -> {
                    return Parsed.ShowVersion
                }

                a == "--help" -> {
                    return Parsed.Usage
                }

                a == "-s" -> {
                    suppress = true
                }

                a == "-p" -> {
                    i++
                    if (i >= args.size) return Parsed.Usage
                    prompt = args[i]
                }

                a.startsWith("-p") && a.length > 2 -> {
                    prompt = a.substring(2)
                }

                a == "--" -> {
                    if (i + 1 < args.size) file = args[i + 1]
                    return Parsed.Ok(file, prompt, suppress)
                }

                a.startsWith("-") && a.length > 1 -> {
                    return Parsed.Usage
                }

                else -> {
                    if (file != null) return Parsed.Usage
                    file = a
                }
            }
            i++
        }
        return Parsed.Ok(file, prompt, suppress)
    }

    private companion object {
        const val USAGE: String =
            "Usage: ed [-s] [-p prompt] [FILE]\n" +
                "  -s              suppress diagnostic byte counts\n" +
                "  -p PROMPT       set command prompt (toggle with P)\n" +
                "  -V, --version   show version\n" +
                "  --help          show this help\n"
    }
}
