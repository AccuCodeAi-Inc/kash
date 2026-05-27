package com.accucodeai.kash.app

import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.git.GitCommitListener
import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitRemoteAdapter
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.PreCommitValidator
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.defaultCommandSpecs
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.tools.ai.all.aiCommands
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.jgit.JGitFactory
import com.accucodeai.kash.tools.git.jgit.JGitHostAdapter
import com.accucodeai.kash.tools.python3.graalpy.GraalPyEngine
import com.accucodeai.kash.tools.python3.python3Commands

/**
 * JVM-app command catalog: the standard kash catalog plus `python3` backed
 * by GraalPy. The `git` tool is wired to a JGit-backed
 * [JGitHostAdapter], but **lazily** — the JGit repo is only constructed
 * on first access to an adapter property. Calls that never touch git
 * (`echo`, shell builtins, …) pay zero JGit-init cost, and never trigger
 * the `bash --login -c 'which git'` discovery path that hangs under
 * `claude-with-kash`.
 *
 * The adapter handle exposed on [AppStandardRegistry] is the same lazy
 * wrapper — forcing `appReg.jgitAdapter.repoSeed` etc. is what builds
 * the real adapter.
 */
public data class AppStandardRegistry(
    val registry: CommandRegistry,
    /** Always non-null on JVM. Forcing any property builds the real adapter. */
    val jgitAdapter: GitHostAdapter,
)

/**
 * Convenience entry point for callers (mostly tests) that don't have a
 * live VFS handy. The adapter built on first access uses a null VFS,
 * so `~/.gitconfig` reads return empty — same effect as a fresh
 * sandbox. Preserves the legacy signature used across the project.
 */
public fun standardRegistry(): CommandRegistry = appStandardRegistry().registry

/**
 * Build the app registry.
 *
 * @param fsProvider supplies the VFS used for git config lookups
 *   (`/etc/gitconfig`, `~/.gitconfig`). Defaults to `null` for
 *   embedders / tests that don't have a [com.accucodeai.kash.fs.FileSystem]
 *   built yet — JGit then sees empty configs.
 * @param homeProvider supplies `$HOME` for resolving `~/.gitconfig`
 *   on first JGit access. Defaults to `/root`.
 * @param networkPolicy per-adapter override of the outbound-network
 *   gate. `null` means "defer to the session
 *   [com.accucodeai.kash.api.KashMachine]'s networkPolicy."
 */
public fun appStandardRegistry(
    fsProvider: () -> FileSystem? = { null },
    homeProvider: () -> String = { "/root" },
    networkPolicy: NetworkPolicy? = null,
): AppStandardRegistry {
    val adapterLazy =
        lazy {
            JGitFactory.createSessionAdapter(fsProvider(), homeProvider(), networkPolicy)
        }
    val lazyAdapter: GitHostAdapter = LazyGitHostAdapter(adapterLazy)
    val specs =
        defaultCommandSpecs().map { spec ->
            if (spec.name == "git") {
                object : com.accucodeai.kash.api.CommandSpec by spec {
                    override val command = GitCommand(lazyAdapter)
                }
            } else {
                spec
            }
        } + python3Commands(GraalPyEngine()) + aiCommands
    return AppStandardRegistry(CommandRegistry(specs), lazyAdapter)
}

/**
 * [GitHostAdapter] wrapper that forwards every property access through
 * a [Lazy] — JGit's `FileRepository` is only built when something on
 * the adapter is actually read. Lets us register `git` in the
 * [CommandRegistry] without paying for JGit init on every kash start.
 */
private class LazyGitHostAdapter(
    private val source: Lazy<GitHostAdapter>,
) : GitHostAdapter {
    private val delegate: GitHostAdapter get() = source.value

    override val repoSeed: GitRepoSeed get() = delegate.repoSeed
    override val identity: GitIdentity get() = delegate.identity
    override val workTreePath: String get() = delegate.workTreePath
    override val syncBranch: String get() = delegate.syncBranch
    override val remotes: Map<String, GitRemoteAdapter> get() = delegate.remotes
    override val preCommitValidator: PreCommitValidator? get() = delegate.preCommitValidator
    override val onCommit: GitCommitListener get() = delegate.onCommit
    override val networkPolicy: NetworkPolicy? get() = delegate.networkPolicy
}
