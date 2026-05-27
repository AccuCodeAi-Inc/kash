package com.accucodeai.kash.tools.clear

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `clear` — clear the terminal screen by writing ANSI/xterm escape sequences
 * to stdout. Non-POSIX (ncurses/BSD) but ubiquitous.
 *
 * Default emits `ESC [ H` (cursor home), `ESC [ 2 J` (erase screen) and
 * `ESC [ 3 J` (erase scrollback) — the same triple ncurses 6+ sends when
 * `TERM` resolves to a modern xterm-class entry. Output is unconditional —
 * pipe targets and redirected files get the bytes too, matching `clear(1)`.
 *
 * Flags:
 *  - `-x` — skip the `3 J` scrollback clear (visible screen only).
 *  - `-T TERM` — accepted but ignored; kash doesn't read terminfo.
 *  - `-V` / `--version`, `-h` / `--help`.
 *
 * Exit 0 on success, 1 on usage error.
 */
public class ClearCommand :
    Command,
    CommandSpec {
    override val name: String = "clear"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var clearScrollback = true
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("clear (kash) 1.0\n")
                    return CommandResult()
                }

                a == "-x" -> {
                    clearScrollback = false
                }

                a == "-T" -> {
                    // Consume and ignore the value — kash has no terminfo.
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("clear: option requires an argument: -T\n")
                        return CommandResult(exitCode = 1)
                    }
                    i++
                }

                else -> {
                    ctx.stderr.writeUtf8("clear: invalid operand: $a\n")
                    return CommandResult(exitCode = 1)
                }
            }
            i++
        }
        val seq =
            if (clearScrollback) {
                "\u001B[H\u001B[2J\u001B[3J"
            } else {
                "\u001B[H\u001B[2J"
            }
        ctx.stdout.writeUtf8(seq)
        return CommandResult()
    }

    private companion object {
        const val HELP: String =
            """Usage: clear [-x] [-T TERM]

Clear the terminal screen by writing ANSI escape sequences to stdout.
By default also clears the scrollback buffer; -x skips that.
"""
    }
}
