package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.tools.git.GitRepo

/**
 * ASCII graph renderer for `git log --graph`. Tracks the open lanes
 * across iterations and emits, for each commit, a "commit row" that
 * marks the commit's lane with `*`. For merge commits (multi-parent)
 * a follow-up `|\` row introduces extra lanes for the additional
 * parents. When two lanes end up pointing at the same sha — because
 * two children share a parent — a `|/` collapse row drops the right
 * lane into the left.
 *
 * Not perfect for highly-criss-crossed histories: real git's graph
 * solver is much more sophisticated. For the common linear-with-merges
 * shape an LLM-driven session produces this is plenty.
 *
 * Output: a list of (graphPrefix, payloadKind) tuples. Callers
 * interleave a payload (commit text) only on the [PayloadKind.Commit]
 * rows; spacer rows have null payloads.
 */
internal class GraphRenderer {
    private val lanes: MutableList<String?> = mutableListOf()

    fun renderCommit(
        sha: String,
        parents: List<String>,
    ): List<String> {
        // Make sure the commit's sha owns a lane. First-time-seen tips
        // get appended to the right.
        var col = lanes.indexOf(sha)
        if (col < 0) {
            lanes.add(sha)
            col = lanes.size - 1
        }
        val rows = mutableListOf<String>()
        // Commit row.
        rows +=
            buildRow { idx, s ->
                when {
                    idx == col -> "*"
                    s != null -> "|"
                    else -> " "
                }
            }
        // Replace current lane with first parent (or null if no parents).
        if (parents.isEmpty()) {
            lanes[col] = null
        } else {
            lanes[col] = parents[0]
            // Add extra parents in new lanes to the right.
            for (i in 1 until parents.size) {
                lanes.add(parents[i])
            }
            if (parents.size > 1) {
                // `|\` row showing the merge's extra branch coming in.
                rows +=
                    buildRow { idx, _ ->
                        when {
                            idx == col -> "|"
                            idx > col && idx <= col + (parents.size - 1) -> "\\"
                            idx < lanes.size && lanes[idx] != null -> "|"
                            else -> " "
                        }
                    }
            }
        }
        // Collapse: any two lanes pointing at the same sha → drop the
        // right one, emit a `/` row showing it sliding left.
        val collapseRow = collapseDuplicateLanes(col)
        if (collapseRow != null) rows += collapseRow

        // Trim trailing nulls so empty lanes don't drag the right margin.
        while (lanes.isNotEmpty() && lanes.last() == null) lanes.removeAt(lanes.size - 1)
        return rows
    }

    private fun collapseDuplicateLanes(focus: Int): String? {
        // Find first pair (i, j>i) with same non-null sha.
        var i = -1
        var j = -1
        outer@ for (a in lanes.indices) {
            val sa = lanes[a] ?: continue
            for (b in a + 1 until lanes.size) {
                if (lanes[b] == sa) {
                    i = a
                    j = b
                    break@outer
                }
            }
        }
        if (i < 0) return null
        // Drop lane j, emit a slide row.
        lanes.removeAt(j)
        return buildRow { idx, s ->
            when {
                idx == i -> {
                    "|"
                }

                idx in (i + 1)..(j - 1) -> {
                    "/"
                }

                idx >= j -> {
                    // shifted left by 1; original idx was idx+1
                    val orig = idx + 1
                    if (orig == j) {
                        "/"
                    } else if (orig < lanes.size + 1 && lanes.getOrNull(idx) != null) {
                        "|"
                    } else {
                        " "
                    }
                }

                s != null -> {
                    "|"
                }

                else -> {
                    " "
                }
            }
        }.also {
            // Squeeze any further duplicates recursively (rare but possible
            // when a 3-way merge collapses multiple lanes at once).
            collapseDuplicateLanes(focus)
        }
    }

    private fun buildRow(cell: (Int, String?) -> String): String {
        val sb = StringBuilder()
        for ((idx, s) in lanes.withIndex()) {
            sb.append(cell(idx, s))
            sb.append(' ')
        }
        return sb.toString().trimEnd()
    }
}

/**
 * Topological walk from [tip] that visits each commit at most once
 * with all of its descendants already visited. Returns commits in
 * the order a real `git log --topo-order` would emit them.
 */
internal suspend fun topoOrder(
    repo: GitRepo,
    tip: String,
): List<String> {
    // First: collect all reachable commits and their parents.
    val all = mutableMapOf<String, List<String>>()
    val stack = ArrayDeque<String>()
    stack.addLast(tip)
    while (stack.isNotEmpty()) {
        val cur = stack.removeLast()
        if (cur in all) continue
        val c =
            try {
                repo.objects.readCommit(cur)
            } catch (_: Throwable) {
                continue
            }
        all[cur] = c.parents
        for (p in c.parents) if (p !in all) stack.addLast(p)
    }
    // Count in-degree (number of children).
    val inDeg = mutableMapOf<String, Int>()
    for (sha in all.keys) inDeg[sha] = 0
    for ((_, parents) in all) for (p in parents) if (p in inDeg) inDeg[p] = (inDeg[p] ?: 0) + 1
    // Kahn-style: emit nodes with 0 in-degree, decrement parents.
    val out = mutableListOf<String>()
    val ready = ArrayDeque<String>()
    for ((sha, n) in inDeg) if (n == 0) ready.addLast(sha)
    // Push tip first if it's in `ready` so we start there.
    if (tip in ready) {
        ready.remove(tip)
        ready.addFirst(tip)
    }
    while (ready.isNotEmpty()) {
        val cur = ready.removeFirst()
        out += cur
        for (p in all[cur].orEmpty()) {
            val d = (inDeg[p] ?: continue) - 1
            inDeg[p] = d
            if (d == 0) ready.addLast(p)
        }
    }
    return out
}
