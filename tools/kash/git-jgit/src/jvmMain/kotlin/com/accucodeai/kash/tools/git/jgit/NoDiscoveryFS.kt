package com.accucodeai.kash.tools.git.jgit

import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.FS_POSIX
import java.io.File

/**
 * [FS_POSIX] subclass that short-circuits JGit's host-`git` discovery
 * to `null`. JGit's default `FS_POSIX.discoverGitExe` shells out to
 * `bash --login -c 'which git'` (and `discoverGitSystemConfig` calls
 * into it). When kash runs under `claude-with-kash`, that `bash` is a
 * symlink to kash itself — the recursive invocation hangs JGit's main
 * thread for minutes.
 *
 * Returning `null` from both seams puts JGit into "no host git, no
 * /etc/gitconfig" mode, which is what we want anyway: the kash
 * sandbox has its own VFS-backed `~/.gitconfig` via
 * [KashSystemReader], and host-tool discovery has no business
 * leaking into the sandbox.
 *
 * Hand this to [org.eclipse.jgit.storage.file.FileRepositoryBuilder.setFS]
 * on every repository built by [JGitFactory]. Belt-and-suspenders with
 * the [KashSystemReader] override — covers code paths that consult
 * the FS directly (some `Transport` and hook-discovery code does).
 *
 * POSIX-only: kash targets macOS and Linux (see CLAUDE.md). On the
 * happy path JGit ships an `FS_Win32` too, but kash doesn't currently
 * support Windows, and `claude-with-kash` is a POSIX shim only.
 */
internal class NoDiscoveryFS private constructor(
    src: FS,
) : FS_POSIX(src) {
    override fun newInstance(): FS = NoDiscoveryFS(this)

    override fun discoverGitSystemConfig(): File? = null

    override fun discoverGitExe(): File? = null

    companion object {
        val INSTANCE: FS = NoDiscoveryFS(FS.detect())
    }
}
