package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Build a `git log --decorate` map: commit sha → the decoration string
 * git renders inside `(...)`. Mirrors observed real-git ordering:
 *  1. `HEAD` (bare when detached, `HEAD -> <branch>` when symbolic)
 *  2. tags (`tag: <name>`), reverse-alphabetical
 *  3. remote branches (`<remote>/<name>`), reverse-alphabetical
 *  4. local branches (`<name>`), reverse-alphabetical, minus the branch
 *     HEAD already points at
 *
 * [full] keeps the full `refs/...` ref paths (for `--decorate=full`);
 * the default short form strips `refs/heads/`, `refs/tags/`,
 * `refs/remotes/`.
 */
public suspend fun buildDecorations(
    repo: GitRepo,
    full: Boolean,
): Map<String, String> {
    val head = repo.refs.readHead()
    val headBranch =
        (head as? com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic)?.target
    val headSha = repo.refs.resolveHead()

    // Group refs by their target sha.
    data class Group(
        val tags: MutableList<String> = mutableListOf(),
        val remotes: MutableList<String> = mutableListOf(),
        val locals: MutableList<String> = mutableListOf(),
    )

    val bySha = mutableMapOf<String, Group>()

    fun group(sha: String) = bySha.getOrPut(sha) { Group() }

    for ((ref, sha) in repo.refs.listRefs("refs/heads")) {
        group(sha).locals += ref
    }
    for ((ref, sha) in repo.refs.listRefs("refs/tags")) {
        // Peel annotated tags to the commit they decorate.
        val peeled = dereferenceTagPublic(repo, sha)
        group(peeled).tags += ref
        if (peeled != sha) group(sha).tags += ref
    }
    for ((ref, sha) in repo.refs.listRefs("refs/remotes")) {
        group(sha).remotes += ref
    }

    fun shortName(ref: String): String =
        if (full) {
            ref
        } else {
            ref
                .removePrefix("refs/heads/")
                .removePrefix("refs/tags/")
                .removePrefix("refs/remotes/")
        }

    val out = mutableMapOf<String, String>()
    val allShas = bySha.keys + listOfNotNull(headSha)
    for (sha in allShas) {
        val g = bySha[sha]
        val parts = mutableListOf<String>()
        if (sha == headSha) {
            if (headBranch != null) {
                val b = if (full) headBranch else headBranch.removePrefix("refs/heads/")
                parts += "HEAD -> $b"
            } else {
                parts += "HEAD"
            }
        }
        if (g != null) {
            for (t in g.tags.sortedDescending()) parts += "tag: ${shortName(t)}"
            for (r in g.remotes.sortedDescending()) parts += shortName(r)
            for (l in g.locals.sortedDescending()) {
                if (l == headBranch) continue
                parts += shortName(l)
            }
        }
        if (parts.isNotEmpty()) out[sha] = parts.joinToString(", ")
    }
    return out
}

private suspend fun dereferenceTagPublic(
    repo: GitRepo,
    sha: String,
): String = dereferenceTag(repo, sha)

/**
 * Resolve a revision spec to a commit (or object) sha.
 *
 * Supported forms:
 *  - `HEAD` → follow `.git/HEAD`
 *  - `refs/heads/<name>` / `refs/tags/<name>` / `refs/remotes/<x>/<y>`
 *  - `<name>` (bare branch name) → tries `refs/heads/<name>` then
 *    `refs/tags/<name>` then `refs/remotes/<name>`
 *  - Full 40-char sha → returned as-is
 *  - Abbreviated sha (4-39 hex chars) → resolved against loose objects
 *    in the local store; ambiguous prefixes return null
 *  - `<rev>^[<N>]` → Nth parent (default 1)
 *  - `<rev>~<N>` → Nth first-parent ancestor
 *  - `<rev>^{tree}` → the tree of that commit
 *  - `<rev>@{upstream}` / `<rev>@{u}` → the upstream tracking ref
 *    (resolved via `branch.<rev>.remote` + `.merge` in `.git/config`;
 *    falls back to `refs/remotes/origin/<rev>` if no config entry)
 *  - `<rev>@{N}` → the Nth-prior reflog value (`0` = current)
 *
 * Not supported:
 *  - `<rev>@{<date>}` → reflog-by-timestamp (we don't index the reflog by date)
 *  - `:/​<text>` message search — niche, defer
 *
 * Returns null when the spec doesn't resolve. Callers decide whether
 * that's `fatal: ambiguous argument` or a graceful empty.
 */
