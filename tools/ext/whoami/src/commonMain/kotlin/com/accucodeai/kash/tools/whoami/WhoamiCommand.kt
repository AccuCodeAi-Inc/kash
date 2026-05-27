package com.accucodeai.kash.tools.whoami

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `whoami` — print the effective user's login name. Equivalent to `id -un`.
 *
 * **Not POSIX** (BSD/GNU extension), but universally present in real
 * scripts; ships untagged-POSIX to flag the divergence. Sources from
 * [com.accucodeai.kash.api.user.UserDatabase.current], same as `logname`.
 */
public class WhoamiCommand :
    Command,
    CommandSpec {
    override val name: String = "whoami"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.isNotEmpty()) {
            ctx.stderr.writeUtf8("whoami: extra operand '${args[0]}'\n")
            return CommandResult(exitCode = 1)
        }
        ctx.stdout.writeUtf8("${ctx.userDb.current().name}\n")
        return CommandResult()
    }
}
