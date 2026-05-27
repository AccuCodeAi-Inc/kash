package com.accucodeai.kash.commands

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

public class CdCommand :
    Command,
    CommandSpec {
    override val name: String = "cd"
    override val kind: CommandKind = CommandKind.BUILTIN
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.STATEFUL)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        // POSIX `cd [-L|-P] [dir]`. `-L` (logical, the default) and `-P`
        // (physical) control symlink resolution; for kash's virtual FS
        // both behaviors collapse to the same thing, but we still accept
        // and skip the flags so `command cd -P /` works as written.
        // `-e` and `-@` (bash extensions) are accepted as no-ops.
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a == "--") {
                i++
                break
            }
            if (a == "-") break // `cd -` is a target, not a flag.
            if (a.startsWith("-") && a.length >= 2 && a.drop(1).all { it in "LPe@" }) {
                i++
                continue
            }
            break
        }
        val rest = if (i > 0) args.drop(i) else args
        val rawTarget = rest.firstOrNull() ?: ctx.process.env["HOME"] ?: "/"
        // `cd -` swaps PWD and OLDPWD, prints the new PWD to stdout. POSIX.
        val (target, echo) =
            if (rawTarget == "-") {
                val old = ctx.process.env["OLDPWD"]
                if (old == null) {
                    ctx.stderr.writeUtf8("cd: OLDPWD not set\n")
                    return CommandResult(exitCode = 1)
                }
                old to true
            } else {
                rawTarget to false
            }
        val resolved = Paths.resolve(ctx.process.cwd, target)
        if (!ctx.process.fs.isDirectory(resolved)) {
            ctx.stderr.writeUtf8("cd: $target: Not a directory\n")
            return CommandResult(exitCode = 1)
        }
        // bash: OLDPWD always tracks the prior PWD on a successful cd.
        // Mutate the env map directly — the interpreter wraps the same
        // map into CommandContext.
        ctx.process.env["OLDPWD"] = ctx.process.cwd
        if (echo) ctx.stdout.writeUtf8("$resolved\n")
        return CommandResult(newCwd = resolved)
    }
}