public suspend fun resolveRev(
    repo: GitRepo,
    spec: String,
): String? {
    if (spec.isEmpty()) return null

    // Split a trailing suffix chain off the base: ^, ~N, ^{...}, @{...}.
    val suffixStart = findSuffixStart(spec)
    val base = spec.substring(0, suffixStart)
    val suffix = spec.substring(suffixStart)

    var sha = resolveBase(repo, base) ?: return null
    if (suffix.isEmpty()) return sha

    // Walk the suffix.
    var i = 0
    while (i < suffix.length) {
        when (suffix[i]) {
            '^' -> {
                // `^{tree}`, `^{commit}`, `^{}` peel forms.
                if (i + 1 < suffix.length && suffix[i + 1] == '{') {
                    val end = suffix.indexOf('}', i + 2)
                    if (end < 0) return null
                    val tag = suffix.substring(i + 2, end)
                    sha = peel(repo, sha, tag) ?: return null
                    i = end + 1
                    continue
                }
                // `^` or `^N` — Nth parent of commit.
                var j = i + 1
                while (j < suffix.length && suffix[j].isDigit()) j++
                val n = if (j == i + 1) 1 else suffix.substring(i + 1, j).toInt()
                if (n == 0) {
                    // `^0` peels to commit-as-commit (annotated tag → commit).
                    sha = peel(repo, sha, "commit") ?: return null
                } else {
                    val c = repo.objects.readCommit(sha)
                    if (n > c.parents.size) return null
                    sha = c.parents[n - 1]
                }
                i = j
            }

            '~' -> {
                var j = i + 1
                while (j < suffix.length && suffix[j].isDigit()) j++
                val n = if (j == i + 1) 1 else suffix.substring(i + 1, j).toInt()
                repeat(n) {
                    val c = repo.objects.readCommit(sha)
                    sha = c.parents.firstOrNull() ?: return null
                }
                i = j
            }

            '@' -> {
                // `@{upstream}` / `@{u}` — only form we support.
                if (i + 1 < suffix.length && suffix[i + 1] == '{') {
                    val end = suffix.indexOf('}', i + 2)
                    if (end < 0) return null
                    val tag = suffix.substring(i + 2, end)
                    when {
                        tag in setOf("upstream", "u", "push") -> {
                            sha = resolveUpstream(repo, base) ?: return null
                        }

                        tag.toIntOrNull() != null -> {
                            // `<ref>@{N}` — Nth-prior value from the
                            // reflog (0 = current; 1 = one move back).
                            val n = tag.toInt()
                            val ref =
                                when {
                                    base.isEmpty() || base == "HEAD" -> "HEAD"
                                    base.startsWith("refs/") -> base
                                    else -> "refs/heads/$base"
                                }
                            val entries = repo.reflog.read(ref)
                            if (n < 0 || n >= entries.size + 1) return null
                            // entries are chronological (oldest first);
                            // the "current" sha is the last entry's newSha.
                            // Walking back: index entries.size-1 is current,
                            // entries.size-1-n is N moves back. For n>0 we
                            // want the *old* value of the (size-n)th entry.
                            val target =
                                if (n == 0) {
                                    entries.lastOrNull()?.newSha
                                } else {
                                    entries.getOrNull(entries.size - n)?.oldSha
                                }
                            sha = target ?: return null
                        }

                        else -> {
                            return null
                        }
                    }
                    i = end + 1
                    continue
                }
                return null
            }

            else -> {
                return null
            }
        }
    }
    return sha
}

