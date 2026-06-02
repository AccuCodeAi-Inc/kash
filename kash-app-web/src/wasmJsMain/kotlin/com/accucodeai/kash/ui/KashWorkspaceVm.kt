@file:OptIn(ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.Kash
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.sandbox.NetworkPolicy
import com.accucodeai.kash.api.sandbox.SandboxPolicy
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.fs.MountedFsSnapshot
import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.SnapshotPayload
import com.accucodeai.kash.snapshot.restoreFsAndSlots
import com.accucodeai.kash.snapshot.snapshot as captureMachineSnapshot

/*
 * The whole web app is one virtual machine: a single Kash / KashMachine
 * with one MountedFileSystem, one process table, one `/proc`, one init.
 * Each shell tab is a Kash.Session (a sibling kash child of init) on this
 * VM, so files created in shell-A are visible from shell-B's `ls`, `kill`
 * from one shell signals processes in another, etc. POSIX "one box, many
 * ttys".
 *
 * Shared (workspace-wide) state lives here. Per-shell state — the
 * ComposeTerminal, the cooked-byte stdin, the controlling session —
 * lives on KashSessionRunner.
 */

/**
 * Security posture for a workspace VM. Re-applied (the VM is rebuilt) when
 * a KashFrame host configures the embed; not persisted in snapshots.
 *
 *  - [network] — outbound-network capability (baked into the HTTP client
 *    AND the machine). [NetworkPolicy.None] is allow-all.
 *  - [sandbox] — language-engine posture (e.g. GraalPy/Pyodide host-FS).
 *  - [allowedCommands] — when non-null, the set of **tool** names that
 *    remain usable; shell builtins/keywords are always kept so the shell
 *    can't be bricked. `null` = all commands.
 */
public data class WorkspacePolicy(
    val network: NetworkPolicy = NetworkPolicy.None,
    val sandbox: SandboxPolicy = SandboxPolicy.TRUSTED,
    val allowedCommands: Set<String>? = null,
) {
    public companion object {
        /** Standalone (first-party) default: full trust, allow-all. */
        public val Standalone: WorkspacePolicy = WorkspacePolicy()

        /** Embedded default: no network, hardest sandbox, all commands. */
        public val Embedded: WorkspacePolicy =
            WorkspacePolicy(network = NetworkPolicy.DenyAll, sandbox = SandboxPolicy.SAFE)
    }
}

public class KashWorkspaceVm(
    registryFactory: (NetworkPolicy) -> CommandRegistry,
    public val policy: WorkspacePolicy = WorkspacePolicy.Standalone,
) {
    /** Effective catalog: the network-scoped base registry, tool-filtered by [policy]. */
    public val registry: CommandRegistry = filterTools(registryFactory(policy.network), policy.allowedCommands)

    /**
     * The VM facade. Sessions are created via [kash]`.newSession(...)`.
     * Built with the browser-app's PS1/TERM/SHELL env defaults so every
     * tab starts with the same baseline, and with [policy]'s network +
     * sandbox posture.
     */
    public val kash: Kash =
        Kash(
            registry = registry,
            initialCwd = "/home/user",
            initialEnv =
                mapOf(
                    "HOME" to "/home/user",
                    "PATH" to "/usr/bin:/bin",
                    "USER" to "user",
                    "SHELL" to "/bin/kash",
                    "TERM" to "kash-compose",
                    "PS1" to "$ ",
                ),
            networkPolicy = policy.network,
            sandbox = policy.sandbox,
        )

    /** Compatibility passthroughs for callers that still want the raw VM. */
    public val machine: KashMachine get() = kash.machine

    /** Shared workspace filesystem. Cast to MountedFileSystem for snapshot ops. */
    public val fs: MountedFileSystem = kash.fs as MountedFileSystem

    /** Workspace-wide snapshot — captures every shell on this VM. */
    public fun takeFullSnapshot(): MachineSnapshot? =
        try {
            machine.captureMachineSnapshot()
        } catch (_: Throwable) {
            null
        }

    /** Workspace-wide FS-only snapshot, or null if capture fails. */
    public fun takeFsSnapshot(): MountedFsSnapshot? =
        try {
            fs.snapshot()
        } catch (_: Throwable) {
            null
        }

    /**
     * Restore the workspace FS from a full snapshot and return every saved
     * shell's slot (opaque [com.accucodeai.kash.snapshot.InterpreterSnapshot]
     * JSON). The caller spawns one tab per returned slot, handing each to a
     * [KashSessionRunner] so its REPL rehydrates that exact shell — so a
     * snapshot taken with N tabs comes back as N tabs.
     *
     * The slots are keyed by pid in the snapshot, but tabs fork fresh pids, so
     * we return the slots positionally and let each runner inject its own at
     * its pid; the live slot map is cleared to keep a coincidentally-matching
     * fork from picking up a stale entry. Returns null on failure (the FS may
     * be left partially restored — callers still spawn a blank shell).
     */
    public fun restoreFullShells(snapshot: MachineSnapshot): List<kotlinx.serialization.json.JsonElement>? =
        try {
            machine.restoreFsAndSlots(snapshot)
            val slots = snapshot.snapshotSlots.values.toList()
            machine.snapshotSlots.clear()
            slots
        } catch (_: Throwable) {
            null
        }

    /** Apply an FS-only snapshot. Safe while shells are running. Never throws. */
    public fun restoreFsOnly(snapshot: MountedFsSnapshot): Boolean =
        try {
            fs.restore(snapshot)
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Best-effort snapshot to the reserved autosave slot in
     * [BrowserSnapshotStore]. Tries a full machine snapshot first; falls
     * back to FS-only when the machine isn't quiescent (e.g. the user is
     * mid-`cat /dev/random` and the interpreter slot hasn't published yet
     * — a full snapshot would throw `NonQuiescentException`). Never
     * throws; returns true if either save succeeded.
     */
    public fun writeAutosave(): Boolean {
        val full = takeFullSnapshot()
        if (full != null && BrowserSnapshotStore.saveAutosave(SnapshotPayload.Full(full))) {
            return true
        }
        val fsOnly = takeFsSnapshot() ?: return false
        return BrowserSnapshotStore.saveAutosave(SnapshotPayload.FsOnly(fsOnly))
    }
}

/**
 * Restrict the usable command set to [allowed] (by canonical name), but
 * only for [CommandKind.TOOL] entries — intrinsics, special builtins, and
 * builtins (cd, export, control flow, …) are always kept so the shell stays
 * operable. `null` leaves the registry untouched (all commands).
 */
private fun filterTools(
    base: CommandRegistry,
    allowed: Set<String>?,
): CommandRegistry {
    if (allowed == null) return base
    val kept = base.specs.filter { it.kind != CommandKind.TOOL || it.name in allowed }
    return CommandRegistry(kept.toList())
}

/**
 * Mirror of the JVM Main.kt seed: ensure $HOME exists and drop a
 * default .kashrc so interactive kash sources it on startup. Called
 * once per workspace from a coroutine context (the first shell's
 * `start()`); subsequent calls are idempotent.
 */
internal suspend fun seedHomeAndRc(
    fs: FileSystem,
    homeDir: String,
) {
    try {
        if (!fs.exists(homeDir)) fs.mkdirs(homeDir)
        val rcPath = if (homeDir.endsWith("/")) "$homeDir.kashrc" else "$homeDir/.kashrc"
        if (!fs.exists(rcPath)) {
            fs.writeBytes(rcPath, Kash.DEFAULT_KASHRC.encodeToByteArray())
        }
    } catch (_: Throwable) {
        // Non-fatal — shell just starts without an rc.
    }
}
