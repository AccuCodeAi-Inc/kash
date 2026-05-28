package com.accucodeai.kash.tools.python3

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `python3` — execute Python via a pluggable [PythonEngine].
 *
 * Argv handling mirrors CPython's launcher for the cases scripts actually
 * reach for:
 * - `python3 -c CODE [ARGS…]`     execute CODE
 * - `python3 -m MODULE [ARGS…]`   run MODULE as a script
 * - `python3 FILE.py [ARGS…]`     execute FILE
 * - `python3` / `python3 -`       read program from stdin
 * - `python3 --version`           print engine name + Python version
 * - `python3 --help`              usage
 *
 * Behavior the engine owns (and this command does not):
 * filesystem virtualization (Python `open()` ⇒ `ctx.process.fs`, the opener-bound facade), sandbox policy
 * (no native, no subprocess, no host classes), and timeout enforcement.
 *
 * Exit codes follow CPython where reasonable: `0` on success, `1` on
 * uncaught exception, `2` on argv errors, `124` on timeout (matches
 * `timeout(1)`).
 */
public class Python3Command(
    private val engine: PythonEngine,
) : Command,
    CommandSpec {
    override val name: String = "python3"

    /** `python` is registered as an alias since modern distros symlink it
     *  to python3 and most user-written scripts and shell prompts type just
     *  `python`. */
    override val aliases: List<String> = listOf("python")
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val parsed = parseArgs(args)
        return when (parsed) {
            is ParseResult.UsageError -> {
                ctx.stderr.writeUtf8("python3: ${parsed.message}\n")
                CommandResult(exitCode = 2)
            }

            is ParseResult.ShowHelp -> {
                ctx.stdout.writeUtf8(HELP_TEXT)
                CommandResult()
            }

            is ParseResult.ShowVersion -> {
                ctx.stdout.writeUtf8("Python 3 (${engine.name})\n")
                CommandResult()
            }

            is ParseResult.Ok -> {
                if (ctx.sandbox == com.accucodeai.kash.api.sandbox.SandboxPolicy.SAFE) {
                    ctx.stderr.writeUtf8(
                        "python3: disabled under sandbox=SAFE (no host-FS access permitted)\n",
                    )
                    return CommandResult(exitCode = 126)
                }
                // Interactive REPL: only when invoked bare (source=Stdin)
                // AND the caller's stdin is actually a terminal. The per-fd
                // tty bit set by the interpreter accounts for pipe stages
                // and `<` redirection — `echo x | python3` lands here with
                // source=Stdin but stdinIsTty=false, so we fall through to
                // the normal stdin-as-program path.
                if (parsed.source is PythonSource.Stdin && ctx.process.isTty(0)) {
                    val code =
                        engine.runInteractiveRepl(
                            ctx.process.fs,
                            ctx.process.cwd,
                            ctx.process.env,
                            ctx.stdin,
                            ctx.stdout,
                            ctx.stderr,
                        )
                    if (code == PythonEngine.REPL_NOT_SUPPORTED) {
                        ctx.stderr.writeUtf8(
                            "python3: interactive REPL not supported by engine '${engine.name}'\n",
                        )
                        return CommandResult(exitCode = 2)
                    }
                    return CommandResult(exitCode = code)
                }
                val code =
                    engine.execute(
                        source = parsed.source,
                        scriptArgs = parsed.scriptArgs,
                        fs = ctx.process.fs,
                        env = ctx.process.env,
                        cwd = ctx.process.cwd,
                        stdin = ctx.stdin,
                        stdout = ctx.stdout,
                        stderr = ctx.stderr,
                        timeoutMillis = DEFAULT_TIMEOUT_MS,
                        sandbox = ctx.sandbox,
                        networkPolicy = ctx.process.machine.networkPolicy,
                    )
                CommandResult(exitCode = code)
            }
        }
    }

    private sealed interface ParseResult {
        data class Ok(
            val source: PythonSource,
            val scriptArgs: List<String>,
        ) : ParseResult

        data class UsageError(
            val message: String,
        ) : ParseResult

        data object ShowHelp : ParseResult

        data object ShowVersion : ParseResult
    }

    private fun parseArgs(args: List<String>): ParseResult {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-c" -> {
                    if (i + 1 >= args.size) {
                        return ParseResult.UsageError("option requires an argument -- 'c'")
                    }
                    return ParseResult.Ok(
                        source = PythonSource.Code(args[i + 1]),
                        scriptArgs = args.subList(i + 2, args.size),
                    )
                }

                a == "-m" -> {
                    if (i + 1 >= args.size) {
                        return ParseResult.UsageError("option requires an argument -- 'm'")
                    }
                    return ParseResult.Ok(
                        source = PythonSource.Module(args[i + 1]),
                        scriptArgs = args.subList(i + 2, args.size),
                    )
                }

                a == "--version" || a == "-V" -> {
                    return ParseResult.ShowVersion
                }

                a == "--help" || a == "-h" -> {
                    return ParseResult.ShowHelp
                }

                a == "-" -> {
                    return ParseResult.Ok(
                        source = PythonSource.Stdin,
                        scriptArgs = args.subList(i + 1, args.size),
                    )
                }

                a == "--" -> {
                    // Remaining args: first is script path (or empty ⇒ stdin), rest scriptArgs.
                    val rest = args.subList(i + 1, args.size)
                    return if (rest.isEmpty()) {
                        ParseResult.Ok(PythonSource.Stdin, emptyList())
                    } else {
                        ParseResult.Ok(PythonSource.File(rest[0]), rest.subList(1, rest.size))
                    }
                }

                a.startsWith("-") && a.length > 1 -> {
                    return ParseResult.UsageError("unknown option: $a")
                }

                else -> {
                    // First positional is the script path; everything after is sys.argv[1:].
                    return ParseResult.Ok(
                        source = PythonSource.File(a),
                        scriptArgs = args.subList(i + 1, args.size),
                    )
                }
            }
        }
        // No operands at all ⇒ read program from stdin.
        return ParseResult.Ok(PythonSource.Stdin, emptyList())
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 30_000

        const val HELP_TEXT: String =
            """usage: python3 [option] ... [-c cmd | -m mod | file | -] [arg] ...
Options:
  -c cmd     program passed in as string
  -m mod     run library module as a script
  -          program read from stdin
  -V, --version  print version info and exit
  -h, --help     print this help and exit
"""
    }
}
