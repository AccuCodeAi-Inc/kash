package com.accucodeai.kash.tools.bc

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `bc` — arbitrary-precision calculator language.
 *
 * Argument grammar:
 *
 *     bc [-l] [-q] [-e EXPR]... [-V|-h|--help|--version] [file ...]
 *
 *  - `-l`            load the math library (sin/cos/atan/ln/exp/Bessel).
 *  - `-q`            suppress welcome banner (kash bc never prints one,
 *                    so accepted-and-ignored).
 *  - `-e EXPR`       evaluate EXPR as bc source. May repeat. If `-e` is
 *                    used, the implicit "drop to stdin after files" step
 *                    is suppressed (GNU behavior).
 *  - `-h`/`--help`   print usage and exit 0.
 *  - `-V`/`--version` print version and exit 0.
 *  - `file ...`      paths to bc source files, processed in order.
 *
 * After all `-e` and file inputs are processed, if no `-e` flag was given
 * and stdin is available, bc reads further commands from stdin until EOF
 * (POSIX behavior). With `-e`, stdin is NOT read.
 *
 * Out-of-scope (documented for future work):
 *  - Postfix `++`/`--`.
 *  - `?:` ternary.
 *  - `read()` builtin.
 *  - GNU-specific `warranty`/`limits`.
 *  - Interactive line editing.
 */
public class BcCommand :
    Command,
    CommandSpec {
    override val name: String = "bc"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var loadMathLib = false

        @Suppress("UNUSED_VARIABLE")
        var quiet = false
        val expressions = mutableListOf<String>()
        val files = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-l" || a == "--mathlib" -> {
                    loadMathLib = true
                    i++
                }

                a == "-q" || a == "--quiet" -> {
                    quiet = true
                    i++
                }

                a == "-i" || a == "--interactive" -> {
                    // no-op
                    i++
                }

                a == "-s" || a == "--standard" || a == "-w" || a == "--warn" -> {
                    // no-op
                    i++
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8(USAGE)
                    return CommandResult()
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("bc (kash) 1.0\n")
                    return CommandResult()
                }

                a == "-e" || a == "--expression" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("bc: $a requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    expressions += args[i + 1]
                    i += 2
                }

                a.startsWith("--expression=") -> {
                    expressions += a.substring("--expression=".length)
                    i++
                }

                a.startsWith("-e") && a.length > 2 -> {
                    expressions += a.substring(2)
                    i++
                }

                a == "--" -> {
                    i++
                    while (i < args.size) {
                        files += args[i]
                        i++
                    }
                }

                a.startsWith("-") && a != "-" && a.length > 1 -> {
                    ctx.stderr.writeUtf8("bc: unknown option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    files += a
                    i++
                }
            }
        }

        // Assemble program source: math lib first (optional), then each file's
        // contents, then `-e` expressions joined with newlines.
        val parts = mutableListOf<String>()
        for (path in files) {
            val abs = Paths.resolve(ctx.process.cwd, path)
            try {
                val txt =
                    ctx.process.fs
                        .readBytes(abs)
                        .decodeToString()
                parts += txt
            } catch (_: FileNotFound) {
                ctx.stderr.writeUtf8("bc: $path: No such file or directory\n")
                return CommandResult(exitCode = 2)
            }
        }
        for (e in expressions) parts += e

        // If neither -e nor file arguments were supplied OR -e was not given,
        // also read stdin. POSIX: stdin always; GNU: stdin skipped when -e used.
        val readStdin = expressions.isEmpty()
        if (readStdin) {
            val stdinText = ctx.stdin.readUtf8Text()
            if (stdinText.isNotEmpty()) parts += stdinText
        }
        val program = parts.joinToString("\n")

        if (program.isBlank()) return CommandResult()

        // Compile (lex + parse).
        val ast =
            try {
                val toks = BcLexer(program).tokenize()
                BcParser(toks).parseProgram()
            } catch (e: BcParseError) {
                ctx.stderr.writeUtf8("bc: parse error: ${e.message}\n")
                return CommandResult(exitCode = 1)
            }

        val interp =
            BcInterpreter(
                emit = { text ->
                    // Suspend write — call into the suspend sink from a non-suspending lambda
                    // by buffering and flushing per chunk. We collect into a StringBuilder
                    // and flush after run to avoid mixing suspend in the eval path.
                    sink.append(text)
                },
                emitErr = { text -> errSink.append(text) },
            )
        if (loadMathLib) interp.loadMathLib()

        return try {
            interp.run(ast)
            flush(ctx)
            CommandResult()
        } catch (_: BcInterpreter.QuitSignal) {
            flush(ctx)
            CommandResult()
        } catch (e: BcRuntimeError) {
            flush(ctx)
            ctx.stderr.writeUtf8("bc: ${e.message}\n")
            CommandResult(exitCode = 1)
        } catch (e: BcParseError) {
            flush(ctx)
            ctx.stderr.writeUtf8("bc: parse error: ${e.message}\n")
            CommandResult(exitCode = 1)
        }
    }

    // Synchronous-buffer plumbing: the interpreter is purely synchronous, but
    // ctx.stdout.writeUtf8 is `suspend`. We collect into a builder and flush
    // once at the end of run. For long-running scripts this means output is
    // buffered — that's fine for `bc` which produces line-rate output.
    private val sink = StringBuilder()
    private val errSink = StringBuilder()

    private suspend fun flush(ctx: CommandContext) {
        if (sink.isNotEmpty()) {
            ctx.stdout.writeUtf8(sink.toString())
            sink.clear()
        }
        if (errSink.isNotEmpty()) {
            ctx.stderr.writeUtf8(errSink.toString())
            errSink.clear()
        }
    }

    private companion object {
        const val USAGE = "usage: bc [-l] [-q] [-e expression]... [file ...]\n"
    }
}
