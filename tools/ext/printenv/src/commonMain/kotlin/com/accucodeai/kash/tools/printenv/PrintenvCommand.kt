package com.accucodeai.kash.tools.printenv

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `printenv \[varname...\]` — minimal clone of BSD printenv(1).
 *
 * No args: print each `NAME=value` from the environment, one per line.
 * Args: print the value of each named variable on its own line; exit non-zero
 * if any name is unset (matches bash's support/printenv.c).
 */
public class PrintenvCommand :
    Command,
    CommandSpec {
    override val name: String = "printenv"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.isEmpty()) {
            for ((k, v) in ctx.process.env) ctx.stdout.writeUtf8("$k=$v\n")
            return CommandResult(exitCode = 0)
        }
        var missing = false
        for (name in args) {
            val v = ctx.process.env[name]
            if (v == null) {
                missing = true
            } else {
                ctx.stdout.writeUtf8("$v\n")
            }
        }
        return CommandResult(exitCode = if (missing) 1 else 0)
    }
}
