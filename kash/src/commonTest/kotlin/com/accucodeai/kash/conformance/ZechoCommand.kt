package com.accucodeai.kash.conformance

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `zecho` — bash test-suite helper. Source lives at
 * `external/bash/support/zecho.c`. Behavior: print args separated by a single
 * space, followed by a newline. No option parsing (so `-n`/`-e` are emitted
 * literally — that's the whole point of `zecho` vs `echo` in the bash tests).
 *
 * `bash/tests/braces.tests` invokes `zecho` inside command substitutions to
 * verify that brace expansion runs on the unquoted result of `$(...)`.
 * Registered into the conformance harness registry by [ScriptPairRunner] so
 * it doesn't pollute production registries.
 */
public class ZechoCommand :
    Command,
    CommandSpec {
    override val name: String = "zecho"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        ctx.stdout.writeUtf8(args.joinToString(" ") + "\n")
        return CommandResult(exitCode = 0)
    }
}
