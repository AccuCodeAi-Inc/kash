package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.ansi.Ansi
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitEnv
import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexEntry
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.TreeEntry
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.encodeTree
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.parseFramedObject
import kotlin.coroutines.cancellation.CancellationException

/**
 * `git write-tree` — write the current index as a tree object,
 * recursively building subtrees, and print its 40-hex sha.
 *
 * Reuses [writeTreeFromIndex] (the same code path `git commit` uses) so
 * the sha is byte-identical to real git's for an identical index.
 */
public fun gitWriteTreeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "write-tree"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            // `--missing-ok` / `--prefix=<path>` are accepted-and-ignored
            // for the common arg-less form; anything else is an error.
            for (a in args) {
                if (a.startsWith("-")) {
                    ctx.stderr.writeUtf8("git write-tree: unsupported option '$a'\n")
                    return CommandResult(exitCode = 129)
                }
            }
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }
            val index = repo.readIndex()
            val sha = writeTreeFromIndex(repo, index)
            ctx.stdout.writeUtf8("$sha\n")
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git commit-tree <tree> [-p <parent>]... [-m <msg>] [-F <file>]` —
 * create a commit object pointing at `<tree>`, print its sha.
 *
 *  - `-p <parent>` may repeat; parents are recorded in argument order.
 *  - `-m <msg>` may repeat; each is a paragraph joined by a blank line.
 *  - `-F <file>` reads the message from a file (relative to cwd).
 *  - With neither `-m` nor `-F`, the message is read verbatim from stdin.
 *
 * Identity + dates follow the same fallback chain as `git commit`
 * (`GIT_AUTHOR_*`/`GIT_COMMITTER_*` env → config → adapter → default), so
 * pinning the env produces real git's exact sha.
 */
public fun gitCommitTreeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "commit-tree"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var tree: String? = null
            val parents = mutableListOf<String>()
            val messages = mutableListOf<String>()
            var fileMsg: String? = null
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    a == "-p" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"p\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        parents += args[i + 1]
                        i += 2
                    }

                    a == "-m" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"m\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        messages += args[i + 1]
                        i += 2
                    }

                    a == "-F" -> {
                        if (i + 1 >= args.size) {
                            ctx.stderr.writeUtf8("error: switch \"F\" requires a value\n")
                            return CommandResult(exitCode = 129)
                        }
                        fileMsg = args[i + 1]
                        i += 2
                    }

                    a == "--" -> {
                        i++
                    }

                    a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git commit-tree: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    tree == null -> {
                        tree = a
                        i++
                    }

                    else -> {
                        ctx.stderr.writeUtf8("git commit-tree: too many tree arguments\n")
                        return CommandResult(exitCode = 129)
                    }
                }
            }
            if (tree == null) {
                ctx.stderr.writeUtf8(
                    "usage: git commit-tree <tree> [(-p <parent>)...] [(-m <message>)...] [(-F <file>)...]\n",
                )
                return CommandResult(exitCode = 129)
            }

            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            val treeSha =
                resolveTreeArg(repo, tree) ?: run {
                    ctx.stderr.writeUtf8("fatal: not a valid object name $tree\n")
                    return CommandResult(exitCode = 128)
                }
            // Validate parents resolve to commits (real git rejects bad parents).
            val parentShas = mutableListOf<String>()
            for (p in parents) {
                val ps =
                    resolveObjectRef(repo, p) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name $p\n")
                        return CommandResult(exitCode = 128)
                    }
                parentShas += ps
            }

            // Message resolution: -F file, else joined -m paragraphs, else stdin.
            val message =
                when {
                    fileMsg != null -> {
                        val abs = absolutize(env.cwd, fileMsg)
                        val raw = ctx.fs.readBytes(abs).decodeToString()
                        // Real git appends a trailing LF if missing.
                        if (raw.endsWith("\n")) raw else "$raw\n"
                    }

                    messages.isNotEmpty() -> {
                        // Each -m paragraph gets a trailing LF; paragraphs are
                        // separated by a blank line.
                        messages.joinToString("\n") { "$it\n" }
                    }

                    else -> {
                        val raw = ctx.stdin.readAllBytes().decodeToString()
                        raw
                    }
                }

            val identity = plumbingIdentity(env, ctx.env, repo, ctx.fs)
            val author = personFor(ctx, identity.name, identity.email, ctx.env, committer = false)
            val committer = personFor(ctx, identity.name, identity.email, ctx.env, committer = true)

            val commit =
                CommitPayload(
                    tree = treeSha,
                    parents = parentShas,
                    author = author,
                    committer = committer,
                    message = message,
                )
            val sha = repo.objects.write(ObjectType.COMMIT, encodeCommit(commit))
            ctx.stdout.writeUtf8("$sha\n")
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git mktree` — read tree entries from stdin, write a tree object, print
 * its sha. Each input line is `<mode> SP <type> SP <sha> TAB <name>` (the
 * `git ls-tree` form). The `type` token is tolerated but ignored — the
 * mode determines whether an entry is a tree.
 */
