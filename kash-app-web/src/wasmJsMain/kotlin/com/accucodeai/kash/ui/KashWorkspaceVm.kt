@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.Kash
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.fs.MountedFsSnapshot
import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.restoreFsAndSlots
import com.accucodeai.kash.snapshot.snapshot as captureMachineSnapshot

/**
 * The whole web app is one virtual machine: a single [Kash] / [KashMachine]
 * with one [MountedFileSystem], one process table, one `/proc`, one init.
 * Each shell tab is a [Kash.Session] (a sibling kash child of init) on this
 * VM, so files created in shell-A are visible from shell-B's `ls`, `kill`
 * from one shell signals processes in another, etc. POSIX "one box, many
 * ttys".
 *
 * Shared (workspace-wide) state lives here. Per-shell state — the
 * [ComposeTerminal], the cooked-byte stdin, the controlling session —
 * lives on [KashSessionRunner].
 */
public class KashWorkspaceVm(
    public val registry: CommandRegistry,
) {
    /**
     * The VM facade. Sessions are created via [kash]`.newSession(...)`.
     * Built with the browser-app's PS1/TERM/SHELL env defaults so every
     * tab starts with the same baseline.
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

    /** Workspace-wide FS-only snapshot. */
    public fun takeFsSnapshot(): MountedFsSnapshot = fs.snapshot()

    /**
     * Apply a saved snapshot to this VM. **Destroys** any current shells'
     * slot state: caller must close all tabs first so their kash
     * processes exit cleanly before the snapshot's slots overwrite the
     * live slot map.
     */
    public fun restoreFull(snapshot: MachineSnapshot) {
        machine.restoreFsAndSlots(snapshot)
    }

    /** Apply an FS-only snapshot. Safe while shells are running. */
    public fun restoreFsOnly(snapshot: MountedFsSnapshot) {
        fs.restore(snapshot)
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
        if (full != null && BrowserSnapshotStore.saveAutosave(BrowserSnapshotStore.Payload.Full(full))) {
            return true
        }
        return BrowserSnapshotStore.saveAutosave(BrowserSnapshotStore.Payload.FsOnly(takeFsSnapshot()))
    }
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
