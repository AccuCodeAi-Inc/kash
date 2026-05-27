package com.accucodeai.kash.fs

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.snapshot.Base64ByteArraySerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable

/** Serializable wire form of a single file in [InMemoryFs]. */
@Serializable
public data class FileEntry(
    @Serializable(with = Base64ByteArraySerializer::class)
    val bytes: ByteArray,
    val mode: Int,
    val mtime: Long,
    val owner: String = "user",
    val group: String = "user",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileEntry) return false

        if (mode != other.mode) return false
        if (mtime != other.mtime) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (owner != other.owner) return false
        if (group != other.group) return false

        return true
    }

    override fun hashCode(): Int {
        var result = mode
        result = 31 * result + mtime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + owner.hashCode()
        result = 31 * result + group.hashCode()
        return result
    }
}

/** Serializable wire form of a single directory in [InMemoryFs]. */
@Serializable
public data class DirEntry(
    val mode: Int,
    val mtime: Long,
    val owner: String = "user",
    val group: String = "user",
)

/** Serializable wire form of a single symbolic link in [InMemoryFs]. */
@Serializable
public data class SymlinkEntry(
    val target: String,
    val mode: Int,
    val mtime: Long,
    val owner: String = "user",
    val group: String = "user",
)

/** Quiescent snapshot of an [InMemoryFs]. Plain data; safe to serialize. */
@Serializable
public data class FsSnapshot(
    val files: Map<String, FileEntry>,
    val dirs: Map<String, DirEntry>,
    val symlinks: Map<String, SymlinkEntry> = emptyMap(),
)

/**
 * Pure in-memory filesystem. No platform dependencies — safe for every KMP target.
 *
 * Each entry (file or directory) carries metadata: mode bits, mtime, owner,
 * group. [clock] supplies wall-clock seconds; default `{ 0L }` keeps tests
 * deterministic. Production callers inject a real clock.
 *
 * Defaults: files 0o644, directories 0o755, owner/group `"user"`. Mode bits
 * are stored but not yet enforced on reads/writes.
 */
