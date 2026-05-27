package com.accucodeai.kash.tools.fzf

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem

/**
 * `fzf` — interactive fuzzy finder. Reads candidate lines from stdin (or,
 * if stdin is a TTY, falls back to a recursive walk of `$cwd`), runs a
 * full-screen TUI, and writes the selected line(s) to stdout. Exit codes:
 *
 *  - 0 on accept
 *  - 1 on no candidates available, or I/O error
 *  - 2 on usage error
 *  - 130 on cancel (Esc / Ctrl-C / Ctrl-G)
 *
 * v1 scope (see KDoc on individual files for details):
 *  - subsequence fuzzy match with smart-case and word-boundary scoring
 *  - multi-select via `-m` + Tab toggle
 *  - `-q`, `--prompt`, `-i`
 *  - keys: arrows, Ctrl-P/N/J/K, Page Up/Down, Home/End, Backspace,
 *    Ctrl-U/W/H, Enter, Esc, Ctrl-C/G/Q, Tab
 *
 * Out of scope: preview window, custom keybinds, mouse, exact/anchored
 * query operators (`!foo`, `^foo`, `foo$`), ANSI passthrough in candidates,
 * `--height`, history.
 */
public class FzfCommand :
    Command,
    CommandSpec {
    override val name: String = "fzf"
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
                ctx.stdout.writeUtf8("fzf (kash) 1.0\n")
                return CommandResult()
            }

            is Parsed.ShowHelp -> {
                ctx.stdout.writeUtf8(USAGE)
                return CommandResult()
            }

            is Parsed.Ok -> {
                Unit
            }
        }

        // 1. Acquire candidates BEFORE touching raw mode (don't deadlock on
        //    the pipe).
        val candidates =
            if (ctx.process.isTty(0)) {
                // No stdin pipe — fall back to recursive fs walk.
                walkCwd(ctx.process.fs, ctx.process.cwd)
            } else {
                readStdinLines(ctx)
            }
        if (candidates.isEmpty()) {
            ctx.stderr.writeUtf8("fzf: no candidates\n")
            return CommandResult(exitCode = 1)
        }

        // 2. TerminalControl is the screen device — try stdin's, then stderr's.
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl
                ?: ctx.process.fdTable[2]
                    ?.ofd
                    ?.terminalControl
                ?: ctx.process.fdTable[1]
                    ?.ofd
                    ?.terminalControl
                ?: run {
                    ctx.stderr.writeUtf8("fzf: no terminal control available in this session\n")
                    return CommandResult(exitCode = 1)
                }

        val tui =
            FzfTui(
                term = term,
                candidates = candidates,
                options =
                    FzfOptions(
                        multi = parsed.multi,
                        initialQuery = parsed.query,
                        prompt = parsed.prompt,
                        caseSensitive = parsed.caseSensitive,
                    ),
            )
        return when (val outcome = tui.run()) {
            is FzfOutcome.Accepted -> {
                writeAccepted(ctx.stdout, outcome.lines)
                CommandResult(exitCode = 0)
            }

            FzfOutcome.Cancelled -> {
                CommandResult(exitCode = 130)
            }

            FzfOutcome.Empty -> {
                ctx.stderr.writeUtf8("fzf: no candidates\n")
                CommandResult(exitCode = 1)
            }
        }
    }

    private suspend fun readStdinLines(ctx: CommandContext): List<String> {
        val text = ctx.stdin.readUtf8Text()
        if (text.isEmpty()) return emptyList()
        // Drop a trailing newline so we don't synthesize an empty final entry.
        val trimmed = if (text.endsWith("\n")) text.dropLast(1) else text
        if (trimmed.isEmpty()) return emptyList()
        return trimmed.split('\n')
    }

    private fun walkCwd(
        fs: FileSystem,
        cwd: String,
    ): List<String> {
        val out = ArrayList<String>()
        val excluded = setOf(".git", "node_modules", ".gradle", "build", ".idea")

        fun walk(
            rel: String,
            absolute: String,
            depth: Int,
        ) {
            if (depth > 10) return
            val entries =
                try {
                    fs.list(absolute)
                } catch (_: Throwable) {
                    return
                }
            for (name in entries) {
                if (name in excluded) continue
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                val childAbs = if (absolute.endsWith("/")) "$absolute$name" else "$absolute/$name"
                val isDir =
                    try {
                        fs.isDirectory(childAbs)
                    } catch (_: Throwable) {
                        false
                    }
                if (isDir) {
                    walk(childRel, childAbs, depth + 1)
                } else {
                    out.add(childRel)
                }
            }
        }
        walk("", cwd, 0)
        return out
    }

    internal sealed interface Parsed {
        data class Ok(
            val multi: Boolean,
            val query: String,
            val prompt: String,
            val caseSensitive: Boolean?,
        ) : Parsed

        data object Usage : Parsed

        data object ShowVersion : Parsed

        data object ShowHelp : Parsed
    }

    internal fun parseArgs(args: List<String>): Parsed {
        var multi = false
        var query = ""
        var prompt = "> "
        var caseSensitive: Boolean? = null // null = smart-case
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-V" || a == "--version" -> {
                    return Parsed.ShowVersion
                }

                a == "-h" || a == "--help" -> {
                    return Parsed.ShowHelp
                }

                a == "-m" || a == "--multi" -> {
                    multi = true
                }

                a == "+m" || a == "--no-multi" -> {
                    multi = false
                }

                a == "-i" -> {
                    caseSensitive = false
                }

                a == "+i" -> {
                    caseSensitive = true
                }

                a == "--case-insensitive" -> {
                    caseSensitive = false
                }

                a == "--case-sensitive" -> {
                    caseSensitive = true
                }

                a == "-q" -> {
                    if (i + 1 >= args.size) return Parsed.Usage
                    query = args[++i]
                }

                a.startsWith("--query=") -> {
                    query = a.removePrefix("--query=")
                }

                a == "--query" -> {
                    if (i + 1 >= args.size) return Parsed.Usage
                    query = args[++i]
                }

                a.startsWith("--prompt=") -> {
                    prompt = a.removePrefix("--prompt=")
                }

                a == "--prompt" -> {
                    if (i + 1 >= args.size) return Parsed.Usage
                    prompt = args[++i]
                }

                // v1: silently accept these to keep AI-generated scripts working.
                a.startsWith("--height=") -> {
                    Unit
                }

                a == "--reverse" || a == "--no-reverse" -> {
                    Unit
                }

                a == "--ansi" -> {
                    Unit
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    return Parsed.Usage
                }

                else -> {
                    return Parsed.Usage
                }
            }
            i++
        }
        return Parsed.Ok(multi, query, prompt, caseSensitive)
    }

    private companion object {
        const val USAGE: String =
            "Usage: fzf [OPTIONS]\n" +
                "  -m, --multi             allow multi-select (Tab to toggle)\n" +
                "  -q, --query=STR         pre-fill the query\n" +
                "      --prompt=STR        change the prompt (default '> ')\n" +
                "  -i, --case-insensitive  force case-insensitive match\n" +
                "      --case-sensitive    force case-sensitive match\n" +
                "  -h, --help              show this help\n" +
                "  -V, --version           show version\n"
    }
}
