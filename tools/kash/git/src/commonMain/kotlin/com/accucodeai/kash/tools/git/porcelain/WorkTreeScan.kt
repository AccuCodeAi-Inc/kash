package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.GitRepo

/**
 * Result of a `.gitignore`-aware walk of the work tree.
 *
 *  - [files] — regular files visible to git (ignored ones excluded)
 *  - [nestedRepoDirs] — directories that contain their own `.git/` and
 *    are therefore reported as opaque `path/` entries (matching real
 *    git's "submodule looks untracked from outside" behavior)
 *  - [ignoredFiles] — files (and top-level ignored directories) that
 *    `.gitignore` rules excluded; useful for `status --ignored` and
 *    `clean -x`
 */
public class WorkTreeScan(
    public val files: Set<String>,
    public val nestedRepoDirs: Set<String>,
    public val ignoredFiles: Set<String>,
)

/**
 * Walk the work tree honoring `.gitignore` (root + nested) and
 * `.git/info/exclude`. Tracked paths (those already in the index or in
 * HEAD's tree) are *never* hidden by .gitignore — that matches real
 * git's behavior: once tracked, ignore rules don't apply.
 *
 * Tracked paths are taken from [trackedPaths]; callers pass the union
 * of HEAD-tree paths and index paths.
 */
public suspend fun scanWorkTree(
    repo: GitRepo,
    trackedPaths: Set<String> = emptySet(),
): WorkTreeScan {
    val fs = repo.fs
    val matcher = GitignoreMatcher()
    matcher.push("", readIgnoreFile(fs, "${repo.layout.gitDir}/info/exclude"))
    val rootIgnore =
        if (repo.layout.workTree == "/") "/.gitignore" else "${repo.layout.workTree}/.gitignore"
    matcher.push("", readIgnoreFile(fs, rootIgnore))

    val files = mutableSetOf<String>()
    val nested = mutableSetOf<String>()
    val ignored = mutableSetOf<String>()
    descend(fs, repo.layout.workTree, "", matcher, trackedPaths, files, nested, ignored, isRoot = true)
    matcher.pop()
    matcher.pop()
    return WorkTreeScan(files, nested, ignored)
}

private suspend fun descend(
    fs: FileSystem,
    absDir: String,
    relDir: String,
    matcher: GitignoreMatcher,
    tracked: Set<String>,
    files: MutableSet<String>,
    nestedRepoDirs: MutableSet<String>,
    ignored: MutableSet<String>,
    isRoot: Boolean,
) {
    var localPushed = false
    if (!isRoot) {
        val local = "$absDir/.gitignore"
        if (fs.exists(local)) {
            matcher.push(relDir, readIgnoreFile(fs, local))
            localPushed = true
        }
    }
    try {
        for (name in fs.list(absDir).sorted()) {
            if (name == ".git") continue
            val sub = if (absDir.endsWith("/")) "$absDir$name" else "$absDir/$name"
            val subRel = if (relDir.isEmpty()) name else "$relDir/$name"
            val isDir = fs.isDirectory(sub)
            if (isDir && fs.exists("$sub/.git")) {
                nestedRepoDirs += subRel
                continue
            }
            val isTracked = subRel in tracked
            val isIgnored = !isTracked && matcher.isIgnored(subRel, isDir)
            if (isIgnored) {
                ignored += if (isDir) "$subRel/" else subRel
                continue
            }
            if (isDir) {
                descend(fs, sub, subRel, matcher, tracked, files, nestedRepoDirs, ignored, isRoot = false)
            } else {
                files += subRel
            }
        }
    } finally {
        if (localPushed) matcher.pop()
    }
}
