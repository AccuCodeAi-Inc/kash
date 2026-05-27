package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo

/**
 * `git remote` — manage named remotes stored as `[remote "<name>"]`
 * sections in `.git/config`.
 *
 * v1 forms:
 *  - `git remote`             → list names, one per line
 *  - `git remote -v`          → list with `<name>\t<url> (fetch)` + `(push)`
 *  - `git remote add <name> <url>`
 *  - `git remote remove|rm <name>`
 *  - `git remote set-url <name> <url>`
 *  - `git remote get-url <name>`
 */
public fun gitRemoteSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "remote"

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
            val cfgPath = repo.layout.configFile
            val store = ConfigStore.load(ctx.fs, cfgPath)

            // Parse [-v] and subcommand.
            var verbose = false
            val rest = mutableListOf<String>()
            for (a in args) {
                if (a == "-v" || a == "--verbose") {
                    verbose = true
                } else {
                    rest += a
                }
            }

            if (rest.isEmpty()) {
                val names = remoteNames(store)
                if (verbose) {
                    for (n in names) {
                        val url = store.get("remote \"$n\"", "url") ?: ""
                        ctx.stdout.writeUtf8("$n\t$url (fetch)\n")
                        ctx.stdout.writeUtf8("$n\t$url (push)\n")
                    }
                } else {
                    for (n in names) ctx.stdout.writeUtf8("$n\n")
                }
                return CommandResult(exitCode = 0)
            }

            return when (rest[0]) {
                "add" -> {
                    if (rest.size != 3) {
                        ctx.stderr.writeUtf8("usage: git remote add <name> <url>\n")
                        return CommandResult(exitCode = 129)
                    }
                    val name = rest[1]
                    val url = rest[2]
                    val section = "remote \"$name\""
                    if (store.get(section, "url") != null) {
                        ctx.stderr.writeUtf8("error: remote $name already exists.\n")
                        return CommandResult(exitCode = 3)
                    }
                    store.set(section, "url", url)
                    store.set(section, "fetch", "+refs/heads/*:refs/remotes/$name/*")
                    store.save(ctx.fs, cfgPath)
                    CommandResult(exitCode = 0)
                }

                "remove", "rm" -> {
                    if (rest.size != 2) {
                        ctx.stderr.writeUtf8("usage: git remote remove <name>\n")
                        return CommandResult(exitCode = 129)
                    }
                    val name = rest[1]
                    val section = "remote \"$name\""
                    if (store.get(section, "url") == null) {
                        ctx.stderr.writeUtf8("error: No such remote: '$name'\n")
                        return CommandResult(exitCode = 2)
                    }
                    store.unset(section, "url")
                    store.unset(section, "fetch")
                    store.save(ctx.fs, cfgPath)
                    CommandResult(exitCode = 0)
                }

                "set-url" -> {
                    if (rest.size != 3) {
                        ctx.stderr.writeUtf8("usage: git remote set-url <name> <url>\n")
                        return CommandResult(exitCode = 129)
                    }
                    val name = rest[1]
                    val url = rest[2]
                    val section = "remote \"$name\""
                    if (store.get(section, "url") == null) {
                        ctx.stderr.writeUtf8("error: No such remote '$name'\n")
                        return CommandResult(exitCode = 2)
                    }
                    store.set(section, "url", url)
                    store.save(ctx.fs, cfgPath)
                    CommandResult(exitCode = 0)
                }

                "get-url" -> {
                    if (rest.size != 2) {
                        ctx.stderr.writeUtf8("usage: git remote get-url <name>\n")
                        return CommandResult(exitCode = 129)
                    }
                    val url =
                        store.get("remote \"${rest[1]}\"", "url") ?: run {
                            ctx.stderr.writeUtf8("error: No such remote '${rest[1]}'\n")
                            return CommandResult(exitCode = 2)
                        }
                    ctx.stdout.writeUtf8("$url\n")
                    CommandResult(exitCode = 0)
                }

                "show" -> {
                    if (rest.size != 2) {
                        ctx.stderr.writeUtf8("usage: git remote show <name>\n")
                        return CommandResult(exitCode = 129)
                    }
                    val name = rest[1]
                    val url =
                        store.get("remote \"$name\"", "url") ?: run {
                            ctx.stderr.writeUtf8("error: No such remote '$name'\n")
                            return CommandResult(exitCode = 2)
                        }
                    ctx.stdout.writeUtf8("* remote $name\n")
                    ctx.stdout.writeUtf8("  Fetch URL: $url\n")
                    ctx.stdout.writeUtf8("  Push  URL: $url\n")
                    CommandResult(exitCode = 0)
                }

                else -> {
                    ctx.stderr.writeUtf8("git remote: unknown subcommand '${rest[0]}'\n")
                    CommandResult(exitCode = 1)
                }
            }
        }
    }

private fun remoteNames(store: ConfigStore): List<String> {
    val out = mutableListOf<String>()
    for ((sec, _) in store.sections()) {
        if (sec.startsWith("remote \"") && sec.endsWith("\"")) {
            out += sec.substring("remote \"".length, sec.length - 1)
        }
    }
    return out.sorted()
}
