package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.DiffStatEntry
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.diffStat
import com.accucodeai.kash.tools.git.plumbing.unifiedDiff

/**
 * A flat (path → blob bytes/sha/mode) view of a tree, used by the diff
 * helpers that compare two commits or a commit vs an empty side.
 */
internal class FlatEntry(
    val bytes: ByteArray,
    val sha: String,
    val mode: FileMode,
)

internal suspend fun flattenTree(
    repo: GitRepo,
    treeSha: String,
): Map<String, FlatEntry> {
    val out = mutableMapOf<String, FlatEntry>()
    walkInto(repo, treeSha, "", out)
    return out
}

private suspend fun walkInto(
    repo: GitRepo,
    treeSha: String,
    prefix: String,
    out: MutableMap<String, FlatEntry>,
) {
    for (e in repo.objects.readTree(treeSha)) {
        val p = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
        when (e.mode) {
            FileMode.TREE -> {
                walkInto(repo, e.sha, p, out)
            }

            else -> {
                val bytes = repo.objects.readBlob(e.sha)
                out[p] = FlatEntry(bytes, e.sha, e.mode)
            }
        }
    }
}

/**
 * Diff between [commitSha] and its first parent. Root commits diff
 * against an empty side. Filtered to [pathFilter] (each path must match
 * exactly or be under one of these directory prefixes); empty = no filter.
 *
 * Returns the rendered diff (header + unified patches) as a single string.
 */
public suspend fun commitDiffPatch(
    repo: GitRepo,
    commitSha: String,
    pathFilter: List<String> = emptyList(),
    contextLines: Int = 3,
): String {
    val commit = repo.objects.readCommit(commitSha)
    val newSide = flattenTree(repo, commit.tree)
    val oldSide =
        commit.parents.firstOrNull()?.let { p ->
            flattenTree(repo, repo.objects.readCommit(p).tree)
        } ?: emptyMap()

    val sb = StringBuilder()
    for (path in (oldSide.keys + newSide.keys).sorted()) {
        if (!matchesPath(path, pathFilter)) continue
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
        val patch =
            unifiedDiff(
                oldBytes = lBytes,
                newBytes = rBytes,
                oldLabel = if (l == null) "/dev/null" else "a/$path",
                newLabel = if (r == null) "/dev/null" else "b/$path",
                contextLines = contextLines,
            )
        if (patch.isNotEmpty()) sb.append(patch)
    }
    return sb.toString()
}

/** Per-file stat for [commitSha] vs first parent (or empty if root). */
public suspend fun commitDiffStat(
    repo: GitRepo,
    commitSha: String,
    pathFilter: List<String> = emptyList(),
): List<DiffStatEntry> {
    val commit = repo.objects.readCommit(commitSha)
    val newSide = flattenTree(repo, commit.tree)
    val oldSide =
        commit.parents.firstOrNull()?.let { p ->
            flattenTree(repo, repo.objects.readCommit(p).tree)
        } ?: emptyMap()

    val out = mutableListOf<DiffStatEntry>()
    for (path in (oldSide.keys + newSide.keys).sorted()) {
        if (!matchesPath(path, pathFilter)) continue
        val l = oldSide[path]
        val r = newSide[path]
        val lBytes = l?.bytes ?: ByteArray(0)
        val rBytes = r?.bytes ?: ByteArray(0)
        if (l != null && r != null && lBytes.contentEquals(rBytes) && l.mode == r.mode) continue
        out += diffStat(lBytes, rBytes, path)
    }
    return out
}

/** Did [commitSha] touch any path matching [pathFilter]? */
public suspend fun commitTouchesAnyOf(
    repo: GitRepo,
    commitSha: String,
    pathFilter: List<String>,
): Boolean {
    if (pathFilter.isEmpty()) return true
    val commit = repo.objects.readCommit(commitSha)
    val newSide = flattenTree(repo, commit.tree)
    val oldSide =
        commit.parents.firstOrNull()?.let { p ->
            flattenTree(repo, repo.objects.readCommit(p).tree)
        } ?: emptyMap()
    for (path in (oldSide.keys + newSide.keys)) {
        if (!matchesPath(path, pathFilter)) continue
        val l = oldSide[path]
        val r = newSide[path]
        if (l == null || r == null) return true
        if (!l.bytes.contentEquals(r.bytes)) return true
        if (l.mode != r.mode) return true
    }
    return false
}

internal fun matchesPath(
    path: String,
    filter: List<String>,
): Boolean {
    if (filter.isEmpty()) return true
    for (f in filter) {
        val n = f.trimEnd('/')
        if (path == n) return true
        if (path.startsWith("$n/")) return true
    }
    return false
}
