package com.accucodeai.kash.tools.tty

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX 🅄 `tty` — print the name of the terminal on standard input, or
 * "not a tty" with exit 1 if stdin is not connected to a terminal.
 *
 * Behavior maps directly to fd 0's OFD:
 *   - `isTty == true` and `path != null` → print the path (typically
 *     `/dev/tty`; in the future `/dev/pts/N` once we model pty slaves).
 *   - `isTty == false` → print "not a tty" to stdout, exit 1. POSIX
 *     specifies the diagnostic goes to stdout, not stderr — `tty(1)`
 *     predates the convention.
 *
 * Options: `-s` suppresses the path output (just sets exit status).
 *
 * A thin wrapper over `isatty(0)` + `ttyname(0)`. In kash, the OFD path
 * field IS our `ttyname` — set by [com.accucodeai.kash.api.installStdio]
 * when the host wires a real terminal handle.
 */
public class TtyCommand :
    Command,
    CommandSpec {
    override val name: String = "tty"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var silent = false
        for (a in args) {
            when (a) {
                "-s", "--silent", "--quiet" -> {
                    silent = true
                }

                "--" -> {
                    Unit
                }

                "--help" -> {
                    ctx.stdout.writeUtf8(
                        """
                        Usage: tty [OPTION]...
                        Print the file name of the terminal connected to standard input.

                          -s, --silent, --quiet   print nothing, only return an exit status
                        """.trimIndent() + "\n",
                    )
                    return CommandResult()
                }

                else -> {
                    if (a.startsWith("-")) {
                        ctx.stderr.writeUtf8("tty: invalid option: $a\n")
                        return CommandResult(exitCode = 2)
                    }
                    ctx.stderr.writeUtf8("tty: extra operand '$a'\n")
                    return CommandResult(exitCode = 2)
                }
            }
        }
        val ofd = ctx.process.fdTable[0]?.ofd
        return if (ofd?.isTty == true) {
            if (!silent) ctx.stdout.writeUtf8("${ofd.path ?: "/dev/tty"}\n")
            CommandResult(exitCode = 0)
        } else {
            if (!silent) ctx.stdout.writeUtf8("not a tty\n")
            CommandResult(exitCode = 1)
        }
    }
}
