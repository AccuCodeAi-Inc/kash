package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.fs.FileSystem
import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * JGit [SystemReader] that routes system / user / jgit config reads
 * through kash's VFS instead of the host filesystem.
 *
 * Why this exists: JGit's default `SystemReader.Default.openSystemConfig`
 * goes through `FS.getGitSystemConfig` → `FS.discoverGitSystemConfig` →
 * `FS_POSIX.discoverGitExe`, which shells out to
 * `bash --login -c 'which git'`. When kash is on PATH as `bash`
 * (the `claude-with-kash` shim), that recursively re-invokes kash
 * itself — multi-minute hang.
 *
 * Beyond the hang, host-config leakage is wrong on its own merits:
 * a sandboxed shell shouldn't see the user's `/etc/gitconfig` /
 * `~/.gitconfig` / signing keys / safe.directory settings. The
 * VFS-backed config keeps kash's git world self-contained, and
 * `git config --global user.email foo` writes to a VFS path that
 * survives the snapshot round-trip for free.
 */
internal class KashSystemReader(
    delegate: SystemReader,
    private val vfsProvider: () -> FileSystem?,
    private val homeProvider: () -> String,
) : SystemReader.Delegate(delegate) {
    override fun openSystemConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig = VfsBackedConfig(parent, fs, vfsProvider) { "/etc/gitconfig" }

    override fun openUserConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig =
        VfsBackedConfig(parent, fs, vfsProvider) {
            "${homeProvider().trimEnd('/')}/.gitconfig"
        }

    override fun openJGitConfig(
        parent: Config?,
        fs: FS,
    ): FileBasedConfig =
        VfsBackedConfig(parent, fs, vfsProvider) {
            "${homeProvider().trimEnd('/')}/.config/jgit/config"
        }
}

/**
 * Process-singleton wire-up for [KashSystemReader].
 *
 * [installAndBind] is idempotent and thread-safe: the first call
 * swaps in the [KashSystemReader] in front of whatever JGit's
 * default [SystemReader] is; subsequent calls just refresh the
 * VFS + home references. This lets [JGitFactory.createSessionAdapter]
 * call it unconditionally on each invocation — the per-process
 * `SystemReader.setInstance` happens once, and the per-session VFS
 * binding always reflects the latest [com.accucodeai.kash.api.KashMachine].
 *
 * Pre-bind (i.e., if anything in the process touches JGit before
 * we've installed), `vfsRef.get()` is null and [VfsBackedConfig.load]
 * is a no-op — JGit sees empty configs, no recursion, no hang.
 */
internal object KashSystemReaderInstaller {
    private val installed = AtomicBoolean(false)
    private val vfsRef = AtomicReference<FileSystem?>(null)
    private val homeRef = AtomicReference("/root")

    fun installAndBind(
        fs: FileSystem?,
        home: String,
    ) {
        vfsRef.set(fs)
        homeRef.set(home.ifEmpty { "/root" })
        if (installed.compareAndSet(false, true)) {
            val prev = SystemReader.getInstance()
            SystemReader.setInstance(
                KashSystemReader(
                    delegate = prev,
                    vfsProvider = vfsRef::get,
                    homeProvider = homeRef::get,
                ),
            )
        }
    }
}
