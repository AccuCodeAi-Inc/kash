package com.accucodeai.kash.tools.git

import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.git.plumbing.IndexFile
import com.accucodeai.kash.tools.git.plumbing.ObjectStore
import com.accucodeai.kash.tools.git.plumbing.RefStore
import com.accucodeai.kash.tools.git.plumbing.ReflogStore
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.plumbing.decodeIndex
import com.accucodeai.kash.tools.git.plumbing.encodeIndex

/**
 * A typed view of a repo open at [layout]. Subcommands construct one
 * via [open] (or [openFromCwd]), then operate against [objects],
 * [refs], and the index helpers without re-doing path math.
 *
 * Discovery: [openFromCwd] walks up from `cwd` looking for a `.git/`
 * directory and uses the first one it finds. Returns null if none —
 * the calling subcommand prints `not a git repository` and exits 128.
 */
public class GitRepo(
    public val layout: RepoLayout,
    public val fs: FileSystem,
    resolver: GitObjectResolver? = null,
) {
    public val objects: ObjectStore = ObjectStore(layout, fs, resolver)
    public val refs: RefStore = RefStore(layout, fs)
    public val reflog: ReflogStore = ReflogStore(layout, fs)

    public suspend fun readIndex(): IndexFile {
        if (!fs.exists(layout.indexFile)) return IndexFile(version = 2, entries = emptyList())
        return decodeIndex(fs.readBytes(layout.indexFile))
    }

    public suspend fun writeIndex(index: IndexFile) {
        fs.mkdirs(layout.gitDir)
        fs.writeBytes(layout.indexFile, encodeIndex(index))
    }

    public companion object {
        /**
         * Open the repo rooted at exactly [workTree] (where
         * `<workTree>/.git/` must exist). Throws if no `.git/` is
         * there — for discovery semantics use [openFromCwd].
         */
        public fun open(
            fs: FileSystem,
            workTree: String,
            resolver: GitObjectResolver? = null,
        ): GitRepo {
            val layout = RepoLayout(workTree)
            require(fs.exists(layout.gitDir) && fs.isDirectory(layout.gitDir)) {
                "not a git repository: $workTree"
            }
            return GitRepo(layout, fs, resolver)
        }

        /**
         * Walk up from [cwd] looking for a `.git/` directory. Returns
         * the open repo, or null if [cwd] is not inside one.
         */
        public fun openFromCwd(
            fs: FileSystem,
            cwd: String,
            resolver: GitObjectResolver? = null,
        ): GitRepo? {
            var dir = cwd.trimEnd('/').ifEmpty { "/" }
            while (true) {
                val candidate = if (dir == "/") "/.git" else "$dir/.git"
                if (fs.exists(candidate) && fs.isDirectory(candidate)) {
                    return GitRepo(RepoLayout(dir), fs, resolver)
                }
                if (dir == "/") return null
                val parent = dir.substringBeforeLast('/').ifEmpty { "/" }
                if (parent == dir) return null
                dir = parent
            }
        }
    }
}
