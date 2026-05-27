package com.accucodeai.kash.tools.git

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.git.GitHostAdapter

/**
 * Factory for the `git` tool.
 *
 * - `gitCommands()` (no args) — **default mode**: plain local git CLI
 *   against the VFS. `init`, `add`, `status`, `commit`, `log`,
 *   `branch`, … all work; `push`/`pull`/`fetch`/`clone` fail with
 *   "no remote configured" the way real git does for a fresh repo.
 *   This is what ships in `kashCommands` by default.
 *
 * - `gitCommands(adapter)` — **host-integrated mode**: the host wires
 *   in a [GitHostAdapter] to supply a repo seed, remote backing,
 *   pre-commit validation, and the "sync me back" commit listener.
 *   Used by `kash-app` when the embedder has wired one up; the
 *   adapter-less factory is otherwise indistinguishable from the host
 *   side.
 *
 * Both factories return the same set of [CommandSpec]s; adapter-aware
 * behavior is gated inside each command via a null check on the
 * captured adapter handle.
 */
public fun gitCommands(adapter: GitHostAdapter? = null): List<CommandSpec> = listOf(GitCommand(adapter))