public class InMemoryFs(
    private val clock: () -> Long = { 0L },
) : FileSystem {
    private class FileNode(
        // Segment-chunked storage: appends are O(chunk), not O(file). The
        // same Buffer instance is shared across every hard-linked path, so
        // writes through one path are observable through all the others.
        val content: Buffer,
        var mode: Int,
        var mtime: Long,
        val owner: String = "user",
        val group: String = "user",
        // POSIX `nlink`: incremented on every hard link, decremented on every
        // remove. Same FileNode instance is referenced by every linked path,
        // so reads/writes through any path see the same content.
        var nlink: Int = 1,
    )

    private class DirNode(
        var mode: Int,
        var mtime: Long,
        val owner: String = "user",
        val group: String = "user",
    )

    private class SymlinkNode(
        var target: String,
        var mtime: Long,
        val owner: String = "user",
        val group: String = "user",
    ) {
        // POSIX symlinks have permission bits fixed at 0o777 — l-symbolic
        // links can't be chmod'd. Exposed as a property so the rest of the
        // code keeps reading `mode` uniformly with files/dirs.
        val mode: Int = 0b111_111_111
    }

    private val files = mutableMapOf<String, FileNode>()
    private val dirs = mutableMapOf<String, DirNode>()
    private val symlinks = mutableMapOf<String, SymlinkNode>()

    /**
     * Parent-path → set of direct child leaf-names. Mirrors the union of
     * [files] / [dirs] / [symlinks] but indexed by parent so [list] is
     * O(matches) instead of scanning every key in the FS. Every mutator
     * that adds or removes an entry also updates this index.
     */
    private val childIndex = mutableMapOf<String, MutableSet<String>>()

    /**
     * Event bus for [watch]. Replay = 0, extraBufferCapacity = 64 so a
     * collector that briefly falls behind doesn't drop events. Emitted from
     * every mutator (sink/flush, remove, mkdirs, chmod via setMtime, …).
     */
    private val events: MutableSharedFlow<FsEvent> =
        MutableSharedFlow(extraBufferCapacity = 64)

    init {
        dirs["/"] = DirNode(mode = 0b111_101_101, mtime = clock())
        listOf("/env/bash", "/bin", "/usr", "/usr/bin", "/tmp", "/home", "/home/user").forEach(::mkdirs)
    }

    private fun indexAdd(path: String) {
        if (path == "/") return
        val parent = Paths.parent(path)
        val leaf = path.substringAfterLast('/')
        childIndex.getOrPut(parent) { mutableSetOf() }.add(leaf)
    }

    private fun indexRemove(path: String) {
        if (path == "/") return
        val parent = Paths.parent(path)
        val leaf = path.substringAfterLast('/')
        val set = childIndex[parent] ?: return
        set.remove(leaf)
        if (set.isEmpty()) childIndex.remove(parent)
    }

    /**
     * Rehydrate a previously captured filesystem. The clock still drives any
     * subsequent writes; the snapshot's `mtime` values are preserved as-is.
     */
    public constructor(
        snapshot: FsSnapshot,
        clock: () -> Long = { 0L },
    ) : this(clock) {
        // Delegate to restore() so path normalization happens in one place.
        restore(snapshot)
    }

    /**
     * Replace this filesystem's contents in place with [snapshot]. Used by
     * [MountedFileSystem.restore] to rehydrate a USER mount without rebuilding
     * the mount table (the table is immutable per [MountedFileSystem]).
     *
     * Paths from the snapshot are normalized before being inserted into the
     * internal maps — a malicious snapshot with un-normalized keys (e.g.
     * `/legit/../escape`) cannot inject entries whose lookup-time
     * normalization would lead to surprise content spoofing.
     */
    public fun restore(snapshot: FsSnapshot) {
        files.clear()
        dirs.clear()
        symlinks.clear()
        childIndex.clear()
        for ((path, e) in snapshot.dirs) {
            val key = Paths.normalize(path)
            dirs[key] = DirNode(mode = e.mode, mtime = e.mtime, owner = e.owner, group = e.group)
            indexAdd(key)
        }
        for ((path, e) in snapshot.files) {
            val key = Paths.normalize(path)
            files[key] =
                FileNode(
                    content = Buffer().also { it.write(e.bytes) },
                    mode = e.mode,
                    mtime = e.mtime,
                    owner = e.owner,
                    group = e.group,
                )
            indexAdd(key)
        }
        for ((path, e) in snapshot.symlinks) {
            val key = Paths.normalize(path)
            symlinks[key] =
                SymlinkNode(
                    target = e.target,
                    // Discard e.mode: symlink mode is pinned to 0o777.
                    mtime = e.mtime,
                    owner = e.owner,
                    group = e.group,
                )
            indexAdd(key)
        }
        // Single synthetic "root changed" event lets watchers re-read the
        // whole tree without us emitting thousands of per-entry events
        // (which would also be out of order relative to the replay).
        events.tryEmit(FsEvent.Modified("/"))
    }

    /** Capture the current filesystem as plain data. Safe to encode. */
    public fun snapshot(): FsSnapshot =
        FsSnapshot(
            files =
                files.mapValues { (_, n) ->
                    FileEntry(
                        // peek() yields an independent Source; readByteArray
                        // drains it without consuming the underlying Buffer.
                        bytes = n.content.peek().readByteArray(),
                        mode = n.mode,
                        mtime = n.mtime,
                        owner = n.owner,
                        group = n.group,
                    )
                },
            dirs =
                dirs.mapValues { (_, n) ->
                    DirEntry(mode = n.mode, mtime = n.mtime, owner = n.owner, group = n.group)
                },
            symlinks =
                symlinks.mapValues { (_, n) ->
                    SymlinkEntry(target = n.target, mode = n.mode, mtime = n.mtime, owner = n.owner, group = n.group)
                },
        )

    override fun exists(path: String): Boolean {
        val p = Paths.normalize(path)
        return p in dirs || p in files || p in symlinks
    }

    override fun isDirectory(path: String): Boolean {
        val p = followInternal(Paths.normalize(path))
        return p in dirs
    }

    override fun source(path: String): SuspendSource {
        val p = followInternal(Paths.normalize(path))
        val node =
            files[p] ?: run {
                if (p in dirs) throw RuntimeException("is a directory: $p")
                throw FileNotFound(p)
            }
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        // Snapshot bytes at open time. peek() yields an independent Source
        // over the current segment chain; subsequent writes to node.content
        // are not visible through this view. Replaces the previous
        // Buffer.write(ByteArray) which full-copied node.bytes.
        val view = Buffer()
        view.transferFrom(node.content.peek())
        return view.asSuspendSource()
    }

    override fun sourceNoFollow(path: String): SuspendSource {
        val p = Paths.normalize(path)
        if (p in symlinks) throw SymlinkLoop(p)
        return source(path)
    }

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink {
        if (Paths.endsWithSlash(path)) throw NotADirectory(path)
        val p = followInternal(Paths.normalize(path))
        // Derive auto-parent dir mode from file mode: read implies search,
        // write implies write. The caller already masked `mode` against
        // its umask (FileSystem.sink contract), so this carries the same
        // umask effect into the auto-created parent dirs without
        // requiring umask to flow through as a separate argument.
        ensureParentDir(p, dirModeFromFileMode(mode))
        val existing = files[p]
        val wasCreated = existing == null
        val node =
            if (existing == null) {
                val fresh = FileNode(content = Buffer(), mode = mode and 0xFFF, mtime = clock())
                files[p] = fresh
                indexAdd(p)
                fresh
            } else {
                if (!append) {
                    // Truncate-in-place so hard-linked siblings (same
                    // FileNode instance) observe the truncation. Replacing
                    // the map entry with a fresh node would silently fork
                    // the inode.
                    existing.content.clear()
                    existing.mtime = clock()
                }
                existing
            }
        if (wasCreated) {
            events.tryEmit(FsEvent.Created(p))
        }
        return object : SuspendSink {
            private val pending = Buffer()

            override suspend fun write(
                source: Buffer,
                byteCount: Long,
            ) {
                source.readAtMostTo(pending, byteCount)
            }

            override suspend fun flush() {
                if (pending.size == 0L) return
                // transferFrom drains `pending` into node.content one
                // segment at a time — O(chunk), not O(file).
                node.content.transferFrom(pending)
                node.mtime = clock()
                events.tryEmit(FsEvent.Modified(p))
            }

            override fun close() {
                if (pending.size > 0L) {
                    node.content.transferFrom(pending)
                    node.mtime = clock()
                    events.tryEmit(FsEvent.Modified(p))
                }
            }
        }
    }

    override fun sinkNoFollow(
        path: String,
        append: Boolean,
    ): SuspendSink {
        val p = Paths.normalize(path)
        if (p in symlinks) throw SymlinkLoop(p)
        return sink(path, append)
    }

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {
        val p = Paths.normalize(path)
        if (p == "/") return
        val parts = p.trim('/').split('/')
        var cur = ""
        for (segment in parts) {
            cur = "$cur/$segment"
            if (cur in dirs) continue
            // POSIX mkdir(2) returns EEXIST if path is a file OR a symlink
            // (regardless of what the symlink targets).
            if (cur in files || cur in symlinks) {
                throw RuntimeException("file exists: $cur")
            }
            dirs[cur] = DirNode(mode = mode and 0xFFF, mtime = clock())
            indexAdd(cur)
            events.tryEmit(FsEvent.Created(cur))
        }
    }

    override fun list(path: String): List<String> {
        val p = followInternal(Paths.normalize(path))
        if (p !in dirs) throw NotADirectory(p)
        // O(matches): the parent-children index is kept in sync with every
        // mutator, so we read only the names that actually live in `p`.
        return childIndex[p].orEmpty().sorted()
    }

    override fun remove(path: String) {
        // POSIX unlink does NOT follow symlinks — operates on the link entry.
        val p = Paths.normalize(path)
        // Decrement nlink for files (hard-link bookkeeping). The shared
        // FileNode stays alive as long as any other path map entry still
        // references it; we just remove the directory entry at `p`.
        val removedAny = p in files || p in dirs || p in symlinks
        files[p]?.let { it.nlink = (it.nlink - 1).coerceAtLeast(0) }
        files.remove(p)
        dirs.remove(p)
        symlinks.remove(p)
        if (removedAny) {
            indexRemove(p)
            events.tryEmit(FsEvent.Deleted(p))
        }
    }

    override fun watch(path: String): Flow<FsEvent> {
        val p = Paths.normalize(path)
        // Match events whose path is `p` itself or a descendant (so a
        // watch on `/var/log` sees writes to `/var/log/foo.log`).
        val prefix = if (p == "/") "/" else "$p/"
        return events.filter { it.path == p || it.path.startsWith(prefix) }
    }

    /**
     * Set the mode bits of an existing entry. Cosmetic for now — the FS
     * doesn't enforce them on reads/writes — but consumed by `ls -l` /
     * `stat` and the `chmod` builtin.
     */
    override fun chmod(
        path: String,
        mode: Int,
    ) {
        // POSIX chmod follows symlinks (lchmod is the no-follow variant).
        val p = followInternal(Paths.normalize(path))
        files[p]?.let {
            it.mode = mode and 0xFFF
            events.tryEmit(FsEvent.Modified(p))
            return
        }
        dirs[p]?.let {
            it.mode = mode and 0xFFF
            events.tryEmit(FsEvent.Modified(p))
            return
        }
        throw FileNotFound(p)
    }

    override fun setMtime(
        path: String,
        epochSeconds: Long,
    ) {
        val p = followInternal(Paths.normalize(path))
        files[p]?.let {
            it.mtime = epochSeconds
            events.tryEmit(FsEvent.Modified(p))
            return
        }
        dirs[p]?.let {
            it.mtime = epochSeconds
            events.tryEmit(FsEvent.Modified(p))
            return
        }
        throw FileNotFound(p)
    }

    override fun stat(path: String): FileStat {
        val p = followInternal(Paths.normalize(path))
        return statRaw(p)
    }

    override fun statLink(path: String): FileStat {
        val p = Paths.normalize(path)
        symlinks[p]?.let { s ->
            return FileStat(
                path = p,
                type = FileType.SYMLINK,
                size = s.target.length.toLong(),
                mtimeEpochSeconds = s.mtime,
                mode = s.mode,
                nlink = 1,
                ownerName = s.owner,
                groupName = s.group,
                symlinkTarget = s.target,
            )
        }
        return statRaw(p)
    }

    override fun readSymlink(path: String): String {
        val p = Paths.normalize(path)
        return symlinks[p]?.target
            ?: if (files.containsKey(p) || dirs.containsKey(p)) {
                throw NotASymlink(p)
            } else {
                throw FileNotFound(p)
            }
    }

    override fun createSymlink(
        linkPath: String,
        target: String,
    ) {
        val p = Paths.normalize(linkPath)
        if (p in files || p in dirs || p in symlinks) {
            // POSIX symlink() returns EEXIST; surface as a clear error.
            throw RuntimeException("file exists: $p")
        }
        ensureParentDir(p)
        symlinks[p] = SymlinkNode(target = target, mtime = clock())
        indexAdd(p)
        events.tryEmit(FsEvent.Created(p))
    }

    override fun createHardLink(
        linkPath: String,
        target: String,
    ) {
        // POSIX link(2) follows by default, but Linux's link(2) does NOT
        // follow symlinks (since kernel 2.0). We match Linux: a hard link
        // whose target is a symlink creates a second directory entry
        // pointing at the SAME symlink node (its target string is
        // shared). For regular files we share the FileNode and bump nlink.
        val resolved = Paths.normalize(target)
        val link = Paths.normalize(linkPath)
        if (link in files || link in dirs || link in symlinks) {
            throw RuntimeException("file exists: $link")
        }
        // Symlink-to-symlink hardlink: copy the SymlinkNode reference at
        // the new path. The kash model doesn't track symlink nlink, so
        // we leave it as-is — matches the cosmetic-mode treatment of
        // mode bits.
        symlinks[resolved]?.let { sn ->
            ensureParentDir(link)
            symlinks[link] = sn
            indexAdd(link)
            events.tryEmit(FsEvent.Created(link))
            return
        }
        val src =
            files[resolved] ?: run {
                if (resolved in dirs) throw RuntimeException("hard link to directory not permitted: $target")
                throw FileNotFound(target)
            }
        ensureParentDir(link)
        // Share the same FileNode instance — that's what "same inode" means
        // in our model. Increment nlink.
        src.nlink += 1
        files[link] = src
        indexAdd(link)
        events.tryEmit(FsEvent.Created(link))
    }

    override fun listStat(path: String): List<FileStat> {
        val p = followInternal(Paths.normalize(path))
        if (p !in dirs) throw NotADirectory(p)
        val base = if (p == "/") "/" else "$p/"
        // Use statLink for children so symlinks render as SYMLINK in `ls -l`
        // (matches POSIX `ls -l` default; `-L` would follow).
        return list(p).map { statLink("$base$it") }
    }

    private fun statRaw(p: String): FileStat {
        dirs[p]?.let { d ->
            return FileStat(
                path = p,
                type = FileType.DIRECTORY,
                size = 0L,
                mtimeEpochSeconds = d.mtime,
                mode = d.mode,
                nlink = 2,
                ownerName = d.owner,
                groupName = d.group,
            )
        }
        files[p]?.let { f ->
            return FileStat(
                path = p,
                type = FileType.REGULAR,
                size = f.content.size,
                mtimeEpochSeconds = f.mtime,
                mode = f.mode,
                nlink = f.nlink,
                ownerName = f.owner,
                groupName = f.group,
            )
        }
        throw FileNotFound(p)
    }

    /**
     * Follow symlinks at the leaf of [p] within this FS only. Absolute
     * targets restart from `/`; relative targets resolve against the link's
     * parent dir. Bounded at [SYMLOOP_MAX] hops to break cycles. If a link
     * points outside this FS (its target isn't a local entry), this returns
     * the unresolved target — the caller's subsequent file/dir lookup will
     * then surface [FileNotFound], which matches dangling-symlink semantics.
     */
    private fun followInternal(start: String): String {
        var current = start
        var depth = 0
        while (depth < SYMLOOP_MAX) {
            val s = symlinks[current] ?: return current
            val raw = s.target
            current =
                if (raw.startsWith("/")) {
                    Paths.normalize(raw)
                } else {
                    Paths.normalize(Paths.parent(current) + "/" + raw)
                }
            depth++
        }
        throw SymlinkLoop(start)
    }

    private fun ensureParentDir(
        path: String,
        mode: Int = 0b111_111_111,
    ) {
        val parent = Paths.parent(path)
        if (parent !in dirs) mkdirs(parent, mode)
    }

    /**
     * Derive a directory creation mode from a file creation mode by adding
     * search/execute bits wherever the file allows read or write — so a
     * file's umask-masked mode propagates sensibly to the auto-created
     * parent dirs. `0o644 → 0o755`, `0o600 → 0o700`, `0o666 → 0o777`.
     */
    private fun dirModeFromFileMode(fileMode: Int): Int {
        var m = fileMode and 0xFFF
        // For each owner/group/other triple, set the execute bit if read
        // OR write is set in that triple.
        if ((m and 0b110_000_000) != 0) m = m or 0b001_000_000
        if ((m and 0b000_110_000) != 0) m = m or 0b000_001_000
        if ((m and 0b000_000_110) != 0) m = m or 0b000_000_001
        return m
    }

    private companion object {
        const val SYMLOOP_MAX = 40
    }
}
