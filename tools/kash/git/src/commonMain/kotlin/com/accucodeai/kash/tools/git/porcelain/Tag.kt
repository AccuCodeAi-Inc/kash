package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.TagPayload
import com.accucodeai.kash.tools.git.plumbing.encodeTag
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject

/**
 * `git tag` — lightweight + annotated tags under `refs/tags/`.
 *
 *  - no args / `-l`: list tags.
 *  - `<name> [<rev>]`: lightweight tag at HEAD or [rev].
 *  - `-a <name> -m <msg>`: annotated tag (writes a tag object).
 *  - `-d <name>`: delete tag.
 *
 * v1 ships lightweight only; `-a` falls back to lightweight with a
 * stderr note. (Annotated tag objects land with the same machinery as
 * commits — adding them is straightforward later.)
 */
public fun gitTagSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "tag"

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

            var delete = false
            var annotated = false
            var message: String? = null
            val positional = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-l" || a == "--list" -> {
                        i++
                    }

                    a == "-d" || a == "--delete" -> {
                        delete = true
                        i++
                    }

                    a == "-a" || a == "--annotate" -> {
                        annotated = true
                        i++
                    }

                    a == "-m" || a == "--message" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        message = if (message == null) args[i + 1] else "$message\n\n${args[i + 1]}"
                        // -m implies -a (matches real git).
                        annotated = true
                        i += 2
                    }

                    a.startsWith("--message=") -> {
                        val v = a.substringAfter("=")
                        message = if (message == null) v else "$message\n\n$v"
                        annotated = true
                        i++
                    }

                    // accepted, ignored in v1
                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git tag: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        positional += a
                        i++
                    }
                }
            }

            if (delete) {
                if (positional.isEmpty()) {
                    ctx.stderr.writeUtf8("git tag: tag name required\n")
                    return CommandResult(exitCode = 129)
                }
                for (name in positional) {
                    val ref = "refs/tags/$name"
                    val path = repo.layout.refFile(ref)
                    if (!ctx.fs.exists(path)) {
                        ctx.stderr.writeUtf8("error: tag '$name' not found.\n")
                        return CommandResult(exitCode = 1)
                    }
                    ctx.fs.remove(path)
                    ctx.stdout.writeUtf8("Deleted tag '$name'\n")
                }
                return CommandResult(exitCode = 0)
            }

            if (positional.isNotEmpty()) {
                val name = positional[0]
                val spec = positional.getOrNull(1) ?: "HEAD"
                val target =
                    resolveRev(repo, spec) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name: '$spec'\n")
                        return CommandResult(exitCode = 128)
                    }
                if (annotated) {
                    val targetType =
                        try {
                            parseFramedObject(repo.objects.read(target)).type
                        } catch (_: Throwable) {
                            ObjectType.COMMIT
                        }
                    val msg =
                        message ?: run {
                            ctx.stderr.writeUtf8("error: -a requires -m <msg> in v1 (no editor)\n")
                            return CommandResult(exitCode = 1)
                        }
                    val nowSec = nowReflogTime(ctx)
                    val tagger =
                        PersonStamp(
                            name = ctx.env["GIT_AUTHOR_NAME"] ?: env.adapter?.identity?.name ?: "kash",
                            email = ctx.env["GIT_AUTHOR_EMAIL"] ?: env.adapter?.identity?.email ?: "kash@localhost",
                            whenSeconds = nowSec.first,
                            tz = nowSec.second,
                        )
                    val tagPayload =
                        TagPayload(
                            targetSha = target,
                            targetType = targetType,
                            tagName = name,
                            tagger = tagger,
                            message = if (msg.endsWith("\n")) msg else "$msg\n",
                        )
                    val tagSha = repo.objects.write(ObjectType.TAG, encodeTag(tagPayload))
                    repo.refs.writeRef("refs/tags/$name", tagSha)
                    return CommandResult(exitCode = 0)
                }
                repo.refs.writeRef("refs/tags/$name", target)
                return CommandResult(exitCode = 0)
            }

            val tags =
                repo.refs
                    .listRefs("refs/tags")
                    .map { it.first.removePrefix("refs/tags/") }
                    .sorted()
            for (t in tags) ctx.stdout.writeUtf8("$t\n")
            return CommandResult(exitCode = 0)
        }
    }
