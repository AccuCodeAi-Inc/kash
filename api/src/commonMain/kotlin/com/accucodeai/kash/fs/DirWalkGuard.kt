package com.accucodeai.kash.fs

/**
 * Hard upper bound on recursive directory descent, shared by every tool that
 * walks a tree (`find`, `ls -R`, `du`, `tree`, `diff -r`, `cp -r`, `chmod -R`,
 * `grep -r`, `rm -r`). A backstop for cycles or pathological trees that slip
 * past the symlink/visited guard below — e.g. a [FileSystem] that doesn't
 * report symlinks via [FileSystem.statLink]. No real source tree comes close.
 */
public const val MAX_FS_WALK_DEPTH: Int = 4096

/**
 * Cycle/symlink guard for recursive directory walks. Construct ONE per
 * top-level walk and call [shouldDescend] for each directory entry the walk
 * discovers, to decide whether to recurse into it. This is the single place
 * the "is it safe + allowed to descend here" policy lives, so individual tools
 * don't each re-derive (and mis-derive) it.
 *
 * Semantics match GNU defaults:
 *  - A symlink discovered DURING the walk is NOT followed unless
 *    [followSymlinks] is set (the tool's `-L`/`-H`/`-l`). Command-line operands
 *    are followed by the caller directly and never pass through here, matching
 *    how the real tools always traverse a symlink named on the command line.
 *  - Under [followSymlinks], a symlink whose resolved target was already
 *    entered is skipped — this breaks `/a/b -> /a`-style cycles.
 *  - Descent at or past [maxDepth] is refused regardless, as a last resort.
 *
 * Not thread-safe; a single walk is single-threaded.
 */
public class DirWalkGuard(
    private val fs: FileSystem,
    private val followSymlinks: Boolean = false,
    private val maxDepth: Int = MAX_FS_WALK_DEPTH,
) {
    /** Resolved targets of symlinks already followed — the cycle breaker. */
    private val followedTargets = HashSet<String>()

    /**
     * Whether the walk may recurse into [childAbs] (a resolved absolute path
     * just listed from its parent directory), where [depth] is the depth of
     * [childAbs] itself (the root's direct children are depth 1). Returns
     * `false` for non-directories, for symlinked directories when not
     * following, for an already-followed symlink target (cycle), and once
     * [depth] reaches [maxDepth].
     */
    public fun shouldDescend(
        childAbs: String,
        depth: Int,
    ): Boolean {
        if (depth >= maxDepth) return false
        val isSymlink =
            runCatching { fs.statLink(childAbs).type == FileType.SYMLINK }.getOrDefault(false)
        if (isSymlink) {
            if (!followSymlinks) return false
            val target =
                runCatching { Paths.resolve(Paths.parent(childAbs), fs.readSymlink(childAbs)) }
                    .getOrNull() ?: return false
            // Already walked into this target → following again would loop.
            if (!followedTargets.add(target)) return false
        }
        return runCatching { fs.isDirectory(childAbs) }.getOrDefault(false)
    }
}
