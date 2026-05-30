package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git cat-file` — query loose objects.
 *
 *  - `-t <obj>` → print type token (`blob`/`tree`/`commit`/`tag`)
 *  - `-s <obj>` → print payload byte size
 *  - `-e <obj>` → exit 0 if object exists; nonzero otherwise (no output)
 *  - `-p <obj>` → pretty-print:
 *      * blob → raw bytes
 *      * tree → `<mode> <type> <sha>\t<name>` lines
 *      * commit/tag → the framed payload (header + blank line + message)
 *
 * Revspec-aware: `-p HEAD`, `-p HEAD^`, `-p abc123` all work.
 */
public fun gitCatFileSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "cat-file"

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
            if (args.size < 2) {
                ctx.stderr.writeUtf8("usage: git cat-file (-t|-s|-e|-p) <object>\n")
                return CommandResult(exitCode = 129)
            }
            val flag = args[0]
            val ref = args[1]
            // cat-file inspects the literal object — must NOT peel
            // through annotated tags (real git: cat-file -t v1.0 → "tag",
            // not "commit").
            val sha =
                resolveObjectRef(repo, ref) ?: run {
                    ctx.stderr.writeUtf8("fatal: Not a valid object name $ref\n")
                    return CommandResult(exitCode = 128)
                }
            val raw =
                try {
                    repo.objects.read(sha)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    ctx.stderr.writeUtf8("fatal: git cat-file: could not read $sha\n")
                    return CommandResult(exitCode = 128)
                }
            val obj = parseFramedObject(raw)
            return when (flag) {
                "-t" -> {
                    ctx.stdout.writeUtf8("${obj.type.token}\n")
                    CommandResult(exitCode = 0)
                }

                "-s" -> {
                    ctx.stdout.writeUtf8("${obj.payload.size}\n")
                    CommandResult(exitCode = 0)
                }

                "-e" -> {
                    CommandResult(exitCode = 0)
                }

                "-p" -> {
                    when (obj.type) {
                        ObjectType.BLOB -> {
                            ctx.stdout.writeUtf8(obj.payload.decodeToString())
                        }

                        ObjectType.COMMIT, ObjectType.TAG -> {
                            ctx.stdout.writeUtf8(obj.payload.decodeToString())
                        }

                        ObjectType.TREE -> {
                            for (e in repo.objects.readTree(sha)) {
                                val typeTok = if (e.mode == FileMode.TREE) "tree" else "blob"
                                ctx.stdout.writeUtf8("${e.mode.wire} $typeTok ${e.sha}\t${e.name}\n")
                            }
                        }
                    }
                    CommandResult(exitCode = 0)
                }

                else -> {
                    ctx.stderr.writeUtf8("git cat-file: unknown flag '$flag'\n")
                    CommandResult(exitCode = 129)
                }
            }
        }
    }

/**
 * `git hash-object` — compute the sha a blob (or other object) would have,
 * optionally writing it to the object store.
 *
 *  - `<path>...` → hash file content
 *  - `--stdin` → hash bytes from stdin
 *  - `-w` → also write the framed object as loose
 *  - `-t <type>` → object type (default `blob`)
 */
public fun gitHashObjectSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "hash-object"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var write = false
            var type = ObjectType.BLOB
            var stdin = false
            val paths = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-w" -> {
                        write = true
                        i++
                    }

                    a == "--stdin" -> {
                        stdin = true
                        i++
                    }

                    a == "-t" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"-t\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        type = ObjectType.ofToken(args[i + 1])
                        i += 2
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git hash-object: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        paths += a
                        i++
                    }
                }
            }
            if (!stdin && paths.isEmpty()) {
                ctx.stderr.writeUtf8("usage: git hash-object [-w] [-t <type>] (--stdin | <file>...)\n")
                return CommandResult(exitCode = 129)
            }

            val repo =
                if (write) {
                    GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }
                } else {
                    null
                }

            suspend fun process(bytes: ByteArray) {
                val framed = framedObject(type, bytes)
                val sha =
                    if (repo != null) {
                        repo.objects.writeFramed(framed)
                    } else {
                        com.accucodeai.kash.hash
                            .sha1Hex(framed)
                    }
                ctx.stdout.writeUtf8("$sha\n")
            }

            if (stdin) {
                process(ctx.stdin.readAllBytes())
            }
            for (p in paths) {
                val abs =
                    if (p.startsWith("/")) {
                        p
                    } else if (env.cwd == "/") {
                        "/$p"
                    } else {
                        "${env.cwd}/$p"
                    }
                process(ctx.fs.readBytes(abs))
            }
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git ls-tree` — list entries of a tree object.
 *
 *  - `<tree-ish>` → resolve to a tree (commits/tags are peeled)
 *  - `-r` → recurse into subtrees (default emits subtree refs only)
 *  - `--name-only` → just the trailing path, no mode/type/sha
 *  - `-z` → NUL terminator instead of LF (only meaningful with --name-only)
 */
