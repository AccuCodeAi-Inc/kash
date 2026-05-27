package com.accucodeai.kash.tools.reset

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `reset` — return the terminal to a sane state. Non-POSIX (ncurses/BSD)
 * but the de-facto recovery command after a binary dump scrambles the
 * display.
 *
 * Emits, in order:
 *  - `ESC c` (RIS — full hardware reset)
 *  - `ESC [ ! p` (DECSTR — soft terminal reset)
 *  - `ESC [ ? 7 h` (DECAWM — re-enable autowrap)
 *  - `ESC [ ? 25 h` (DECTCEM — show cursor)
 *  - `ESC [ m` (SGR reset — clear colors/attributes)
 *  - `ESC [ H ESC [ 2 J ESC [ 3 J` (home, clear screen, clear scrollback)
 *
 * If a [com.accucodeai.kash.api.terminal.TerminalControl] is wired in, also
 * calls `exitRawMode()` defensively — `nano` is supposed to do this on
 * teardown but a crashed editor can leave the tty in raw mode, and
 * `exitRawMode()` is documented as idempotent.
 *
 * Real `reset(1)` also drives `stty` to repair line discipline (erase/kill
 * chars, echo, cooked mode). kash doesn't model `stty`, so we accept and
 * ignore the corresponding flags rather than refusing.
 *
 * Flags accepted (mostly no-ops):
 *  - `-q` quiet, `-Q` no messages, `-I` skip init, `-c` clear screen,
 *    `-r` print TERM to stderr, `-s` print TERM-export commands —
 *    all accepted; we emit only the reset escapes.
 *  - `-e CH`, `-k CH`, `-m MAP` — flag-with-value; consumed and ignored.
 *  - `-V` / `--version`, `-h` / `--help`.
 *
 * Any non-flag operand is treated as the terminal type and ignored.
 *
 * Exit 0 on success, 1 on usage error.
 */
public class ResetCommand :
    Command,
    CommandSpec {
    override val name: String = "reset"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("reset (kash) 1.0\n")
                    return CommandResult()
                }

                a == "-e" || a == "-k" || a == "-m" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("reset: option requires an argument: $a\n")
                        return CommandResult(exitCode = 1)
                    }
                    i++
                }

                a == "-q" || a == "-Q" || a == "-I" || a == "-c" || a == "-r" || a == "-s" -> {
                    // Accepted, no-op.
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("reset: invalid option: $a\n")
                    return CommandResult(exitCode = 1)
                }

                else -> {
                    // Bare operand = terminal type; we don't read terminfo, so ignore.
                }
            }
            i++
        }
        ctx.process.fdTable[0]
            ?.ofd
            ?.terminalControl
            ?.exitRawMode()
        val seq =
            buildString {
                append("\u001Bc")
                append("\u001B[!p")
                append("\u001B[?7h")
                append("\u001B[?25h")
                append("\u001B[m")
                append("\u001B[H\u001B[2J\u001B[3J")
            }
        ctx.stdout.writeUtf8(seq)
        return CommandResult()
    }

    private companion object {
        const val HELP: String =
            """Usage: reset [-qQIcrs] [-e CH] [-k CH] [-m MAP] [TERM]

Reset the terminal by writing the ANSI sequences RIS + DECSTR, re-enabling
autowrap and the cursor, resetting SGR, and clearing the screen and
scrollback. stty-style line-discipline repair is not performed (kash has
no stty); the corresponding flags are accepted but ignored.
"""
    }
}
