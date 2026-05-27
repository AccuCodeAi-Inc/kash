package com.accucodeai.kash.fs

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * Synthetic, read-only [FileSystem] whose entries are derived from a
 * registry of in-process commands. Conceptually serves the role of
 * `/usr/bin` in a real Unix: every utility appears as an executable file
 * whose execution is short-circuited to the in-process [CommandSpec] via
 * [FileSystem.commandSpec].
 *
 * Mounted (by convention) at `/usr/bin`. All paths passed to this FS are
 * mount-relative: the root is `/`, files are `/<name>` (e.g. `/grep`).
 *
 * Per POSIX [XCU §2.9.1.1 step 1.e](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01),
 * regular built-ins MUST appear in `$PATH` for callers to find them — this
 * FS is how kash satisfies that contract for in-process implementations.
 *
 * Writes are rejected. The FS is stateless — snapshots skip it entirely
 * (see [FsLabel.SYSTEM_BIN]); the next session regenerates it from the
 * registry.
 *
 * @param lookup name → [CommandSpec]?. Called lazily on every access so a
 *               registry populated after FS construction works.
 * @param names producer of the full set of utility names (e.g.
 *              `registry.specs.filter { !it.isSpecial }.map { it.name }`).
 */
public class ToolsFs(
    private val lookup: (String) -> CommandSpec?,
    private val names: () -> Set<String>,
) : FileSystem {
    override fun exists(path: String): Boolean {
        val p = Paths.normalize(path)
        if (p == "/") return true
        if (p.count { it == '/' } != 1) return false
        return lookup(p.removePrefix("/")) != null
    }

    override fun isDirectory(path: String): Boolean = Paths.normalize(path) == "/"

    override fun source(path: String): SuspendSource {
        val p = Paths.normalize(path)
        if (p == "/") throw FileNotFound(path)
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        if (lookup(p.removePrefix("/")) == null) throw FileNotFound(path)
        return EmptySuspendSource
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = throw ReadOnlyMountException(path)

    override fun mkdirs(
        path: String,
        mode: Int,
    ): Unit = throw ReadOnlyMountException(path)

    override fun list(path: String): List<String> {
        val p = Paths.normalize(path)
        if (p != "/") throw NotADirectory(path)
        return names().sorted()
    }

    override fun remove(path: String): Unit = throw ReadOnlyMountException(path)

    override fun stat(path: String): FileStat {
        val p = Paths.normalize(path)
        if (p == "/") {
            return FileStat(
                path = p,
                type = FileType.DIRECTORY,
                size = 0L,
                mtimeEpochSeconds = 0L,
                mode = 0b111_101_101, // 0o755
            )
        }
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        if (lookup(p.removePrefix("/")) == null) throw FileNotFound(path)
        return FileStat(
            path = p,
            type = FileType.REGULAR,
            size = 0L,
            mtimeEpochSeconds = 0L,
            mode = 0b111_101_101, // 0o755 — executable, world-readable
        )
    }

    override fun chmod(
        path: String,
        mode: Int,
    ): Unit = throw ReadOnlyMountException(path)

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ): Unit = throw ReadOnlyMountException(path)

    override fun commandSpec(path: String): CommandSpec? {
        val p = Paths.normalize(path)
        if (p == "/") return null
        // Defense in depth: same single-segment guard as exists(). Today
        // every reachable caller pre-gates on exists(), but a future caller
        // could route a multi-segment path here and accidentally match a
        // registry key containing "/".
        if (p.count { it == '/' } != 1) return null
        return lookup(p.removePrefix("/"))
    }
}