public fun gitLsTreeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "ls-tree"

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
            var recurse = false
            var nameOnly = false
            var nullTerm = false
            var treeish: String? = null
            val paths = mutableListOf<String>()
            for (a in args) {
                when {
                    a == "-r" -> {
                        recurse = true
                    }

                    a == "--name-only" -> {
                        nameOnly = true
                    }

                    a == "-z" -> {
                        nullTerm = true
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git ls-tree: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    treeish == null -> {
                        treeish = a
                    }

                    else -> {
                        paths += a
                    }
                }
            }
            if (treeish == null) {
                ctx.stderr.writeUtf8("usage: git ls-tree [-r] [--name-only] [-z] <tree-ish> [<path>...]\n")
                return CommandResult(exitCode = 129)
            }
            val treeSha =
                peelToTree(repo, treeish) ?: run {
                    ctx.stderr.writeUtf8("fatal: Not a valid object name $treeish\n")
                    return CommandResult(exitCode = 128)
                }
            val term = if (nullTerm) Ansi.NUL else "\n"
            emitTree(repo, treeSha, "", recurse, nameOnly, paths, term, ctx)
            return CommandResult(exitCode = 0)
        }
    }

/**
 * Resolve [ref] to an object sha *without* peeling annotated tags.
 * Used by cat-file (where the caller wants the literal object). Falls
 * back to a general resolveRev for forms with explicit suffixes.
 */
internal suspend fun resolveObjectRef(
    repo: GitRepo,
    ref: String,
): String? {
    if (ref == "HEAD") return repo.refs.resolveHead()
    if (ref.length == 40 && ref.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        return ref.lowercase()
    }
    if (ref.startsWith("refs/")) return repo.refs.resolve(ref)
    repo.refs.resolve("refs/heads/$ref")?.let { return it }
    repo.refs.resolve("refs/tags/$ref")?.let { return it }
    repo.refs.resolve("refs/remotes/$ref")?.let { return it }
    // Fall back to revspec resolution for suffix forms (^{tree}, ~N, etc.).
    return resolveRev(repo, ref)
}

private suspend fun peelToTree(
    repo: GitRepo,
    ref: String,
): String? {
    val sha = resolveRev(repo, ref) ?: return null
    val raw =
        try {
            repo.objects.read(sha)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return null
        }
    return when (parseFramedObject(raw).type) {
        ObjectType.TREE -> sha
        ObjectType.COMMIT -> repo.objects.readCommit(sha).tree
        else -> null
    }
}

private suspend fun emitTree(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    recurse: Boolean,
    nameOnly: Boolean,
    pathFilter: List<String>,
    term: String,
    ctx: CommandContext,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val full = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        if (pathFilter.isNotEmpty() && !pathFilter.any { matchesPathOrAncestor(full, it) }) continue
        if (e.mode == FileMode.TREE && recurse) {
            emitTree(repo, e.sha, full, recurse, nameOnly, pathFilter, term, ctx)
            continue
        }
        if (nameOnly) {
            ctx.stdout.writeUtf8("$full$term")
        } else {
            val typeTok = if (e.mode == FileMode.TREE) "tree" else "blob"
            ctx.stdout.writeUtf8("${e.mode.wire} $typeTok ${e.sha}\t$full$term")
        }
    }
}

private fun matchesPathOrAncestor(
    path: String,
    filter: String,
): Boolean {
    val n = filter.trimEnd('/')
    if (path == n) return true
    if (path.startsWith("$n/")) return true
    if (n.startsWith("$path/")) return true // descend into matching subtree
    return false
}
