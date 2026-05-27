package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.tools.git.GitEnv

/**
 * Handler for one `git <sub>` subcommand. Implementations parse their
 * own arguments and write to `ctx.stdout`/`ctx.stderr`. The
 * dispatching layer in [com.accucodeai.kash.tools.git.GitCommand] is
 * responsible for global flags (`-C`, `--git-dir`, …) — by the time a
 * handler runs, [GitEnv.cwd] reflects them.
 */
public interface GitSubcommand {
    public val name: String

    public suspend fun run(
        args: List<String>,
        ctx: CommandContext,
        env: GitEnv,
    ): CommandResult
}
