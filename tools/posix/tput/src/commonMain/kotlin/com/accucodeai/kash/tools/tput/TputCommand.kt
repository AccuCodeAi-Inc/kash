package com.accucodeai.kash.tools.tput

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `tput` — query terminfo capabilities and emit the corresponding
 * ANSI escape sequence, numeric value, or boolean exit status. We do not
 * load a system terminfo database — capability strings are synthesized
 * from a built-in ANSI-flavored table (see [TputCaps]).
 *
 * Recipes this needs to handle:
 *  - `tput clear` — escape sequence to clear the screen
 *  - `tput cup R C` — cursor address (1-indexed)
 *  - `tput cols` / `tput lines` — numeric capability output
 *  - `tput bold` / `tput sgr0` — SGR attributes
 *  - `tput setaf N` / `tput setab N` — colors
 *  - `tput -S` — batch-read commands from stdin
 */
public class TputCommand :
    Command,
    CommandSpec {
    override val name: String = "tput"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var i = 0
        var batchFromStdin = false
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    break
                }

                a == "-T" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("tput: option requires an argument -- 'T'\n")
                        return CommandResult(exitCode = 2)
                    }
                    // Accepted, ignored — we always produce ANSI-like output.
                    i += 2
                }

                a.startsWith("-T") && a.length > 2 -> {
                    i++
                }

                a == "-S" -> {
                    batchFromStdin = true
                    i++
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("tput (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(helpText())
                    return CommandResult(exitCode = 0)
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    ctx.stderr.writeUtf8("tput: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    break
                }
            }
        }

        return if (batchFromStdin) {
            runBatch(ctx)
        } else {
            val rest = args.subList(i, args.size)
            if (rest.isEmpty()) {
                ctx.stderr.writeUtf8("tput: missing capability name\n")
                return CommandResult(exitCode = 2)
            }
            runOne(rest, ctx)
        }
    }

    /** Process a single capability invocation: name + optional args. */
    private suspend fun runOne(
        words: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val name = words[0]
        val rest = words.drop(1)
        return emit(name, rest, ctx)
    }

    private suspend fun runBatch(ctx: CommandContext): CommandResult {
        var lastExit = 0
        while (true) {
            val line = ctx.stdin.readUtf8LineOrNull() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val words = tokenize(trimmed)
            if (words.isEmpty()) continue
            val rc = emit(words[0], words.drop(1), ctx).exitCode
            if (rc != 0) lastExit = rc
        }
        return CommandResult(exitCode = lastExit)
    }

    private fun tokenize(line: String): List<String> {
        // Whitespace split; no quoting (matches ncurses tput -S).
        return line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    @Suppress("ReturnCount")
    private suspend fun emit(
        name: String,
        rest: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Booleans first.
        if (name in BOOL_CAPS) return CommandResult(exitCode = 0)
        if (name in UNSUPPORTED_BOOLS) return CommandResult(exitCode = 1)

        // Static (no-arg) caps.
        STATIC_CAPS[name]?.let {
            ctx.stdout.writeUtf8(it)
            return CommandResult(exitCode = 0)
        }

        // Numeric.
        if (isNumeric(name)) {
            val (cols, lines) = terminalSize(ctx)
            val v = renderNumeric(name, cols, lines)
            if (v == null) {
                ctx.stderr.writeUtf8("tput: unknown capability '$name'\n")
                return CommandResult(exitCode = 1)
            }
            ctx.stdout.writeUtf8("$v\n")
            return CommandResult(exitCode = 0)
        }

        // Parametric.
        if (isParametric(name)) {
            val out = renderParametric(name, rest)
            if (out == null) {
                ctx.stderr.writeUtf8("tput: capability '$name' requires numeric argument(s)\n")
                return CommandResult(exitCode = 1)
            }
            ctx.stdout.writeUtf8(out)
            return CommandResult(exitCode = 0)
        }

        // `init` and `longname` are common script no-ops; accept silently.
        when (name) {
            "init", "is2", "rs1", "rs2", "rs3" -> {
                // Many scripts call `tput init`; produce a benign reset.
                ctx.stdout.writeUtf8("$ESC[0m")
                return CommandResult(exitCode = 0)
            }

            "longname" -> {
                ctx.stdout.writeUtf8("ANSI-compatible (kash)\n")
                return CommandResult(exitCode = 0)
            }
        }

        ctx.stderr.writeUtf8("tput: unknown capability '$name'\n")
        return CommandResult(exitCode = 1)
    }

    /** Resolve terminal size: TerminalControl → env → default 80x24. */
    private fun terminalSize(ctx: CommandContext): Pair<Int, Int> {
        val tc =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl
        if (tc != null) {
            val s = tc.size()
            return s.cols to s.rows
        }
        val cols = ctx.env["COLUMNS"]?.toIntOrNull() ?: 80
        val rows = ctx.env["LINES"]?.toIntOrNull() ?: 24
        return cols to rows
    }

    private fun helpText(): String =
        """
        Usage: tput [-T type] capability [args...]
               tput [-T type] -S

        Query terminfo capabilities. Outputs the escape sequence for
        named capabilities; numeric capabilities (cols, lines, colors)
        print a decimal value; boolean capabilities set the exit status.

        With -S, read one command per line from standard input.
        """.trimIndent() + "\n"
}
