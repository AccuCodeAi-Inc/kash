package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.fs.FileSystem
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.nio.file.Files

/**
 * Single entry point for building a fresh session-local
 * [JGitHostAdapter]. Owns the order-of-operations the JGit init
 * needs to be safe:
 *
 *  1. Install [KashSystemReader] (idempotent) and bind it to the
 *     caller's VFS + `$HOME`. Has to happen *before* the
 *     [FileRepositoryBuilder] call below, since constructing a
 *     `FileRepository` triggers `SystemReader.getInstance().openSystemConfig`.
 *  2. Hand [NoDiscoveryFS] to the builder so any JGit code path
 *     that bypasses [SystemReader] (some `Transport` and hook
 *     discovery does) still can't recurse into `bash --login`.
 *  3. Build a bare on-disk repo in a temp dir. We use on-disk
 *     (not [org.eclipse.jgit.internal.storage.dfs.InMemoryRepository])
 *     because JGit's [org.eclipse.jgit.transport.Transport]
 *     requires `Repository.getFS()` to be non-null.
 *  4. Register a JVM shutdown hook to clean up the temp dir.
 *
 * The returned adapter is independent of [fs]/[home] — only the
 * config layer cares about those (via [KashSystemReader]). The
 * adapter's own repo lives on the host FS in a temp dir.
 */
public object JGitFactory {
    public fun createSessionAdapter(
        fs: FileSystem?,
        home: String,
        networkPolicy: NetworkPolicy?,
    ): JGitHostAdapter {
        KashSystemReaderInstaller.installAndBind(fs, home)

        val tempDir = Files.createTempDirectory("kash-jgit-session-").toFile()
        val gitDir = java.io.File(tempDir, "session.git").apply { mkdirs() }
        val repo =
            FileRepositoryBuilder()
                .setFS(NoDiscoveryFS.INSTANCE)
                .setGitDir(gitDir)
                .build()
        repo.create(true)
        // Symbolic HEAD → refs/heads/main even though no commits yet, so
        // `git init`/`git commit` lands at the conventional default branch.
        repo.updateRef(Constants.HEAD).apply { link("refs/heads/main") }
        Runtime.getRuntime().addShutdownHook(
            Thread {
                try {
                    repo.close()
                    tempDir.deleteRecursively()
                } catch (_: Throwable) {
                    // best-effort
                }
            },
        )
        return JGitHostAdapter(repository = repo, workTreePath = "/work", networkPolicy = networkPolicy)
    }
}
