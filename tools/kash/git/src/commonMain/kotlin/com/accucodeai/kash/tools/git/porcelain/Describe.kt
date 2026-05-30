package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git describe` — walk back from HEAD (or [rev]) until a tagged
 * commit is found, then emit `<tag>-<N>-g<abbrev>` where:
 *
 *  - `<tag>` is the closest reachable annotated tag (or any tag with
 *    `--tags`)
 *  - `<N>` is the number of commits between the tip and the tag
 *  - `<abbrev>` is the short sha of the tip (default 7 chars)
 *
 * Direct tag matches emit just the tag name (no `-N-g…` suffix).
 *
 * v1 flags:
 *  - `--tags` — also consider lightweight tags (we default to all
 *    tags anyway since the lightweight/annotated distinction doesn't
 *    affect reachability)
 *  - `--abbrev=<N>` — short-sha length (clamped to 1..40)
 *  - `--always` — fall back to the bare short-sha if no tag is found
 */
public fun gitDescribeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "describe"

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

            var abbrev = 7
            var always = false
            var rev = "HEAD"
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--tags" -> {
                        i++
                    }

                    a == "--always" -> {
                        always = true
                        i++
                    }

                    a.startsWith("--abbrev=") -> {
                        abbrev = a.substringAfter("=").toInt().coerceIn(1, 40)
                        i++
                    }

                    a == "--abbrev" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"--abbrev\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        abbrev = args[i + 1].toInt().coerceIn(1, 40)
                        i += 2
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git describe: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        rev = a
                        i++
                    }
                }
            }

            val tipSha =
                resolveRev(repo, rev) ?: run {
                    ctx.stderr.writeUtf8("fatal: Not a valid object name $rev\n")
                    return CommandResult(exitCode = 128)
                }

            // Build commit-sha -> tag-name map. Annotated tags are
            // peeled to their underlying commit so they participate in
            // ancestor matching.
            val tagsByCommit = mutableMapOf<String, MutableList<String>>()
            for ((refName, refSha) in repo.refs.listRefs("refs/tags")) {
                val name = refName.removePrefix("refs/tags/")
                val commitSha = peelTagToCommit(repo, refSha) ?: continue
                tagsByCommit.getOrPut(commitSha) { mutableListOf() } += name
            }

            // BFS from tip via first-parent (depth tracks distance).
            var depth = 0
            var cur: String? = tipSha
            while (cur != null) {
                val matched = tagsByCommit[cur]
                if (matched != null) {
                    // Prefer the lexically-greatest tag at a tie (matches
                    // real-git's "annotated, then lex order" heuristic
                    // closely enough for v1).
                    val tag = matched.sorted().last()
                    if (depth == 0) {
                        ctx.stdout.writeUtf8("$tag\n")
                    } else {
                        ctx.stdout.writeUtf8("$tag-$depth-g${tipSha.substring(0, abbrev)}\n")
                    }
                    return CommandResult(exitCode = 0)
                }
                cur =
                    try {
                        repo.objects
                            .readCommit(cur)
                            .parents
                            .firstOrNull()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        null
                    }
                depth++
            }

            if (always) {
                ctx.stdout.writeUtf8("${tipSha.substring(0, abbrev)}\n")
                return CommandResult(exitCode = 0)
            }
            ctx.stderr.writeUtf8("fatal: No names found, cannot describe anything.\n")
            return CommandResult(exitCode = 128)
        }
    }

private suspend fun peelTagToCommit(
    repo: GitRepo,
    sha: String,
): String? {
    var cur = sha
    repeat(8) {
        val parsed =
            try {
                com.accucodeai.kash.tools.git.plumbing
                    .parseFramedObject(repo.objects.read(cur))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return null
            }
        when (parsed.type) {
            com.accucodeai.kash.tools.git.plumbing.ObjectType.COMMIT -> {
                return cur
            }

            com.accucodeai.kash.tools.git.plumbing.ObjectType.TAG -> {
                cur =
                    com.accucodeai.kash.tools.git.plumbing
                        .decodeTag(parsed.payload)
                        .targetSha
            }

            else -> {
                return null
            }
        }
    }
    return null
}
