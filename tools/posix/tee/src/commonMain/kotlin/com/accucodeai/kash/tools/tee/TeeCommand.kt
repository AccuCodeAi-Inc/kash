package com.accucodeai.kash.tools.tee

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.BrokenPipeException
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import kotlinx.coroutines.yield
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * `tee` — read from stdin and write to stdout and to each named file.
 *
 * Flags:
 * - `-a` append to each file instead of truncating.
 * - `-i` ignore SIGINT. kash has no signal model — accepted silently.
 * - `--` ends option processing.
 *
 * Semantics:
 * - Input is copied to all sinks (stdout + every successfully opened file) in
 *   8 KiB chunks. Each sink is written independently; a failing sink is
 *   dropped from the fan-out but the others keep going.
 * - If a file cannot be opened, an error is reported to stderr and `tee`
 *   continues with the remaining outputs, exiting non-zero at the end.
 * - Closing of stdout (`BrokenPipeException`) drops stdout from the fan-out
 *   but file writes continue — POSIX `tee` survives a SIGPIPE on stdout.
 * - A closed file sink stops attempts to that one sink only.
 */
public class TeeCommand :
    Command,
    CommandSpec {
    override val name: String = "tee"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Opts(
        val append: Boolean,
        val files: List<String>,
    )

    /** One output sink in the fan-out, with a display name for error reporting. */
    private class Output(
        val name: String,
        val sink: SuspendSink,
        /** When set, this sink has errored and should be skipped on future writes. */
        var broken: Boolean = false,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = parseArgs(args, ctx) ?: return CommandResult(exitCode = 2)

        var exitCode = 0
        val outputs = mutableListOf<Output>()
        outputs += Output(name = "stdout", sink = ctx.stdout)
        for (f in opts.files) {
            try {
                val s =
                    ctx.process.fs.sink(
                        Paths.resolve(ctx.process.cwd, f),
                        append = opts.append,
                        mode =
                            0b110_110_110 and ctx.process.umask.inv(),
                    )
                outputs += Output(name = f, sink = s)
            } catch (t: Throwable) {
                ctx.stderr.writeUtf8("tee: $f: ${t.message ?: "cannot open for writing"}\n")
                exitCode = 1
            }
        }

        // Stream stdin in 8 KiB chunks, fanning out each chunk to every live sink.
        val chunk = Buffer()
        try {
            while (true) {
                val n = ctx.stdin.readAtMostTo(chunk, 8 * 1024L)
                if (n == -1L) break
                if (n == 0L) continue
                // Snapshot bytes once; copy into each sink via a per-write Buffer.
                val bytes = chunk.readByteArray()
                for (o in outputs) {
                    if (o.broken) continue
                    try {
                        val buf = Buffer()
                        buf.write(bytes)
                        o.sink.write(buf, buf.size)
                        o.sink.flush()
                    } catch (_: BrokenPipeException) {
                        // SIGPIPE on stdout (or a piped file backend): drop just this sink.
                        o.broken = true
                        if (o.name != "stdout") exitCode = 1
                    } catch (t: Throwable) {
                        ctx.stderr.writeUtf8("tee: ${o.name}: ${t.message ?: "write error"}\n")
                        o.broken = true
                        if (o.name != "stdout") exitCode = 1
                    }
                }
                // If every sink is broken, no reason to keep reading.
                if (outputs.all { it.broken }) break
                yield()
            }
        } finally {
            // Close file sinks; stdout is owned by the caller.
            for (o in outputs) {
                if (o.name == "stdout") continue
                try {
                    o.sink.close()
                } catch (t: Throwable) {
                    if (!o.broken) {
                        ctx.stderr.writeUtf8("tee: ${o.name}: ${t.message ?: "close error"}\n")
                        exitCode = 1
                    }
                }
            }
        }

        return CommandResult(exitCode = exitCode)
    }

    private suspend fun parseArgs(
        args: List<String>,
        ctx: CommandContext,
    ): Opts? {
        var append = false
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    files += a
                }

                a == "-a" -> {
                    append = true
                }

                a == "-i" -> {
                    // accepted; kash has no signal model
                }

                a.startsWith("-") && a.length > 1 && !a.startsWith("--") -> {
                    // bundled short flags, e.g. -ai
                    for (c in a.substring(1)) {
                        when (c) {
                            'a' -> {
                                append = true
                            }

                            'i' -> {}

                            else -> {
                                ctx.stderr.writeUtf8("tee: invalid option -- '$c'\n")
                                return null
                            }
                        }
                    }
                }

                a.startsWith("--") -> {
                    ctx.stderr.writeUtf8("tee: unrecognized option: $a\n")
                    return null
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return Opts(append = append, files = files)
    }
}