private fun findSuffixStart(spec: String): Int {
    // The base is everything up to the first ^, ~, or @{ that isn't part
    // of a refs/ path or other non-suffix usage. We keep it simple: scan
    // for the first ^/~ at any depth, and for @ only when followed by `{`.
    for (i in spec.indices) {
        val c = spec[i]
        if (c == '^' || c == '~') return i
        if (c == '@' && i + 1 < spec.length && spec[i + 1] == '{') return i
    }
    return spec.length
}

private suspend fun resolveBase(
    repo: GitRepo,
    base: String,
): String? {
    if (base.isEmpty() || base == "HEAD") return repo.refs.resolveHead()
    if (base.length == 40 && base.all(::isHex)) return base.lowercase()
    if (base.startsWith("refs/")) return repo.refs.resolve(base)?.let { dereferenceTag(repo, it) }
    repo.refs.resolve("refs/heads/$base")?.let { return dereferenceTag(repo, it) }
    repo.refs.resolve("refs/tags/$base")?.let { return dereferenceTag(repo, it) }
    repo.refs.resolve("refs/remotes/$base")?.let { return dereferenceTag(repo, it) }
    // Abbreviated SHA: 4-39 hex chars, must uniquely match a loose object.
    if (base.length in 4..39 && base.all(::isHex)) {
        return resolveAbbreviatedSha(repo, base.lowercase())
    }
    return null
}

private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/**
 * Walk through annotated-tag objects to the eventual non-tag target.
 * For `refs/tags/<lightweight>` (pointing straight at a commit) this is
 * a no-op; for `refs/tags/<annotated>` it follows `tag.object` until
 * we hit a commit (or run out, in which case we return what we have).
 */
private suspend fun dereferenceTag(
    repo: GitRepo,
    sha: String,
): String {
    var cur = sha
    repeat(8) {
        val parsed =
            try {
                com.accucodeai.kash.tools.git.plumbing
                    .parseFramedObject(repo.objects.read(cur))
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                return cur
            }
        if (parsed.type != com.accucodeai.kash.tools.git.plumbing.ObjectType.TAG) return cur
        cur =
            com.accucodeai.kash.tools.git.plumbing
                .decodeTag(parsed.payload)
                .targetSha
    }
    return cur
}

private fun resolveAbbreviatedSha(
    repo: GitRepo,
    abbrev: String,
): String? {
    val fs = repo.fs
    val objects = repo.layout.objectsDir
    if (!fs.exists(objects) || !fs.isDirectory(objects)) return null
    // Two cases: prefix is at least 2 chars (limits us to one subdir), or
    // shorter (must scan all subdirs — possible but rare).
    val matches = mutableListOf<String>()
    if (abbrev.length >= 2) {
        val dir = "$objects/${abbrev.substring(0, 2)}"
        if (!fs.exists(dir) || !fs.isDirectory(dir)) return null
        val rest = abbrev.substring(2)
        for (name in fs.list(dir)) {
            if (name.startsWith(rest)) {
                matches += abbrev.substring(0, 2) + name
                if (matches.size > 1) return null
            }
        }
    } else {
        for (sub in fs.list(objects)) {
            if (sub.length != 2 || !sub.all(::isHex)) continue
            if (!sub.startsWith(abbrev)) {
                if (abbrev.length == 1 && sub[0] != abbrev[0]) continue
            }
            val dir = "$objects/$sub"
            if (!fs.isDirectory(dir)) continue
            for (name in fs.list(dir)) {
                val full = sub + name
                if (full.startsWith(abbrev)) {
                    matches += full
                    if (matches.size > 1) return null
                }
            }
        }
    }
    return matches.singleOrNull()
}

/**
 * Peel [sha] toward [target] type. Supports:
 *  - `tree` → commit's tree (or recursively through tag → commit → tree)
 *  - `commit` → annotated-tag-target commit (or the sha itself if already
 *    a commit). For lightweight refs that just point at commits, no-op.
 *  - `""` (empty inside `^{}`) → fully de-reference annotated tags
 */
