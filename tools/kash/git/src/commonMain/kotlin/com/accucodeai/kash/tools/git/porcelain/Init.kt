package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.RepoLayout

/**
 * `git init [-b <branch>] [<directory>]` — create an empty repo.
 *
 * Layout mirrors real git: HEAD, config, description, info/exclude,
 * hooks/, objects/{info,pack}, refs/{heads,tags}. We omit the
 * `*.sample` hooks — they're cosmetic and bloat the VFS.
 *
 * Re-init: if `.git/` already exists, we keep existing content and
 * just rewrite HEAD to the new initial branch when `-b` differs.
 * Output: `Reinitialized existing Git repository in <abs>/.git/`.
 */
public fun gitInitSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "init"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var initialBranch = "main"
            var target: String? = null
            var quiet = false

            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-b" || a == "--initial-branch" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"$a\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        initialBranch = args[i + 1]
                        i += 2
                    }

                    a.startsWith("--initial-branch=") -> {
                        initialBranch = a.substringAfter("=")
                        i++
                    }

                    a == "-q" || a == "--quiet" -> {
                        quiet = true
                        i++
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git init: unsupported flag '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        if (target != null) {
                            ctx.stderr.writeUtf8("git init: too many positional args\n")
                            return CommandResult(exitCode = 129)
                        }
                        target = a
                        i++
                    }
                }
            }

            val workTree =
                when (val t = target) {
                    null -> {
                        env.cwd
                    }

                    else -> {
                        if (t.startsWith("/")) {
                            t
                        } else if (env.cwd == "/") {
                            "/$t"
                        } else {
                            "${env.cwd}/$t"
                        }
                    }
                }
            // Collapse `.`/`..` so `git init .` from `/home/user/the`
            // prints `Initialized empty Git repository in /home/user/the/.git/`
            // instead of the embarrassing `/home/user/the/./.git/`.
            val normalized = Paths.normalize(workTree)
            val layout = RepoLayout(normalized)
            val fs = ctx.fs

            val isReinit = fs.exists(layout.gitDir) && fs.isDirectory(layout.gitDir)
            fs.mkdirs(layout.gitDir)
            fs.mkdirs(layout.objectsDir)
            fs.mkdirs("${layout.objectsDir}/info")
            fs.mkdirs(layout.packDir)
            fs.mkdirs(layout.refsDir)
            fs.mkdirs(layout.headsDir)
            fs.mkdirs(layout.tagsDir)
            fs.mkdirs(layout.hooksDir)
            fs.mkdirs(layout.infoDir)

            val refs = RefStore(layout, fs)
            // Real git's re-init warns about ignored `--initial-branch`
            // when the existing HEAD already points to a branch. We
            // honor the spirit: keep existing HEAD on re-init unless
            // the user explicitly passed -b to a fresh repo.
            if (!isReinit) {
                refs.writeHeadSymbolic("refs/heads/$initialBranch")
            } else if (args.contains("-b") || args.any { it.startsWith("--initial-branch") }) {
                ctx.stderr.writeUtf8("warning: re-init: ignored --initial-branch=$initialBranch\n")
            }

            if (!fs.exists(layout.configFile)) {
                fs.writeBytes(
                    layout.configFile,
                    buildString {
                        appendLine("[core]")
                        appendLine("\trepositoryformatversion = 0")
                        appendLine("\tfilemode = true")
                        appendLine("\tbare = false")
                        appendLine("\tlogallrefupdates = true")
                    }.encodeToByteArray(),
                )
            }
            if (!fs.exists(layout.descriptionFile)) {
                fs.writeBytes(
                    layout.descriptionFile,
                    "Unnamed repository; edit this file 'description' to name the repository.\n".encodeToByteArray(),
                )
            }
            if (!fs.exists(layout.excludeFile)) {
                fs.writeBytes(
                    layout.excludeFile,
                    "# git ls-files --others --exclude-from=.git/info/exclude\n".encodeToByteArray(),
                )
            }

            if (!quiet) {
                val prefix = if (isReinit) "Reinitialized existing" else "Initialized empty"
                ctx.stdout.writeUtf8("$prefix Git repository in ${layout.gitDir}/\n")
            }
            return CommandResult(exitCode = 0)
        }
    }

private suspend fun com.accucodeai.kash.fs.FileSystem.writeBytes(
    path: String,
    bytes: ByteArray,
) {
    writeBytes(path, bytes, mode = 0b110_110_110)
}
