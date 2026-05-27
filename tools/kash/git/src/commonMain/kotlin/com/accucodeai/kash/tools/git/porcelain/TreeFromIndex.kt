package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.tools.git.GitRepo
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.TreeEntry
import com.accucodeai.kash.tools.git.plumbing.encodeTree

/**
 * Write a tree object that reflects the current index — analogous to
 * `git write-tree`. Walks the index's flat path/sha pairs, builds
 * nested tree objects bottom-up, returns the root tree sha.
 *
 * The index already references the blobs (they're written by
 * [gitAddSubcommand]), so this only needs to emit tree objects.
 */
public suspend fun writeTreeFromIndex(
    repo: GitRepo,
    index: IndexFile,
): String = writeDir(repo, index.entries.associateBy { it.path }, "")

private suspend fun writeDir(
    repo: GitRepo,
    entriesByPath: Map<String, com.accucodeai.kash.tools.git.plumbing.IndexEntry>,
    dirPrefix: String,
): String {
    val direct = mutableMapOf<String, com.accucodeai.kash.tools.git.plumbing.IndexEntry>()
    val subdirs = mutableMapOf<String, MutableSet<String>>()
    val keyPrefix = if (dirPrefix.isEmpty()) "" else "$dirPrefix/"

    for ((path, entry) in entriesByPath) {
        if (!path.startsWith(keyPrefix)) continue
        val rest = path.substring(keyPrefix.length)
        if (rest.isEmpty()) continue
        val slash = rest.indexOf('/')
        if (slash < 0) {
            direct[rest] = entry
        } else {
            subdirs.getOrPut(rest.substring(0, slash)) { mutableSetOf() } += path
        }
    }

    val treeEntries = mutableListOf<TreeEntry>()
    for ((name, ie) in direct) {
        treeEntries += TreeEntry(ie.mode, name, ie.sha)
    }
    for ((sub, _) in subdirs) {
        val subSha = writeDir(repo, entriesByPath, if (dirPrefix.isEmpty()) sub else "$dirPrefix/$sub")
        treeEntries += TreeEntry(FileMode.TREE, sub, subSha)
    }
    return repo.objects.write(ObjectType.TREE, encodeTree(treeEntries))
}
