package com.accucodeai.kash.fs

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Router-style [FileSystem] over a list of [Mount]s.
 *
 * Semantics (audited against Linux VFS, Plan 9 bind, WASI preopens):
 *
 * 1. **Longest-prefix routing.** A path resolves to the mount whose
 *    `mountPoint` is the longest absolute prefix of the (normalized) path.
 *    The root mount at `/` is the catch-all.
 *
 * 2. **Path normalization happens at the router, before lookup.** A read of
 *    `/a/../b/c` is normalized to `/b/c` and routes to the mount that owns
 *    `/b`. ("Getting Dot-Dot Right" — Plan 9's lesson.)
 *
 * 3. **Mount points are virtual directories.** [exists] / [isDirectory] /
 *    [list] / [stat] all treat the mount-point path and any ancestor of a
 *    mount point as an existing directory, even when the parent mount has
 *    nothing at that path.
 *
 * 4. **Mount shadows underlying content.** If the root mount has
 *    `/.cache/foo` and `/.cache/sub` is also a mount, then reads of
 *    `/.cache/sub/...` hit the sub-mount (the underlying `/.cache/sub` in
 *    the root mount is masked). `list("/.cache")` shows entries from the
 *    root mount *plus* the sub-mount's first-segment name; if a name
 *    collides, the mount name wins by virtue of set semantics + the fact
 *    that reads underneath go to the mount.
 *
 * 5. **Read-only enforcement.** Mounts flagged `readOnly = true` reject
 *    `sink` / `mkdirs` / `remove` / `chmod` with [ReadOnlyMountException].
 *
 * 6. **Mount table is immutable per instance.** No `addMount` / `removeMount`
 *    at runtime — sessions construct their table up front and freeze it.
 *    Multiple commands in a pipeline see the same view.
 */
public class MountedFileSystem(
    mounts: List<Mount>,
) : FileSystem {
    private val mounts: List<Mount>

    init {
        require(mounts.isNotEmpty()) { "MountedFileSystem requires at least one mount" }
        val normalized =
            mounts.map { it.copy(mountPoint = Paths.normalize(it.mountPoint)) }
        val points = normalized.map { it.mountPoint }
        require(points.contains("/")) { "MountedFileSystem requires a mount at '/'; got $points" }
        require(points.size == points.toSet().size) { "duplicate mount points: $points" }
        // Longest-first so the first prefix match in [routeOrNull] is the longest.
        this.mounts = normalized.sortedByDescending { it.mountPoint.length }
    }

    /** Read-only view of every registered [Mount], in declaration order (root last). */
    public fun mounts(): List<Mount> = mounts

    /**
     * The [FsLabel] of the mount that owns [path] (longest-prefix routed).
     * Used by the file-access recording layer to apply per-mount policy —
     * e.g. record reads+writes on USER, mutations-only on HOST_BORROW,
     * nothing on ENGINE_CACHE/SYSTEM_BIN.
     */
    public fun mountLabel(path: String): FsLabel = route(path).mount.label

    /**
     * Capture a label-aware snapshot.
     *
     * Only [FsLabel.USER] mounts whose backing FS is an [InMemoryFs] have
     * their contents serialized. Non-USER mounts (`ENGINE_CACHE`,
     * `HOST_BORROW`, `EPHEMERAL`) contribute only manifest metadata — engines
     * regenerate their caches on next use, host borrows are out of scope,
     * ephemeral evaporates by definition.
     *
     * USER mounts with a non-`InMemoryFs` backing (e.g. a `HostFs` USER
     * mount) appear in the manifest but get no content snapshot — caller can
     * walk those externally if they want.
     */
    public fun snapshot(): MountedFsSnapshot {
        val userContents = mutableMapOf<String, FsSnapshot>()
        val manifest =
            mounts.map { m ->
                if (m.label == FsLabel.USER) {
                    val inner = m.fs
                    if (inner is InMemoryFs) {
                        userContents[m.mountPoint] = inner.snapshot()
                    }
                }
                MountManifestEntry(m.mountPoint, m.label, m.readOnly)
            }
        return MountedFsSnapshot(userMounts = userContents, mountManifest = manifest)
    }

    /**
     * Restore USER mount contents from [snapshot] in place. Throws
     * [MountManifestMismatchException] if the snapshot's manifest doesn't
     * match this instance's mount table — the caller is expected to
     * reconstruct the mount table itself (engines re-register their cache
     * mounts; user code re-declares borrows) before calling restore.
     *
     * Non-USER mounts are not touched. USER mounts that lack an `InMemoryFs`
     * backing are left alone (no content was snapshotted for them).
     */
    public fun restore(snapshot: MountedFsSnapshot) {
        val actual =
            mounts.map { MountManifestEntry(it.mountPoint, it.label, it.readOnly) }
        if (actual.toSet() != snapshot.mountManifest.toSet()) {
            throw MountManifestMismatchException(snapshot.mountManifest, actual)
        }
        for (m in mounts) {
            if (m.label != FsLabel.USER) continue
            val saved = snapshot.userMounts[m.mountPoint] ?: continue
            val inner = m.fs
            if (inner is InMemoryFs) {
                inner.restore(saved)
            }
        }
    }

    private data class Routed(
        val mount: Mount,
        /** Path relative to the mount root — always starts with `/`. */
        val relativePath: String,
    )

    private fun route(path: String): Routed {
        val p = Paths.normalize(path)
        for (m in mounts) {
            val mp = m.mountPoint
            if (mp == "/") return Routed(m, p)
            if (p == mp) return Routed(m, "/")
            if (p.startsWith("$mp/")) return Routed(m, "/" + p.removePrefix("$mp/"))
        }
        // Unreachable — the init block guarantees a root mount, which matches everything.
        error("no mount matched $p; mounts=$mounts")
    }

    private fun isMountPoint(p: String): Boolean = mounts.any { it.mountPoint == p }

    private fun isAncestorOfMountPoint(p: String): Boolean =
        mounts.any { it.mountPoint != p && it.mountPoint.startsWith(if (p == "/") "/" else "$p/") }

    private fun requireWritable(
        m: Mount,
        path: String,
    ) {
        if (m.readOnly) throw ReadOnlyMountException(path)
    }

    override fun exists(path: String): Boolean = exists(path, opener = null)

    override fun exists(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): Boolean {
        val p = Paths.normalize(path)
        if (isMountPoint(p)) return true
        if (isAncestorOfMountPoint(p)) return true
        val r = route(p)
        return r.mount.fs.exists(r.relativePath, opener)
    }

    override fun isDirectory(path: String): Boolean = isDirectory(path, opener = null)

    override fun isDirectory(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): Boolean {
        // POSIX `chdir(2)` / `stat(2)` follow symlinks: `cd /proc/self`
        // and `cd link-to-dir` both work because the kernel walks the
        // chain before asking "is this S_IFDIR?". Resolve cross-mount
        // first (opener-aware so /proc/self lands on the calling pid),
        // then route the resolved path.
        val resolved =
            try {
                resolveSymlinkChain(path, opener)
            } catch (_: SymlinkLoop) {
                return false
            }
        if (isMountPoint(resolved)) return true
        if (isAncestorOfMountPoint(resolved)) return true
        val r = route(resolved)
        return r.mount.fs.isDirectory(r.relativePath, opener)
    }

    override fun source(path: String): SuspendSource = source(path, opener = null)

    override fun source(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSource {
        val resolved = resolveSymlinkChain(path, opener)
        // Preserve POSIX trailing-slash semantics across the router: by the
        // time we hit the inner FS, the path is normalized (no slash). Check
        // here before delegating.
        if (Paths.endsWithSlash(path) && !isDirectoryNoFollow(resolved)) throw NotADirectory(path)
        val r = route(resolved)
        return r.mount.fs.source(r.relativePath, opener)
    }

    override fun sourceNoFollow(path: String): SuspendSource = sourceNoFollow(path, opener = null)

    override fun sourceNoFollow(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSource {
        val p = Paths.normalize(path)
        if (statLink(p, opener).type == FileType.SYMLINK) throw SymlinkLoop(p)
        return source(path, opener)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = sink(path, append, mode, opener = null)

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSink {
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        val resolved = resolveSymlinkChain(path, opener)
        val r = route(resolved)
        requireWritable(r.mount, path)
        return r.mount.fs.sink(r.relativePath, append, mode, opener)
    }

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
    ): SuspendSink = sinkNoFollow(path, append, opener = null)

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): SuspendSink {
        val p = Paths.normalize(path)
        // statLink throws FileNotFound if the path doesn't exist yet — that's
        // a legitimate sink-create case, so swallow FileNotFound but reject if
        // the existing entry is a symlink.
        val type =
            try {
                statLink(p, opener).type
            } catch (_: FileNotFound) {
                null
            }
        if (type == FileType.SYMLINK) throw SymlinkLoop(p)
        return sink(path, append, mode = 0b110_110_110, opener = opener)
    }

    private fun isDirectoryNoFollow(resolved: String): Boolean =
        if (isMountPoint(resolved) || isAncestorOfMountPoint(resolved)) {
            true
        } else {
            val r = route(resolved)
            r.mount.fs.isDirectory(r.relativePath)
        }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        val r = route(path)
        requireWritable(r.mount, path)
        r.mount.fs.mkdirs(r.relativePath, mode)
    }

    override fun list(path: String): List<String> = list(path, opener = null)

    override fun list(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): List<String> {
        // POSIX `opendir(3)` follows symlinks. `ls /proc/self`,
        // `ls some-link-to-dir` — both walk the chain to the target dir
        // before reading entries. Without this, list on a symlink returns
        // empty (or NotADirectory from the underlying FS).
        val p =
            try {
                resolveSymlinkChain(Paths.normalize(path), opener)
            } catch (_: SymlinkLoop) {
                throw NotADirectory(path)
            }
        val entries = mutableSetOf<String>()

        // Step 1: entries from the mount that owns `p` (if `p` is a real path within it).
        // If `p` is purely a virtual ancestor of a sub-mount (no underlying directory),
        // the owner-mount call may throw NotADirectory — fall through to step 2.
        try {
            val r = route(p)
            entries += r.mount.fs.list(r.relativePath, opener)
        } catch (_: NotADirectory) {
            // Pure virtual directory — only sub-mount names contribute.
        }

        // Step 2: first segment of every mount point that lives strictly below `p`.
        // Mount shadows the underlying entry by union with `entries` (set dedupes;
        // reads of the colliding name route to the mount in step 1 of any later op).
        val prefix = if (p == "/") "/" else "$p/"
        for (m in mounts) {
            val mp = m.mountPoint
            if (mp == p || mp == "/") continue
            if (mp.startsWith(prefix)) {
                val rest = mp.removePrefix(prefix)
                entries += rest.substringBefore('/')
            }
        }

        return entries.sorted()
    }

    /**
     * Route the watch to whichever mount owns [path], translating the
     * mount-relative event paths back to the router's namespace so a
     * caller watching `/var/log/foo` sees events with that path even if
     * the underlying mount sees the file at `/log/foo`.
     */
    override fun watch(path: String): Flow<FsEvent> {
        val r = route(path)
        val mp = r.mount.mountPoint
        val prefix = if (mp == "/") "" else mp
        val inner = r.mount.fs.watch(r.relativePath)
        return flow {
            inner.collect { ev ->
                val absPath = if (prefix.isEmpty()) ev.path else "$prefix${ev.path}"
                val translated: FsEvent =
                    when (ev) {
                        is FsEvent.Created -> FsEvent.Created(absPath)
                        is FsEvent.Modified -> FsEvent.Modified(absPath)
                        is FsEvent.Deleted -> FsEvent.Deleted(absPath)
                    }
                emit(translated)
            }
        }
    }

    override fun remove(path: String) {
        val p = Paths.normalize(path)
        if (isMountPoint(p)) {
            throw RuntimeException("cannot remove mount point: $p")
        }
        if (isAncestorOfMountPoint(p)) {
            // Removing an ancestor of a mount would only delete content in the
            // owning (parent) mount and leave the sub-mount "stranded" — the
            // virtual directory disappears from the parent's listing but the
            // sub-mount itself is still reachable. Reject for consistency
            // and to avoid masking attempts at clearing a tree that crosses
            // mount boundaries.
            throw RuntimeException("cannot remove ancestor of mount point: $p")
        }
        val r = route(p)
        requireWritable(r.mount, path)
        r.mount.fs.remove(r.relativePath)
    }

    override fun chmod(
        path: String,
        mode: Int,
    ) {
        val r = route(path)
        requireWritable(r.mount, path)
        r.mount.fs.chmod(r.relativePath, mode)
    }

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ) {
        val r = route(path)
        requireWritable(r.mount, path)
        r.mount.fs.setMtime(r.relativePath, epochSeconds)
    }

    override fun commandSpec(path: String): com.accucodeai.kash.api.CommandSpec? {
        val p = Paths.normalize(path)
        // Mount points and virtual ancestors are directories, not commands.
        if (isMountPoint(p) || isAncestorOfMountPoint(p)) return null
        return try {
            val r = route(p)
            r.mount.fs.commandSpec(r.relativePath)
        } catch (_: Throwable) {
            null
        }
    }

    override fun openHandle(
        path: String,
        accessMode: com.accucodeai.kash.api.AccessMode,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): com.accucodeai.kash.api.OpenFileDescription? {
        val p = Paths.normalize(path)
        if (isMountPoint(p) || isAncestorOfMountPoint(p)) return null
        return try {
            val r = route(p)
            r.mount.fs.openHandle(r.relativePath, accessMode, opener)
        } catch (_: Throwable) {
            null
        }
    }

    override fun stat(path: String): FileStat = stat(path, opener = null)

    override fun stat(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): FileStat {
        val resolved = resolveSymlinkChain(path, opener)
        return statNoFollow(resolved, opener)
    }

    override fun statLink(path: String): FileStat = statLink(path, opener = null)

    override fun statLink(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): FileStat = statNoFollow(Paths.normalize(path), opener)

    override fun readSymlink(path: String): String = readSymlink(path, opener = null)

    override fun readSymlink(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): String {
        val p = Paths.normalize(path)
        if (isMountPoint(p) || isAncestorOfMountPoint(p)) throw NotASymlink(p)
        val r = route(p)
        return r.mount.fs.readSymlink(r.relativePath, opener)
    }

    override fun createSymlink(
        linkPath: String,
        target: String,
    ) {
        val r = route(linkPath)
        requireWritable(r.mount, linkPath)
        r.mount.fs.createSymlink(r.relativePath, target)
    }

    override fun createHardLink(
        linkPath: String,
        target: String,
    ) {
        // POSIX `link(2)` returns EXDEV across filesystems. For us, "filesystem
        // boundary" == mount boundary — hard links share an inode and inodes
        // can't be shared across backing FSes.
        val targetResolved = resolveSymlinkChain(target)
        val tRoute = route(targetResolved)
        val lRoute = route(linkPath)
        if (tRoute.mount.mountPoint != lRoute.mount.mountPoint) {
            throw CrossMountException(from = target, to = linkPath)
        }
        requireWritable(lRoute.mount, linkPath)
        lRoute.mount.fs.createHardLink(lRoute.relativePath, tRoute.relativePath)
    }

    /**
     * Stat without following symlinks. Mount points and their virtual
     * ancestors are synthesized as directories (same as before); for any
     * other path the routed mount's [FileSystem.statLink] returns the leaf
     * metadata — `FileType.SYMLINK` with `symlinkTarget` populated when
     * applicable.
     */
    private fun statNoFollow(
        p: String,
        opener: com.accucodeai.kash.api.KashProcess? = null,
    ): FileStat {
        if (isMountPoint(p) || isAncestorOfMountPoint(p)) {
            val r = runCatching { route(p) }.getOrNull()
            val underlying =
                r?.let {
                    runCatching { it.mount.fs.statLink(it.relativePath, opener) }.getOrNull()
                }
            if (underlying != null && underlying.type == FileType.DIRECTORY) return underlying.copy(path = p)
            return FileStat(
                path = p,
                type = FileType.DIRECTORY,
                size = 0L,
                mtimeEpochSeconds = 0L,
                mode = 0b111_101_101,
                nlink = 2,
                ownerName = "kash",
                groupName = "kash",
            )
        }
        val r = route(p)
        return r.mount.fs
            .statLink(r.relativePath, opener)
            .copy(path = p)
    }

    /**
     * Walk [path] component-by-component, expanding any symlink to its
     * target (absolute targets restart from `/`, relative targets resolve
     * against the link's parent dir). Returns the final non-symlink
     * absolute path. Bounded at `SYMLOOP_MAX = 40` per POSIX.
     *
     * [opener] is threaded into every per-component `statLink` so backends
     * with per-process symlink targets (ProcFs's `/proc/self`) resolve
     * relative to the calling process, mirroring `current` in the kernel.
     */
    private fun resolveSymlinkChain(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess? = null,
    ): String {
        var depth = 0
        var work = Paths.normalize(path)
        outer@ while (true) {
            if (depth > SYMLOOP_MAX) throw SymlinkLoop(path)
            val components =
                if (work == "/") emptyList() else work.removePrefix("/").split('/')
            var current = "/"
            for (i in components.indices) {
                val c = components[i]
                val next = if (current == "/") "/$c" else "$current/$c"
                if (isMountPoint(next) || isAncestorOfMountPoint(next)) {
                    current = next
                    continue
                }
                val r = route(next)
                val st =
                    try {
                        r.mount.fs.statLink(r.relativePath, opener)
                    } catch (_: FileNotFound) {
                        // Intermediate or leaf doesn't exist — let the caller's
                        // subsequent operation surface the right error.
                        current = next
                        continue
                    }
                if (st.type == FileType.SYMLINK) {
                    val target = st.symlinkTarget ?: ""
                    // Magic-symlink targets like `pipe:[12]`, `anon_inode:[fd-5]`,
                    // or `socket:[…]` (the synthetic strings Linux uses for fds
                    // backed by anonymous inodes) are NOT real paths. Don't
                    // follow them — the FS layer's openHandle for the original
                    // path (e.g. `/dev/fd/12`) short-circuits via the fd table
                    // (see `dupFromFdTable`). Leave the chain unresolved so the
                    // caller's open() runs on the magic-symlink itself.
                    if (isSyntheticSymlinkTarget(target)) {
                        return work
                    }
                    val resolved =
                        if (target.startsWith("/")) {
                            Paths.normalize(target)
                        } else {
                            Paths.normalize(Paths.parent(next) + "/" + target)
                        }
                    val rest = components.drop(i + 1)
                    work = if (rest.isEmpty()) resolved else Paths.normalize("$resolved/" + rest.joinToString("/"))
                    depth++
                    continue@outer
                }
                current = next
            }
            return current
        }
    }

    /**
     * True when [target] is one of Linux's synthetic magic-symlink strings —
     * `pipe:[N]`, `socket:[N]`, `anon_inode:[…]`, etc. These are not real
     * paths; the kernel resolves the corresponding `/proc/<pid>/fd/N` open
     * via its fd table rather than by following the symlink string.
     */
    private fun isSyntheticSymlinkTarget(target: String): Boolean {
        if (target.isEmpty() || target.startsWith("/")) return false
        // A leading non-`/` token followed by `:` (e.g. `pipe:`, `socket:`,
        // `anon_inode:`) signals a synthetic descriptor — real paths never
        // contain `:` before the first `/` (no Windows-style drives in POSIX).
        val colon = target.indexOf(':')
        val slash = target.indexOf('/')
        return colon in 0..<(if (slash < 0) Int.MAX_VALUE else slash)
    }

    override fun listStat(path: String): List<FileStat> = listStat(path, opener = null)

    override fun listStat(
        path: String,
        opener: com.accucodeai.kash.api.KashProcess?,
    ): List<FileStat> {
        val p = Paths.normalize(path)
        val base = if (p == "/") "/" else "$p/"
        return list(p, opener).map { statLink("$base$it", opener) }
    }

    private companion object {
        const val SYMLOOP_MAX = 40
    }
}