public fun gitMktreeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "mktree"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var nullTerm = false
            for (a in args) {
                when (a) {
                    "-z" -> {
                        nullTerm = true
                    }

                    "--missing", "--batch" -> {}

                    // accepted, no-op here
                    else -> {
                        if (a.startsWith("-")) {
                            ctx.stderr.writeUtf8("git mktree: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        }
                    }
                }
            }
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }
            val text = ctx.stdin.readAllBytes().decodeToString()
            val lines = if (nullTerm) text.split(Ansi.NUL) else text.split('\n')
            val entries = mutableListOf<TreeEntry>()
            for (line in lines) {
                if (line.isEmpty()) continue
                // Split into "<meta>\t<name>".
                val tab = line.indexOf('\t')
                if (tab < 0) {
                    ctx.stderr.writeUtf8("fatal: input format error: $line\n")
                    return CommandResult(exitCode = 128)
                }
                val meta = line.substring(0, tab)
                val name = line.substring(tab + 1)
                // meta is "<mode> [<type>] <sha>"; tokens split on whitespace.
                val toks = meta.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (toks.size < 2) {
                    ctx.stderr.writeUtf8("fatal: input format error: $line\n")
                    return CommandResult(exitCode = 128)
                }
                val modeTok = toks[0]
                val sha = toks.last()
                val mode =
                    try {
                        FileMode.ofWire(modeTok.trimStart('0').ifEmpty { "0" })
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        // ls-tree zero-pads tree mode to 040000; normalize.
                        try {
                            FileMode.ofWire(modeTok)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            ctx.stderr.writeUtf8("fatal: invalid mode: $modeTok\n")
                            return CommandResult(exitCode = 128)
                        }
                    }
                entries += TreeEntry(mode, name, sha)
            }
            val sha = repo.objects.write(ObjectType.TREE, encodeTree(entries))
            ctx.stdout.writeUtf8("$sha\n")
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git update-index` — manipulate index entries.
 *
 *  - `--add` → stage new/modified files (the file must exist on disk
 *    unless `--remove` is also given).
 *  - `--remove` → entries whose file is gone are dropped; entries whose
 *    file still exists are refreshed.
 *  - `--cacheinfo <mode>,<sha>,<path>` (or `<mode> <sha> <path>`) →
 *    insert an entry by explicit mode/sha/path without touching the
 *    worktree.
 *  - `--refresh` → re-stat tracked files (a no-op for our 0-stat indices,
 *    but accepted so scripts succeed).
 */
public fun gitUpdateIndexSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "update-index"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var add = false
            var remove = false
            var endOpts = false
            val cacheinfos = mutableListOf<Triple<String, String, String>>()
            val paths = mutableListOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                when {
                    !endOpts && a == "--" -> {
                        endOpts = true
                        i++
                    }

                    !endOpts && a == "--add" -> {
                        add = true
                        i++
                    }

                    !endOpts && a == "--remove" -> {
                        remove = true
                        i++
                    }

                    !endOpts && a == "--refresh" -> {
                        // Stat refresh — our entries carry 0 stat fields, so
                        // there's nothing to update. Accept and continue.
                        i++
                    }

                    !endOpts && a == "--cacheinfo" -> {
                        // Two grammars: "--cacheinfo M,S,P" or
                        // "--cacheinfo M S P".
                        val next = args.getOrNull(i + 1)
                        if (next != null && next.contains(',')) {
                            val parts = next.split(',', limit = 3)
                            if (parts.size != 3) {
                                ctx.stderr.writeUtf8("fatal: git update-index: --cacheinfo cannot add $next\n")
                                return CommandResult(exitCode = 128)
                            }
                            cacheinfos += Triple(parts[0], parts[1], parts[2])
                            i += 2
                        } else {
                            if (i + 3 >= args.size) {
                                ctx.stderr.writeUtf8("usage: git update-index --cacheinfo <mode>,<sha1>,<path>\n")
                                return CommandResult(exitCode = 129)
                            }
                            cacheinfos += Triple(args[i + 1], args[i + 2], args[i + 3])
                            i += 4
                        }
                    }

                    !endOpts && a.startsWith("--cacheinfo=") -> {
                        val spec = a.removePrefix("--cacheinfo=")
                        val parts = spec.split(',', limit = 3)
                        if (parts.size != 3) {
                            ctx.stderr.writeUtf8("fatal: git update-index: --cacheinfo cannot add $spec\n")
                            return CommandResult(exitCode = 128)
                        }
                        cacheinfos += Triple(parts[0], parts[1], parts[2])
                        i++
                    }

                    !endOpts && a.startsWith("-") -> {
                        ctx.stderr.writeUtf8("git update-index: unsupported option '$a'\n")
                        return CommandResult(exitCode = 129)
                    }

                    else -> {
                        paths += a
                        i++
                    }
                }
            }

            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }

            // Build a mutable map keyed by path (stage 0 only — update-index
            // operates on the resolved index).
            val index = repo.readIndex()
            val byPath = LinkedHashMap<String, IndexEntry>()
            for (e in index.entries) byPath[e.path] = e

            // --cacheinfo entries: explicit mode/sha/path, no worktree touch.
            for ((modeTok, sha, path) in cacheinfos) {
                val mode =
                    try {
                        FileMode.ofWire(modeTok.trimStart('0').ifEmpty { "0" })
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        try {
                            FileMode.ofWire(modeTok)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Throwable) {
                            ctx.stderr.writeUtf8("fatal: git update-index: --cacheinfo cannot add $path\n")
                            return CommandResult(exitCode = 128)
                        }
                    }
                if (mode == FileMode.TREE) {
                    ctx.stderr.writeUtf8("fatal: git update-index: --cacheinfo cannot add $path\n")
                    return CommandResult(exitCode = 128)
                }
                if (sha.length != 40) {
                    ctx.stderr.writeUtf8("fatal: git update-index: --cacheinfo cannot add $path\n")
                    return CommandResult(exitCode = 128)
                }
                byPath[path] =
                    IndexEntry(
                        mode = mode,
                        size = 0,
                        sha = sha.lowercase(),
                        path = path,
                    )
            }

            // Plain path arguments.
            for (rel in paths) {
                val abs = absolutize(env.cwd, rel)
                val onDisk = ctx.fs.exists(abs)
                when {
                    !onDisk -> {
                        if (remove) {
                            byPath.remove(rel)
                        } else {
                            ctx.stderr.writeUtf8(
                                "error: $rel: does not exist and --remove not passed\n",
                            )
                            ctx.stderr.writeUtf8("fatal: Unable to process path $rel\n")
                            return CommandResult(exitCode = 128)
                        }
                    }

                    else -> {
                        // File exists: stage it if --add (new) or it's already
                        // tracked (update). A new path without --add is an error.
                        val tracked = byPath.containsKey(rel)
                        if (!tracked && !add) {
                            ctx.stderr.writeUtf8(
                                "error: $rel: cannot add to the index - missing --add option?\n",
                            )
                            ctx.stderr.writeUtf8("fatal: Unable to process path $rel\n")
                            return CommandResult(exitCode = 128)
                        }
                        val bytes = ctx.fs.readBytes(abs)
                        val blobSha = repo.objects.write(ObjectType.BLOB, bytes)
                        val mode = fileModeOf(ctx, abs)
                        byPath[rel] =
                            IndexEntry(
                                mode = mode,
                                size = bytes.size,
                                sha = blobSha,
                                path = rel,
                            )
                    }
                }
            }

            repo.writeIndex(IndexFile(version = 2, entries = byPath.values.toList()))
            return CommandResult(exitCode = 0)
        }
    }

/**
 * `git diff-tree [-r] [--root] [-p] [--name-only] [--name-status]
 * [--no-commit-id] [<tree-ish>] [<tree-ish>]`.
 *
 * Forms:
 *  - one commit arg → diff against its first parent (root commits emit
 *    nothing unless `--root`); a commit-id line precedes the changes.
 *  - one tree arg → undefined for real git; we treat a single tree like a
 *    commit-less diff against empty only with `--root`.
 *  - two tree-ish args → diff them directly, no commit-id line.
 *
 * Raw default output: `:<oldmode> <newmode> <oldsha> <newsha> <status>\t<path>`.
 * `-r` recurses into subtrees; without it, subtree changes collapse to a
 * single tree entry. `-p` prints the unified patch (and implies recursion).
 */
public fun gitDiffTreeSubcommand(): GitSubcommand =
    object : GitSubcommand {
        override val name: String = "diff-tree"

        override suspend fun run(
            args: List<String>,
            ctx: CommandContext,
            env: GitEnv,
        ): CommandResult {
            var recurse = false
            var root = false
            var patch = false
            var nameOnly = false
            var nameStatus = false
            var noCommitId = false
            val positional = mutableListOf<String>()
            for (a in args) {
                when (a) {
                    "-r" -> {
                        recurse = true
                    }

                    "--root" -> {
                        root = true
                    }

                    "-p", "-u", "--patch" -> {
                        patch = true
                        recurse = true
                    }

                    "--name-only" -> {
                        nameOnly = true
                    }

                    "--name-status" -> {
                        nameStatus = true
                    }

                    "--no-commit-id" -> {
                        noCommitId = true
                    }

                    "--" -> {}

                    else -> {
                        if (a.startsWith("-")) {
                            ctx.stderr.writeUtf8("git diff-tree: unsupported option '$a'\n")
                            return CommandResult(exitCode = 129)
                        }
                        positional += a
                    }
                }
            }
            val repo =
                GitRepo.openFromCwd(ctx.fs, env.cwd, env.resolver)
                    ?: run {
                        ctx.stderr.writeUtf8("fatal: not a git repository\n")
                        return CommandResult(exitCode = 128)
                    }
            if (positional.isEmpty()) {
                ctx.stderr.writeUtf8("usage: git diff-tree [-r] [--root] [-p] <tree-ish> [<tree-ish>]\n")
                return CommandResult(exitCode = 129)
            }

            val sb = StringBuilder()
            if (positional.size >= 2) {
                // Two tree-ish args: diff directly, never a commit-id line.
                val oldTree =
                    resolveTreeArg(repo, positional[0]) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name ${positional[0]}\n")
                        return CommandResult(exitCode = 128)
                    }
                val newTree =
                    resolveTreeArg(repo, positional[1]) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a valid object name ${positional[1]}\n")
                        return CommandResult(exitCode = 128)
                    }
                renderTreeDiff(repo, oldTree, newTree, recurse, patch, nameOnly, nameStatus, sb)
                ctx.stdout.writeUtf8(sb.toString())
                return CommandResult(exitCode = 0)
            }

            // One arg. Resolve as a commit; diff vs its first parent.
            val spec = positional[0]
            val objSha =
                resolveObjectRef(repo, spec) ?: run {
                    ctx.stderr.writeUtf8("fatal: not a valid object name $spec\n")
                    return CommandResult(exitCode = 128)
                }
            val parsed = parseFramedObject(repo.objects.read(objSha))
            if (parsed.type != ObjectType.COMMIT) {
                // A bare tree with no second arg: real git needs a pair; we
                // only diff vs empty when --root is asked for.
                val treeSha =
                    resolveTreeArg(repo, spec) ?: run {
                        ctx.stderr.writeUtf8("fatal: not a tree object $spec\n")
                        return CommandResult(exitCode = 128)
                    }
                if (root) {
                    renderTreeDiff(repo, null, treeSha, recurse, patch, nameOnly, nameStatus, sb)
                    ctx.stdout.writeUtf8(sb.toString())
                }
                return CommandResult(exitCode = 0)
            }

            val commit = repo.objects.readCommit(objSha)
            val parent = commit.parents.firstOrNull()
            if (parent == null && !root) {
                // Root commit without --root: real git emits nothing.
                return CommandResult(exitCode = 0)
            }
            val oldTree = parent?.let { repo.objects.readCommit(it).tree }
            if (!noCommitId) sb.append(objSha).append('\n')
            renderTreeDiff(repo, oldTree, commit.tree, recurse, patch, nameOnly, nameStatus, sb)
            ctx.stdout.writeUtf8(sb.toString())
            return CommandResult(exitCode = 0)
        }
    }

/** Resolve a tree-ish (commit/tag/tree/sha) to a tree sha. */
private suspend fun resolveTreeArg(
    repo: GitRepo,
    spec: String,
): String? {
    val sha = resolveObjectRef(repo, spec) ?: return null
    return when (parseFramedObject(repo.objects.read(sha)).type) {
        ObjectType.TREE -> {
            sha
        }

        ObjectType.COMMIT -> {
            repo.objects.readCommit(sha).tree
        }

        ObjectType.TAG -> {
            // Peel through the tag via the general revspec engine.
            val peeled = resolveRev(repo, "$spec^{tree}") ?: return null
            peeled
        }

        else -> {
            null
        }
    }
}

/** A node in a tree-vs-tree diff: name → (mode, sha). */
private data class DiffNode(
    val mode: FileMode,
    val sha: String,
)

/**
 * Render a diff between [oldTree] (null = empty) and [newTree] into [sb].
 * Honors recurse/patch/name-only/name-status. Raw entries are emitted in
 * git's path-sorted order.
 */
private suspend fun renderTreeDiff(
    repo: GitRepo,
    oldTree: String?,
    newTree: String,
    recurse: Boolean,
    patch: Boolean,
    nameOnly: Boolean,
    nameStatus: Boolean,
    sb: StringBuilder,
) {
    if (patch) {
        // Patch mode reuses the unified-diff renderer over fully-flattened
        // trees (always recursive).
        val oldSide = if (oldTree == null) emptyMap() else flattenTree(repo, oldTree)
        val newSide = flattenTree(repo, newTree)
        for (path in (oldSide.keys + newSide.keys).sorted()) {
            val l = oldSide[path]
            val r = newSide[path]
            val lBytes = l?.bytes ?: ByteArray(0)
            val rBytes = r?.bytes ?: ByteArray(0)
            if (l != null && r != null && lBytes.contentEquals(rBytes) && l.mode == r.mode) continue
            sb.append("diff --git a/$path b/$path\n")
            when {
                l == null && r != null -> {
                    sb.append("new file mode ${r.mode.wire}\n")
                    sb.append("index 0000000..${r.sha.substring(0, 7)}\n")
                }

                r == null && l != null -> {
                    sb.append("deleted file mode ${l.mode.wire}\n")
                    sb.append("index ${l.sha.substring(0, 7)}..0000000\n")
                }

                l != null && r != null -> {
                    if (l.mode != r.mode) {
                        sb.append("old mode ${l.mode.wire}\n")
                        sb.append("new mode ${r.mode.wire}\n")
                    }
                    sb.append("index ${l.sha.substring(0, 7)}..${r.sha.substring(0, 7)} ${r.mode.wire}\n")
                }

                else -> {}
            }
            val p =
                com.accucodeai.kash.tools.git.plumbing.unifiedDiff(
                    oldBytes = lBytes,
                    newBytes = rBytes,
                    oldLabel = if (l == null) "/dev/null" else "a/$path",
                    newLabel = if (r == null) "/dev/null" else "b/$path",
                )
            if (p.isNotEmpty()) sb.append(p)
        }
        return
    }

    // Raw / name(-status) modes: collapse subtrees unless recursing.
    val changes = mutableListOf<RawChange>()
    diffTreesRaw(repo, oldTree, newTree, "", recurse, changes)
    changes.sortBy { it.path }
    for (c in changes) {
        when {
            nameOnly -> {
                sb.append(c.path).append('\n')
            }

            nameStatus -> {
                sb
                    .append(c.status)
                    .append('\t')
                    .append(c.path)
                    .append('\n')
            }

            else -> {
                sb
                    .append(':')
                    .append(rawMode(c.oldMode))
                    .append(' ')
                    .append(rawMode(c.newMode))
                    .append(' ')
                    .append(c.oldSha)
                    .append(' ')
                    .append(c.newSha)
                    .append(' ')
                    .append(c.status)
                    .append('\t')
                    .append(c.path)
                    .append('\n')
            }
        }
    }
}

private const val ZERO_SHA = "0000000000000000000000000000000000000000"

private data class RawChange(
    val oldMode: FileMode?,
    val newMode: FileMode?,
    val oldSha: String,
    val newSha: String,
    val status: String,
    val path: String,
)

private fun rawMode(mode: FileMode?): String =
    when (mode) {
        null -> "000000"

        // Trees print 6-digit zero-padded in the raw format (040000).
        FileMode.TREE -> "040000"

        else -> mode.wire
    }

/**
 * Walk two trees in parallel collecting [RawChange]s. When [recurse] is
 * false, a changed/added/removed subtree is reported as a single `T`/`A`/`D`
 * entry; when true, the walk descends and reports leaf changes.
 */
private suspend fun diffTreesRaw(
    repo: GitRepo,
    oldTree: String?,
    newTree: String?,
    prefix: String,
    recurse: Boolean,
    out: MutableList<RawChange>,
) {
    val oldEntries = if (oldTree == null) emptyMap() else repo.objects.readTree(oldTree).associateBy { it.name }
    val newEntries = if (newTree == null) emptyMap() else repo.objects.readTree(newTree).associateBy { it.name }
    val names = (oldEntries.keys + newEntries.keys)
    for (name in names) {
        val o = oldEntries[name]
        val n = newEntries[name]
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        if (o != null && n != null && o.mode == n.mode && o.sha == n.sha) continue
        val oTree = o?.mode == FileMode.TREE
        val nTree = n?.mode == FileMode.TREE
        when {
            recurse && (oTree || nTree) -> {
                // Descend; treat a non-tree side as empty on that side.
                diffTreesRaw(
                    repo,
                    if (oTree) o.sha else null,
                    if (nTree) n.sha else null,
                    path,
                    recurse,
                    out,
                )
                // A file<->tree swap also yields the file side as a change.
                if (oTree && n != null && !nTree) {
                    out += RawChange(null, n.mode, ZERO_SHA, n.sha, "A", path)
                }
                if (nTree && o != null && !oTree) {
                    out += RawChange(o.mode, null, o.sha, ZERO_SHA, "D", path)
                }
            }

            else -> {
                val status =
                    when {
                        o == null -> "A"
                        n == null -> "D"
                        else -> "M"
                    }
                out +=
                    RawChange(
                        oldMode = o?.mode,
                        newMode = n?.mode,
                        oldSha = o?.sha ?: ZERO_SHA,
                        newSha = n?.sha ?: ZERO_SHA,
                        status = status,
                        path = path,
                    )
            }
        }
    }
}

private fun absolutize(
    cwd: String,
    path: String,
): String =
    when {
        path.startsWith("/") -> path
        cwd == "/" -> "/$path"
        else -> "${cwd.trimEnd('/')}/$path"
    }

private fun fileModeOf(
    ctx: CommandContext,
    abs: String,
): FileMode =
    try {
        val st = ctx.fs.stat(abs)
        if ((st.mode and 0b001_001_001) != 0) FileMode.EXECUTABLE else FileMode.REGULAR
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        FileMode.REGULAR
    }

/**
 * Build the author/committer [PersonStamp] honoring `GIT_AUTHOR_DATE` /
 * `GIT_COMMITTER_DATE` env (the same parsing `git commit` uses) so that
 * pinned tests reproduce real git's sha exactly.
 */
private fun personFor(
    ctx: CommandContext,
    name: String,
    email: String,
    shellEnv: Map<String, String>,
    committer: Boolean,
): PersonStamp {
    val dateKey = if (committer) "GIT_COMMITTER_DATE" else "GIT_AUTHOR_DATE"
    val fallbackKey = if (committer) "GIT_AUTHOR_DATE" else "GIT_COMMITTER_DATE"
    val dateStr = shellEnv[dateKey] ?: shellEnv[fallbackKey]
    val whenSec =
        dateStr
            ?.split(' ')
            ?.firstOrNull()
            ?.toLongOrNull()
            ?: ctx.process.machine.clock
                .now()
                .epochSeconds
    val tz =
        dateStr
            ?.split(' ')
            ?.getOrNull(1)
            ?.takeIf { it.length == 5 }
            ?: ctx.process.machine.clock
                .localTz()
    return PersonStamp(name, email, whenSec, tz)
}

/**
 * Identity-fallback chain mirroring `git commit`:
 *   GIT_AUTHOR_* / GIT_COMMITTER_* env -> repo `.git/config` [user] ->
 *   `$HOME/.gitconfig` [user] -> host adapter identity -> placeholder.
 */
private suspend fun plumbingIdentity(
    env: GitEnv,
    shellEnv: Map<String, String>,
    repo: GitRepo,
    fs: FileSystem,
): GitIdentity {
    val repoCfg = runCatching { readGitConfig(repo) }.getOrDefault(emptyMap())
    val userCfg =
        shellEnv["HOME"]?.let { home ->
            val path = "${home.trimEnd('/')}/.gitconfig"
            if (fs.exists(path)) parseGitConfigText(fs.readBytes(path).decodeToString()) else null
        } ?: emptyMap()

    fun cfg(key: String): String? = repoCfg["user"]?.get(key) ?: userCfg["user"]?.get(key)

    val name =
        shellEnv["GIT_AUTHOR_NAME"]
            ?: shellEnv["GIT_COMMITTER_NAME"]
            ?: cfg("name")
            ?: env.adapter?.identity?.name
            ?: "kash"
    val email =
        shellEnv["GIT_AUTHOR_EMAIL"]
            ?: shellEnv["GIT_COMMITTER_EMAIL"]
            ?: cfg("email")
            ?: env.adapter?.identity?.email
            ?: "kash@localhost"
    return GitIdentity(name, email)
}
