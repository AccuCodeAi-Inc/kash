package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.tools.git.GitRepo

/**
 * Find the merge base of two commits — the most recent commit that's
 * an ancestor of both. v1 implements the standard BFS-from-both-sides
 * algorithm:
 *  1. Walk ancestors from [a], marking every visited sha.
 *  2. Walk ancestors from [b]; the first sha seen that's already marked
 *     is the merge base.
 *
 * Returns null if the commits share no common ancestor (independent
 * histories). For criss-cross merges (where multiple equally-good
 * bases exist) we return the first one found, which matches real
 * git's `merge.directoryRenames=false` default well enough for v1.
 */
public suspend fun mergeBase(
    repo: GitRepo,
    a: String,
    b: String,
): String? {
    if (a == b) return a
    val ancestorsA = mutableSetOf<String>()
    val queueA = ArrayDeque<String>()
    queueA.add(a)
    while (queueA.isNotEmpty()) {
        val cur = queueA.removeFirst()
        if (!ancestorsA.add(cur)) continue
        val commit = repo.objects.readCommit(cur)
        commit.parents.forEach { queueA.add(it) }
    }
    if (b in ancestorsA) return b

    val seen = mutableSetOf<String>()
    val queueB = ArrayDeque<String>()
    queueB.add(b)
    while (queueB.isNotEmpty()) {
        val cur = queueB.removeFirst()
        if (!seen.add(cur)) continue
        if (cur in ancestorsA) return cur
        val commit = repo.objects.readCommit(cur)
        commit.parents.forEach { queueB.add(it) }
    }
    return null
}

/** Is [ancestor] an ancestor (inclusive) of [descendant]? */
public suspend fun isAncestor(
    repo: GitRepo,
    ancestor: String,
    descendant: String,
): Boolean {
    if (ancestor == descendant) return true
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(descendant)
    while (queue.isNotEmpty()) {
        val cur = queue.removeFirst()
        if (!visited.add(cur)) continue
        if (cur == ancestor) return true
        val commit = repo.objects.readCommit(cur)
        commit.parents.forEach { queue.add(it) }
    }
    return false
}
