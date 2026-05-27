package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo

/**
 * `git rev-list` — list commit shas in topological-ish first-parent
 * order. v1 flags:
 *  - `<rev>` — start commit (HEAD if omitted)
 *  - `<from>..<to>` — commits reachable from `to` but not from `from`
 *  - `^<rev>` — exclude commits reachable from `<rev>`
 *  - `--max-count=<N>` / `-n <N>` — limit
 *  - `--reverse` — emit oldest-first
 *  - `--count` — emit a single integer count instead of shas
 *
 * Multi-parent walks are followed (we visit all parents BFS-style),
 * but the output order is by the first-parent backbone for determinism.
 */
public fun gitRevListSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "rev-list"

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

            var maxCount = Int.MAX_VALUE
            var reverse = false
            var countOnly = false
            val includes = mutableListOf<String>()
            val excludes = mutableListOf<String>()

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-n" || a == "--max-count" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        maxCount = args[i + 1].toInt()
                        i += 2
                    }

                    a.startsWith("--max-count=") -> {
                        maxCount = a.substringAfter("=").toInt()
                        i++
                    }

                    // `-n42` and `-42` bare-count shorthands, both synonyms
                    // for `-n 42`. Real git accepts both.
                    a.length > 2 && a.startsWith("-n") && a.substring(2).all(Char::isDigit) -> {
                        maxCount = a.substring(2).toInt()
                        i++
                    }

                    a.length > 1 && a[0] == '-' && a.substring(1).all(Char::isDigit) -> {
                        maxCount = a.substring(1).toInt()
                        i++
                    }

                    a == "--reverse" -> {
                        reverse = true
                        i++
                    }

                    a == "--count" -> {
                        countOnly = true
                        i++
                    }

                    a == "--all" -> {
                        // Walk every branch tip.
                        for ((_, sha) in repo.refs.listRefs("refs/heads")) {
                            includes += sha
                        }
                        i++
                    }

                    a.startsWith("^") -> {
                        excludes += a.substring(1)
                        i++
                    }

                    a.contains("..") -> {
                        val (lo, hi) = a.split("..", limit = 2)
                        if (lo.isNotEmpty()) excludes += lo
                        if (hi.isNotEmpty()) includes += hi
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git rev-list: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        includes += a
                        i++
                    }
                }
            }

            if (includes.isEmpty()) {
                val head =
                    repo.refs.resolveHead() ?: run {
                        ctx.stderr.writeUtf8("fatal: no commits yet\n")
                        return CommandResult(exitCode = 128)
                    }
                includes += head
            }

            val excludeShas = mutableSetOf<String>()
            for (e in excludes) {
                val s = resolveRev(repo, e) ?: continue
                collectAncestors(repo, s, excludeShas)
            }

            val visited = mutableSetOf<String>()
            val ordered = mutableListOf<String>()
            for (start in includes) {
                val startSha =
                    resolveRev(repo, start) ?: run {
                        ctx.stderr.writeUtf8("fatal: ambiguous argument '$start'\n")
                        return CommandResult(exitCode = 128)
                    }
                walkDfs(repo, startSha, excludeShas, visited, ordered)
            }

            val limited = if (maxCount != Int.MAX_VALUE) ordered.take(maxCount) else ordered
            val final = if (reverse) limited.reversed() else limited
            if (countOnly) {
                ctx.stdout.writeUtf8("${final.size}\n")
            } else {
                for (s in final) ctx.stdout.writeUtf8("$s\n")
            }
            return CommandResult(exitCode = 0)
        }
    }

private suspend fun walkDfs(
    repo: GitRepo,
    start: String,
    excludes: Set<String>,
    visited: MutableSet<String>,
    out: MutableList<String>,
) {
    if (start in excludes || start in visited) return
    val stack = ArrayDeque<String>()
    stack.addLast(start)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (cur in visited || cur in excludes) continue
        visited += cur
        out += cur
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (_: Throwable) {
                continue
            }
        // Push parents in reverse so first-parent is processed first.
        for (p in c.parents.asReversed()) {
            if (p !in visited && p !in excludes) stack.addLast(p)
        }
    }
}

private suspend fun collectAncestors(
    repo: GitRepo,
    start: String,
    into: MutableSet<String>,
) {
    if (start in into) return
    val stack = ArrayDeque<String>()
    stack.addLast(start)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (!into.add(cur)) continue
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (_: Throwable) {
                continue
            }
        for (p in c.parents) {
            if (p !in into) stack.addLast(p)
        }
    }
}
