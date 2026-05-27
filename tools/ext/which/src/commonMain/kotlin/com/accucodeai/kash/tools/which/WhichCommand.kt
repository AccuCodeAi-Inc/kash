package com.accucodeai.kash.tools.which

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.fs.Paths

/**
 * Non-POSIX but ubiquitous. Locates each operand by walking `$PATH` exactly
 * like the interpreter's command resolution: for each NAME, prints the
 * first PATH directory whose entry would be invoked. Per the
 * widely-followed BSD/GNU contract:
 *
 *  - Exit 0 if every NAME was located.
 *  - Exit 1 if any NAME could not be located.
 *  - Exit 2 on usage error.
 *
 * Special builtins and functions are not utilities — `which :` and
 * `which if` exit non-zero by default (use `command -v` for those).
 *
 * Flags:
 *  - `-a` / `--all` — print every matching path across all PATH directories
 *    (deduped), not just the first.
 *  - `-s` / `--silent` — suppress output; exit status only.
 *  - `--`, `-h` / `--help`.
 */
public class WhichCommand :
    Command,
    CommandSpec {
    override val name: String = "which"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var all = false
        var silent = false
        val names = mutableListOf<String>()
        var endOfOptions = false
        for (a in args) {
            when {
                endOfOptions -> {
                    names += a
                }

                a == "--" -> {
                    endOfOptions = true
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                a == "-a" || a == "--all" -> {
                    all = true
                }

                a == "-s" || a == "--silent" -> {
                    silent = true
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Bundled short flags (`-as`).
                    for (ch in a.drop(1)) {
                        when (ch) {
                            'a' -> {
                                all = true
                            }

                            's' -> {
                                silent = true
                            }

                            else -> {
                                ctx.stderr.writeUtf8("which: invalid option: -$ch\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                }

                else -> {
                    names += a
                }
            }
        }
        if (names.isEmpty()) {
            ctx.stderr.writeUtf8("which: missing operand\n")
            return CommandResult(exitCode = 2)
        }

        val pathEnv = ctx.process.env["PATH"]
        val dirs = splitPath(pathEnv)
        var exit = 0
        for (name in names) {
            if ('/' in name) {
                // Path-qualified — POSIX command-search step 1.d. Verify and print.
                if (ctx.process.fs.exists(name) && !ctx.process.fs.isDirectory(name)) {
                    if (!silent) ctx.stdout.writeUtf8("$name\n")
                } else {
                    if (!silent) ctx.stderr.writeUtf8("which: $name: not found\n")
                    exit = 1
                }
                continue
            }
            val hits = mutableListOf<String>()
            val seen = mutableSetOf<String>()
            for (dir in dirs) {
                val candidate = if (dir.isEmpty()) Paths.resolve(ctx.process.cwd, name) else "$dir/$name"
                if (candidate in seen) continue
                seen += candidate
                val exists =
                    try {
                        ctx.process.fs.exists(candidate) && !ctx.process.fs.isDirectory(candidate)
                    } catch (_: Throwable) {
                        false
                    }
                if (!exists) continue
                hits += candidate
                if (!all) break
            }
            if (hits.isEmpty()) {
                if (!silent) ctx.stderr.writeUtf8("which: $name: not found\n")
                exit = 1
            } else if (!silent) {
                for (p in hits) ctx.stdout.writeUtf8("$p\n")
            }
        }
        return CommandResult(exitCode = exit)
    }

    private companion object {
        const val HELP: String =
            """Usage: which [-a] [-s] NAME...

Locate each NAME by walking ${'$'}PATH. Print the first match (or all matches
with -a). Exit 1 if any NAME is not found. Use `command -v` for builtins,
functions, and aliases.
"""
    }
}
