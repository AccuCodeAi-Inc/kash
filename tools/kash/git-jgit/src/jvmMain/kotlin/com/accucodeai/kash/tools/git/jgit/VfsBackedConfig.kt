package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.fs.FileSystem
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS

/**
 * A [FileBasedConfig] whose backing storage is kash's in-memory
 * VFS, not the host filesystem. The underlying `cfgLocation` passed
 * to the parent constructor is `null`, so JGit's default
 * mtime / canonical-path machinery never fires; we override
 * [load] / [save] / [isOutdated] to talk to [FileSystem] directly.
 *
 * The VFS handle is supplied as a lambda so the same config object
 * can be created before [FileSystem] exists (during early JGit
 * static init) and still pick up the real VFS once
 * [KashSystemReaderInstaller.installAndBind] wires it in. Until
 * then [load] is a no-op and JGit sees empty config — which is
 * exactly what we want during pre-VFS bootstrap.
 *
 * The [vfsPathProvider] is also a lambda so paths referencing
 * `$HOME` (e.g. `~/.gitconfig`) resolve against the *current* home
 * directory rather than whatever was set at construction time.
 */
internal class VfsBackedConfig(
    parent: Config?,
    fs: FS,
    private val vfsProvider: () -> FileSystem?,
    private val vfsPathProvider: () -> String,
) : FileBasedConfig(parent, null, fs) {
    @Volatile private var cachedMtime: Long = -1L

    override fun load() {
        val vfs =
            vfsProvider() ?: run {
                clear()
                return
            }
        val path = vfsPathProvider()
        if (!vfs.exists(path)) {
            clear()
            return
        }
        val bytes =
            runCatching { runBlocking { vfs.readBytes(path) } }.getOrNull()
                ?: run {
                    clear()
                    return
                }
        cachedMtime = runCatching { vfs.stat(path).mtimeEpochSeconds }.getOrDefault(0L)
        runCatching { fromText(bytes.decodeToString()) }
            .onFailure { clear() }
    }

    override fun save() {
        val vfs = vfsProvider() ?: return
        val path = vfsPathProvider()
        val text = toText().encodeToByteArray()
        val parentDir = path.substringBeforeLast('/', "")
        if (parentDir.isNotEmpty() && !vfs.exists(parentDir)) {
            runCatching { vfs.mkdirs(parentDir) }
        }
        runBlocking { vfs.writeBytes(path, text) }
        cachedMtime = runCatching { vfs.stat(path).mtimeEpochSeconds }.getOrDefault(0L)
    }

    override fun isOutdated(): Boolean {
        val vfs = vfsProvider() ?: return false
        val path = vfsPathProvider()
        if (!vfs.exists(path)) return cachedMtime != -1L
        val cur = runCatching { vfs.stat(path).mtimeEpochSeconds }.getOrDefault(0L)
        return cur != cachedMtime
    }
}
