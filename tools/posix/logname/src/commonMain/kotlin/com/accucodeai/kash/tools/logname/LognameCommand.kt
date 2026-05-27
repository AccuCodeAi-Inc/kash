package com.accucodeai.kash.tools.logname

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX 🅄 `logname` — print the user's login name.
 *
 * POSIX RATIONALE explicitly forbids reading `$LOGNAME`: it must come from a
 * system source (`getlogin()` equivalent) so a stale or hostile env var
 * can't lie about who's running the shell. We source from
 * [com.accucodeai.kash.api.user.UserDatabase.current], which is pure code
 * supplied at session construction.
 *
 * Accepts no options and no operands. Any argument → exit 1 with diagnostic.
 */
public class LognameCommand :
    Command,
    CommandSpec {
    override val name: String = "logname"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.isNotEmpty()) {
            ctx.stderr.writeUtf8("logname: extra operand '${args[0]}'\n")
            return CommandResult(exitCode = 1)
        }
        ctx.stdout.writeUtf8("${ctx.userDb.current().name}\n")
        return CommandResult()
    }
}
