package com.accucodeai.kash.tools.nano

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `nano` — minimal in-process text editor. Interactive only.
 *
 * Refuses cleanly when stdin/stdout aren't a terminal (parses through any
 * pipe stage or `<` redirection) and when no [TerminalControl] is available
 * (Kash REPL hasn't supplied one — e.g. non-interactive invocation). Both
 * refusal paths mirror the python3 REPL gating pattern.
 *
 * v1 keybindings: arrows / Home / End / PgUp / PgDn for navigation,
 * printable for insert, Backspace / Delete, Enter splits line, `^O` save
 * (with filename prompt), `^X` quit (with dirty-buffer confirm), `^S` save,
 * `^W` forward search, `^G` help overlay, `^L` redraw, `^K` cut line,
 * `^U` paste, `^A`/`^E` line home/end.
 */
public class NanoCommand :
    Command,
    CommandSpec {
    override val name: String = "nano"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
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
                ctx.stdout.writeUtf8("nano (kash) 1.0\n")
                return CommandResult()
            }

            is Parsed.Ok -> {
                Unit
            }
        }
        if (!ctx.process.isTty(0) || !ctx.process.isTty(1)) {
            ctx.stderr.writeUtf8("nano: stdin/stdout is not a TTY\n")
            return CommandResult(exitCode = 1)
        }
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl ?: run {
                ctx.stderr.writeUtf8("nano: no terminal control available in this session\n")
                return CommandResult(exitCode = 1)
            }
        val editor = NanoEditor(term, ctx.process.fs, ctx.process.cwd, parsed.file)
        return CommandResult(exitCode = editor.run())
    }

    private sealed interface Parsed {
        data class Ok(
            val file: String?,
        ) : Parsed

        data object Usage : Parsed

        data object ShowVersion : Parsed
    }

    private fun parseArgs(args: List<String>): Parsed {
        var file: String? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-V" || a == "--version" -> {
                    return Parsed.ShowVersion
                }

                a == "-h" || a == "--help" -> {
                    return Parsed.Usage
                }

                a == "--" -> {
                    if (i + 1 < args.size) file = args[i + 1]
                    return Parsed.Ok(file)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // v1: unknown options refuse rather than silently ignore.
                    return Parsed.Usage
                }

                else -> {
                    if (file != null) return Parsed.Usage // v1: at most one file
                    file = a
                }
            }
            i++
        }
        return Parsed.Ok(file)
    }

    private companion object {
        const val USAGE: String =
            "Usage: nano [OPTIONS] [FILE]\n" +
                "  -h, --help      show this help\n" +
                "  -V, --version   show version\n"
    }
}
