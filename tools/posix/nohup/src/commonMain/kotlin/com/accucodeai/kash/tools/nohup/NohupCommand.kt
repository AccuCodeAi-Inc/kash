package com.accucodeai.kash.tools.nohup

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * POSIX `nohup` — invoke a utility immune to hangups.
 *
 * Real systems would: ignore SIGHUP, then `exec` the utility. kash doesn't
 * model HUP delivery, so this implementation:
 *  - Dispatches the utility via [com.accucodeai.kash.api.UtilityRunner]
 *    (POSIX `execvp`-style — no inline assignments, no intrinsics).
 *  - If stdout is a tty, redirects stdout to `nohup.out` in cwd (append).
 *    On failure, falls back to `$HOME/nohup.out`. Emits the standard
 *    `nohup: appending output to 'nohup.out'` diagnostic to stderr.
 *  - If stderr is a tty, sends stderr to the same destination as stdout.
 *
 * Exit codes (POSIX):
 *  - 126 if the utility is found but not invokable.
 *  - 127 if the utility cannot be found, or `nohup` was invoked without
 *    a utility name.
 *  - Otherwise, the utility's own exit code.
 */
public class NohupCommand :
    Command,
    CommandSpec {
    override val name: String = "nohup"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    @Suppress("ReturnCount")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // Strip leading `--`. POSIX defines no options for nohup other than
        // the standard help/version extensions.
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                i++
                break
            }
            if (a == "-h" || a == "--help") {
                ctx.stdout.writeUtf8("Usage: nohup COMMAND [ARG]...\n")
                return CommandResult(exitCode = 0)
            }
            if (a == "-V" || a == "--version") {
                ctx.stdout.writeUtf8("nohup (kash)\n")
                return CommandResult(exitCode = 0)
            }
            if (a.startsWith("-") && a.length > 1 && a != "-") {
                ctx.stderr.writeUtf8("nohup: invalid option: $a\n")
                return CommandResult(exitCode = 125)
            }
            break
        }

        if (i >= args.size) {
            ctx.stderr.writeUtf8("nohup: missing operand\n")
            return CommandResult(exitCode = 127)
        }
        val utility = args[i]
        val utilArgs = args.subList(i + 1, args.size)

        val runner =
            ctx.utilityRunner ?: run {
                ctx.stderr.writeUtf8("nohup: not supported in this context\n")
                return CommandResult(exitCode = 127)
            }

        // Decide where stdout/stderr go.
        val stdoutIsTty = ctx.stdoutIsTty
        val stderrIsTty = ctx.stderrIsTty

        var outSink: SuspendSink = ctx.stdout
        var errSink: SuspendSink = ctx.stderr
        var openedSink: SuspendSink? = null

        if (stdoutIsTty) {
            val (path, sink) =
                openNohupOut(ctx) ?: run {
                    ctx.stderr.writeUtf8("nohup: failed to open nohup.out\n")
                    return CommandResult(exitCode = 127)
                }
            ctx.stderr.writeUtf8("nohup: appending output to '$path'\n")
            outSink = sink
            openedSink = sink
            if (stderrIsTty) errSink = sink
        } else if (stderrIsTty) {
            // POSIX: stderr-but-not-stdout-is-tty → redirect stderr to stdout.
            errSink = outSink
        }

        return try {
            val rc =
                runner.run(
                    name = utility,
                    args = utilArgs,
                    stdin = ctx.stdin,
                    stdout = outSink,
                    stderr = errSink,
                )
            CommandResult(exitCode = rc)
        } finally {
            openedSink?.let { runCatching { it.flush() } }
            openedSink?.let { runCatching { it.close() } }
        }
    }

    /**
     * Open `nohup.out` in cwd (preferred) or `$HOME/nohup.out` (fallback).
     * Returns (resolved-path, sink) on success.
     */
    private fun openNohupOut(ctx: CommandContext): Pair<String, SuspendSink>? {
        val cwdAttempt = Paths.resolve(ctx.process.cwd, "nohup.out")
        runCatching {
            return "nohup.out" to ctx.process.fs.sink(cwdAttempt, append = true)
        }
        val home = ctx.process.env["HOME"] ?: return null
        val homePath = Paths.resolve(home, "nohup.out")
        runCatching {
            return "$home/nohup.out" to ctx.process.fs.sink(homePath, append = true)
        }
        return null
    }
}
