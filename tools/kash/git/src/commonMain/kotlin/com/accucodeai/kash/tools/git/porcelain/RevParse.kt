package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.RefStore

/**
 * `git rev-parse` — resolve revspecs and surface repo metadata. v1
 * supports: `HEAD`, branch/tag names, full SHAs, `--short[=N]`,
 * `--abbrev-ref`, `--show-toplevel`, `--git-dir`, `--verify`,
 * `--is-inside-work-tree`.
 */
public fun gitRevParseSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "rev-parse"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8(
                            "fatal: not a git repository (or any of the parent directories): .git\n",
                        )
                        return CommandResult(exitCode = 128)
                    }

            var short: Int? = null
            var abbrevRef = false
            var verify = false
            val args2 = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "--short" -> {
                        short = 7
                        i++
                    }

                    a.startsWith("--short=") -> {
                        short = a.substringAfter("=").toInt()
                        i++
                    }

                    a == "--abbrev-ref" -> {
                        abbrevRef = true
                        i++
                    }

                    a == "--verify" -> {
                        verify = true
                        i++
                    }

                    a == "--show-toplevel" -> {
                        ctx.stdout.writeUtf8(repo.layout.workTree + "\n")
                        i++
                    }

                    a == "--git-dir" -> {
                        ctx.stdout.writeUtf8(repo.layout.gitDir + "\n")
                        i++
                    }

                    a == "--is-inside-work-tree" -> {
                        ctx.stdout.writeUtf8("true\n")
                        i++
                    }

                    a == "--is-bare-repository" -> {
                        ctx.stdout.writeUtf8("false\n")
                        i++
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git rev-parse: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        args2 += a
                        i++
                    }
                }
            }

            for (spec in args2) {
                val resolved = resolveOrAbbrev(repo, spec, abbrevRef)
                if (resolved == null) {
                    if (verify || abbrevRef) {
                        ctx.stderr.writeUtf8("fatal: ambiguous argument '$spec': unknown revision\n")
                        return CommandResult(exitCode = 128)
                    } else {
                        // Mirror real-git: bare spec that doesn't
                        // resolve gets echoed back as-is. (Used by
                        // scripts that want to test "is this a sha?".)
                        ctx.stdout.writeUtf8("$spec\n")
                        continue
                    }
                }
                val s = short
                val out =
                    if (s != null && !abbrevRef && resolved.length == 40) {
                        resolved.substring(0, s.coerceIn(4, 40))
                    } else {
                        resolved
                    }
                ctx.stdout.writeUtf8(out + "\n")
            }
            return CommandResult(exitCode = 0)
        }

        private suspend fun resolveOrAbbrev(
            repo: GitRepo,
            spec: String,
            abbrevRef: Boolean,
        ): String? {
            if (abbrevRef) {
                // --abbrev-ref HEAD → branch name (or sha if detached).
                if (spec == "HEAD") {
                    return when (val h = repo.refs.readHead()) {
                        null -> {
                            null
                        }

                        is RefStore.Head.Detached -> {
                            h.sha
                        }

                        is RefStore.Head.Symbolic -> {
                            // refs/heads/main → main
                            h.target
                                .substringAfter("refs/heads/")
                                .ifEmpty { h.target }
                        }
                    }
                }
                // For named refs: strip the conventional prefix.
                return when {
                    spec.startsWith("refs/heads/") -> spec.removePrefix("refs/heads/")
                    spec.startsWith("refs/tags/") -> spec.removePrefix("refs/tags/")
                    spec.startsWith("refs/remotes/") -> spec.removePrefix("refs/remotes/")
                    else -> resolveRev(repo, spec)
                }
            }
            return resolveRev(repo, spec)
        }
    }