private suspend fun peel(
    repo: GitRepo,
    sha: String,
    target: String,
): String? {
    var cur = sha
    // Annotated tags are already de-referenced upstream by dereferenceTag
    // (via decodeTag) during base resolution, so by the time we get here
    // `cur` is normally a commit or tree. Best-effort: try reading as a
    // commit; if that works we have what we need for "commit". For "tree",
    // read the commit and grab its tree.
    return when (target) {
        "tree" -> {
            try {
                repo.objects.readCommit(cur).tree
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // Maybe it's already a tree.
                val p =
                    try {
                        repo.objects.readParsed(cur)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Throwable) {
                        return null
                    }
                if (p.type == ObjectType.TREE) cur else null
            }
        }

        "commit", "" -> {
            // Read and accept it only if it's a commit. Annotated tags were
            // already peeled by dereferenceTag during resolution, so there's
            // no need to re-decode tag.object here.
            val p =
                try {
                    repo.objects.readParsed(cur)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    return null
                }
            if (p.type == ObjectType.COMMIT) cur else null
        }

        else -> {
            null
        }
    }
}

/**
 * Resolve the upstream tracking ref for the local branch named [branch].
 * Reads `.git/config` for `[branch "<branch>"] remote = ...` and `merge`,
 * and resolves the corresponding `refs/remotes/<remote>/<branch>`. Falls
 * back to `refs/remotes/origin/<branch>` when no config entry is present.
 */
private suspend fun resolveUpstream(
    repo: GitRepo,
    branch: String,
): String? {
    val branchName =
        when {
            branch.isEmpty() || branch == "HEAD" -> {
                val head = repo.refs.readHead() ?: return null
                when (head) {
                    is com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Symbolic -> {
                        head.target.removePrefix("refs/heads/")
                    }

                    is com.accucodeai.kash.tools.git.plumbing.RefStore.Head.Detached -> {
                        return null
                    }
                }
            }

            else -> {
                branch.removePrefix("refs/heads/")
            }
        }

    val cfg = readGitConfig(repo)
    val section = "branch \"$branchName\""
    val remote = cfg[section]?.get("remote")
    val merge = cfg[section]?.get("merge")
    if (remote != null && merge != null) {
        val shortBranch = merge.removePrefix("refs/heads/")
        repo.refs.resolve("refs/remotes/$remote/$shortBranch")?.let { return it }
    }
    return repo.refs.resolve("refs/remotes/origin/$branchName")
}

/**
 * Minimal INI-style reader for `.git/config`. Returns
 * `Map<sectionName, Map<key, value>>`. Section names preserve the form
 * `branch "main"` (subsection in quotes); plain `[core]` becomes key
 * `"core"`. Quiet on parse errors — they're treated as "no entry."
 */
internal suspend fun readGitConfig(repo: GitRepo): Map<String, Map<String, String>> {
    val path = repo.layout.configFile
    if (!repo.fs.exists(path)) return emptyMap()
    return parseGitConfigText(repo.fs.readBytes(path).decodeToString())
}

/**
 * Same INI-shaped parse used by [readGitConfig], lifted out so callers
 * with the raw text (e.g. `$HOME/.gitconfig`, or `-c` override
 * collation) can reuse it without rooting an FS read at a `GitRepo`.
 */
internal fun parseGitConfigText(text: String): Map<String, Map<String, String>> {
    val out = mutableMapOf<String, MutableMap<String, String>>()
    var section: String? = null
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.substring(1, line.length - 1).trim()
            continue
        }
        if (section == null) continue
        val eq = line.indexOf('=')
        if (eq < 0) continue
        val key = line.substring(0, eq).trim()
        var value = line.substring(eq + 1).trim()
        if (value.startsWith("\"") && value.endsWith("\"") && value.length >= 2) {
            value = value.substring(1, value.length - 1)
        }
        out.getOrPut(section) { mutableMapOf() }[key] = value
    }
    return out
}
