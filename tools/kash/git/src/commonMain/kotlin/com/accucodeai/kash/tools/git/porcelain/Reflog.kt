package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo

/**
 * `git reflog [show] [<ref>]` — list reflog entries. Output format:
 *
 * ```
 * <short-sha> <ref>@{<N>}: <message>
 * ```
 *
 * where N counts from the most recent move (0). Other reflog
 * subcommands (`expire`, `delete`, `exists`) are accepted as no-ops
 * for compatibility (`exists` returns 0 iff there's a log file).
 */
public fun gitReflogSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "reflog"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            val nonOpts = args.filterNot { it.startsWith("-") }
            val sub = nonOpts.firstOrNull() ?: "show"
            val rest = if (nonOpts.isEmpty()) emptyList() else nonOpts.drop(1)

            return when (sub) {
                "show" -> {
                    showLog(repo, rest.firstOrNull() ?: "HEAD", ctx)
                }

                "exists" -> {
                    val ref = rest.firstOrNull() ?: "HEAD"
                    val entries = repo.reflog.read(ref)
                    CommandResult(exitCode = if (entries.isEmpty()) 1 else 0)
                }

                "expire", "delete" -> {
                    CommandResult(exitCode = 0)
                }

                // accepted-but-no-op
                else -> {
                    // Bare `git reflog <ref>` — treat as `show <ref>`.
                    showLog(repo, sub, ctx)
                }
            }
        }
    }

private suspend fun showLog(
    repo: GitRepo,
    ref: String,
    ctx: CommandContext,
): CommandResult {
    val entries = repo.reflog.read(ref)
    // Most-recent-first; index N counts from current backwards.
    val n = entries.size
    for (i in entries.indices.reversed()) {
        val e = entries[i]
        val idx = n - 1 - i
        val short = e.newSha.take(7)
        ctx.stdout.writeUtf8("$short $ref@{$idx}: ${e.message}\n")
    }
    return CommandResult(exitCode = 0)
}
