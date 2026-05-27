package com.accucodeai.kash.tools.less

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `less` — minimal in-process interactive pager. Reads from argv FILE or
 * piped stdin; pages the content with vi-style key bindings.
 *
 * Refuses cleanly when stdout isn't a TTY and when no [TerminalControl] is
 * available. Unlike `nano`, stdin *can* be a pipe — that is in fact the
 * most common invocation (`cmd | less`). When stdin is piped, the input is
 * read completely into memory before raw mode is enabled, to avoid a
 * deadlock against the upstream producer.
 *
 * Out of scope for v1: tags, multi-file ring, `+pattern`, follow mode (`F`),
 * shell-pipe (`|`), the `v` edit shortcut, marks (`m`/`'`), wrap toggles,
 * case-insensitive search, and horizontal scroll.
 */
public class LessCommand :
    Command,
    CommandSpec {
    override val name: String = "less"
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
                ctx.stdout.writeUtf8("less (kash) 1.0\n")
                return CommandResult()
            }

            is Parsed.Ok -> {
                Unit
            }
        }

        // stdout MUST be a tty (we paint a full-screen UI). stdin may be a
        // pipe — that's the common `cmd | less` case.
        if (!ctx.process.isTty(1)) {
            ctx.stderr.writeUtf8("less: stdout is not a TTY\n")
            return CommandResult(exitCode = 1)
        }
        val term =
            ctx.process.fdTable[0]
                ?.ofd
                ?.terminalControl
                ?: ctx.process.fdTable[1]
                    ?.ofd
                    ?.terminalControl
                ?: run {
                    ctx.stderr.writeUtf8("less: no terminal control available in this session\n")
                    return CommandResult(exitCode = 1)
                }

        // Resolve content: argv FILE wins; otherwise drain stdin.
        val (text, displayName) =
            try {
                loadContent(ctx, parsed.file)
            } catch (t: Throwable) {
                ctx.stderr.writeUtf8("less: ${t.message ?: "read error"}\n")
                return CommandResult(exitCode = 1)
            }

        val pager = LessPager(term, text, displayName, showLineNumbers = parsed.showLineNumbers)
        return CommandResult(exitCode = pager.run())
    }

    private suspend fun loadContent(
        ctx: CommandContext,
        file: String?,
    ): Pair<String, String?> {
        if (file != null) {
            val resolved = resolve(ctx.process.cwd, file)
            if (!ctx.process.fs.exists(resolved)) {
                throw IllegalStateException("$file: No such file")
            }
            if (ctx.process.fs.isDirectory(resolved)) {
                throw IllegalStateException("$file: is a directory")
            }
            return ctx.process.fs
                .readBytes(resolved)
                .decodeToString() to file
        }
        // No file: drain stdin into memory. Required for non-seekable pipes.
        val bytes = ctx.stdin.readAllBytes()
        return bytes.decodeToString() to null
    }

    private fun resolve(
        cwd: String,
        path: String,
    ): String =
        if (path.startsWith("/")) {
            path
        } else if (cwd.endsWith("/")) {
            "$cwd$path"
        } else {
            "$cwd/$path"
        }

    private sealed interface Parsed {
        data class Ok(
            val file: String?,
            val showLineNumbers: Boolean,
        ) : Parsed

        data object Usage : Parsed

        data object ShowVersion : Parsed
    }

    private fun parseArgs(args: List<String>): Parsed {
        var file: String? = null
        var showLineNumbers = false
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

                a == "-N" || a == "--LINE-NUMBERS" -> {
                    showLineNumbers = true
                }

                a == "--" -> {
                    if (i + 1 < args.size) file = args[i + 1]
                    return Parsed.Ok(file, showLineNumbers)
                }

                a.startsWith("-") && a.length > 1 -> {
                    return Parsed.Usage
                }

                else -> {
                    // v1: at most one file. Extras are a usage error rather
                    // than silently ignored.
                    if (file != null) return Parsed.Usage
                    file = a
                }
            }
            i++
        }
        return Parsed.Ok(file, showLineNumbers)
    }

    private companion object {
        const val USAGE: String =
            "Usage: less [OPTIONS] [FILE]\n" +
                "  -h, --help            show this help\n" +
                "  -V, --version         show version\n" +
                "  -N                    show line numbers in the gutter\n"
    }
}
