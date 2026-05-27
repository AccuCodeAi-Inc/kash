package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode

/**
 * `git show` — print an object. v1 supports the two forms an LLM
 * reaches for most often:
 *  - `git show <rev>` — commit summary + (eventually) its diff. v1
 *    prints the commit header form; diff is the [gitDiffSubcommand]'s
 *    job once it lands.
 *  - `git show <rev>:<path>` — print the blob at `<path>` as of
 *    `<rev>`. This is the form scripts use to fetch historical file
 *    content; we get it exactly right.
 */
public fun gitShowSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "show"

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

            val positional = args.filterNot { it.startsWith("-") }
            val spec = positional.firstOrNull() ?: "HEAD"

            if (':' in spec) {
                val rev = spec.substringBefore(':')
                val path = spec.substringAfter(':')
                val commitSha =
                    resolveRev(repo, rev) ?: run {
                        ctx.stderr.writeUtf8("fatal: bad revision '$rev'\n")
                        return CommandResult(exitCode = 128)
                    }
                val commit = repo.objects.readCommit(commitSha)
                val blobSha = lookupPath(repo, commit.tree, path)
                if (blobSha == null) {
                    ctx.stderr.writeUtf8("fatal: path '$path' does not exist in '$rev'\n")
                    return CommandResult(exitCode = 128)
                }
                val bytes = repo.objects.readBlob(blobSha)
                ctx.stdout.writeUtf8(bytes.decodeToString())
                return CommandResult(exitCode = 0)
            }

            val sha =
                resolveRev(repo, spec) ?: run {
                    ctx.stderr.writeUtf8("fatal: bad revision '$spec'\n")
                    return CommandResult(exitCode = 128)
                }
            val parsed = repo.objects.readParsed(sha)
            when (parsed.type) {
                com.accucodeai.kash.tools.git.plumbing.ObjectType.COMMIT -> {
                    val commit = repo.objects.readCommit(sha)
                    ctx.stdout.writeUtf8("commit $sha\n")
                    ctx.stdout.writeUtf8("Author: ${commit.author.name} <${commit.author.email}>\n")
                    ctx.stdout.writeUtf8("Date:   ${commit.author.whenSeconds} ${commit.author.tz}\n")
                    ctx.stdout.writeUtf8("\n")
                    for (line in commit.message.trimEnd('\n').split('\n')) {
                        ctx.stdout.writeUtf8("    $line\n")
                    }
                }

                com.accucodeai.kash.tools.git.plumbing.ObjectType.BLOB -> {
                    ctx.stdout.writeUtf8(parsed.payload.decodeToString())
                }

                com.accucodeai.kash.tools.git.plumbing.ObjectType.TREE -> {
                    for (e in repo.objects.readTree(sha)) {
                        ctx.stdout.writeUtf8("${e.mode.wire} ${e.sha}\t${e.name}\n")
                    }
                }

                com.accucodeai.kash.tools.git.plumbing.ObjectType.TAG -> {
                    ctx.stdout.writeUtf8(parsed.payload.decodeToString())
                }
            }
            return CommandResult(exitCode = 0)
        }

        private suspend fun lookupPath(
            repo: GitRepo,
            rootTree: String,
            path: String,
        ): String? {
            var curTree = rootTree
            val parts = path.split('/')
            for ((i, part) in parts.withIndex()) {
                val entries = repo.objects.readTree(curTree)
                val match = entries.firstOrNull { it.name == part } ?: return null
                if (i == parts.lastIndex) {
                    return if (match.mode == FileMode.TREE) null else match.sha
                }
                if (match.mode != FileMode.TREE) return null
                curTree = match.sha
            }
            return null
        }
    }
