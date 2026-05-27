package com.accucodeai.kash.ui

import com.accucodeai.kash.Kash
import com.accucodeai.kash.api.signal.SigInt
import com.accucodeai.kash.api.terminal.ControllingTty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Per-tab shell driver. Holds a [ComposeTerminal] for the tab's pixels
 * + keys, opens a [Kash.Session] on the **shared** [KashWorkspaceVm.kash]
 * VM, and lets the session's `runShellCommand` hand the kash REPL the
 * controlling tty.
 *
 * Multiple instances of this class coexist on one VM: all shells share
 * fs, /proc, the process table, env defaults. Each has its own
 * controlling tty.
 *
 * **No per-tab machine.** Files made by shell-A are visible in shell-B
 * because they hit the same [com.accucodeai.kash.fs.MountedFileSystem].
 */
public class KashSessionRunner(
    private val workspace: KashWorkspaceVm,
    /**
     * Fired after the shell process exits normally (the user typed
     * `exit` / `quit` / hit Ctrl-D at the prompt). Lets the workspace
     * remove the tab.
     */
    private val onExit: () -> Unit = {},
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    public val terminal: ComposeTerminal =
        ComposeTerminal().also { t ->
            // Browser Ctrl-C → SIGINT to THIS tab's shell. The global
            // `foregroundSignalReceiver` slot only fits one listener, so
            // when a second tab's shell starts it overwrites the first
            // tab's entry — Ctrl-C from tab A would deliver to tab B's
            // shell. Per-session map keyed by sid solves that: each tab's
            // shell installs itself under its own sid, and we look up
            // by sid here.
            t.onInterrupt = {
                if (sid > 0) workspace.machine.sessionSignalReceivers[sid]?.invoke(SigInt)
            }
        }

    /**
     * Per-tab env overrides (COLUMNS / LINES). Applied to the child kash
     * process on top of the workspace's [Kash.initialEnv] at spawn time,
     * so each tab can have its own viewport dimensions.
     */
    private val tabEnvOverrides: MutableMap<String, String> = mutableMapOf()

    /**
     * Session id assigned to this tab once the shell spawns. Equals the
     * kash child's pid (it's the session leader). Used to route signals
     * for any descendant tools to this tab's terminal, and to clean up
     * the session on [stop].
     */
    public var sid: Int = -1
        private set

    /**
     * Currently-active attachment sink for this session, if any. Walks
     * the workspace process table looking for a process in this
     * session's pgrp whose attachmentSink slot is set — that's the
     * foreground command (today: `agent`) declaring it wants drag-and-drop
     * to be routed through it instead of the generic path-paste fallback.
     *
     * Returns null when no attachment-aware command is running, in which
     * case the terminal drop handler falls back to writing under
     * /tmp/drops + pasting the path token.
     */
    public fun findAttachmentSink(): com.accucodeai.kash.api.AttachmentSink? {
        if (sid <= 0) return null
        // Last-writer-wins if multiple match: a nested `agent`
        // (foreground agent shells out to `agent`) would set its own
        // sink on top; iterate in insertion order and keep overwriting.
        var found: com.accucodeai.kash.api.AttachmentSink? = null
        for ((_, p) in workspace.machine.processTable) {
            if (p.sid == sid && p.attachmentSink != null) found = p.attachmentSink
        }
        return found
    }

    /** Convenience accessor for the workspace VM behind this runner. */
    public val machineFs: com.accucodeai.kash.fs.FileSystem get() = workspace.fs

    private var runJob: Job? = null
    private var session: Kash.Session? = null

    public fun resizeViewport(
        cols: Int,
        rows: Int,
    ) {
        if (cols <= 0 || rows <= 0) return
        val sizeChanged = (cols != terminal.size().cols || rows != terminal.size().rows)
        terminal.resizeCells(cols, rows)
        tabEnvOverrides["COLUMNS"] = cols.toString()
        tabEnvOverrides["LINES"] = rows.toString()
        if (sizeChanged) terminal.notifyResizeRedraw()
    }

    public fun start() {
        if (runJob != null) return
        runJob =
            scope.launch {
                try {
                    runShell()
                } finally {
                    // Shell exited (user typed `exit`, Ctrl-D, or the
                    // command threw). Notify the workspace so it can
                    // remove the tab. Wrap in try/catch — a thrown
                    // callback shouldn't leak as a coroutine failure.
                    try {
                        onExit()
                    } catch (_: Throwable) {
                    }
                }
            }
    }

    public fun feedKey(key: com.accucodeai.kash.api.terminal.Key) {
        terminal.feedKey(key)
    }

    public fun stop() {
        scope.cancel()
        session?.close()
        session = null
    }

    private suspend fun runShell() {
        // Idempotent .kashrc seed (FS is shared, so the first tab's
        // call wins; later tabs find the file already present).
        seedHomeAndRc(workspace.fs, homeDir = "/home/user")

        val stdinSource = terminal.cookedByteSource()
        val sz = terminal.size()
        tabEnvOverrides["COLUMNS"] = sz.cols.toString()
        tabEnvOverrides["LINES"] = sz.rows.toString()

        val ttyBundle =
            ControllingTty(
                source = stdinSource,
                sink = terminal.asSink,
                terminalControl = terminal,
            )

        // newSession does the setsid/pgid/sessions[pid] registration and
        // installs fd 0/1/2 against the controlling tty for us. All the
        // ritual that used to live inline here.
        val s =
            workspace.kash.newSession(
                env = workspace.kash.initialEnv + tabEnvOverrides,
                interactive = true,
                controllingTty = ttyBundle,
            )
        session = s
        sid = s.process.pid
        try {
            s.runShellCommand()
        } catch (_: Throwable) {
            // Shell threw — exit code unused; the onExit callback in [start]
            // closes the tab regardless. Swallow to keep the launch coroutine
            // from logging a noisy crash.
        } finally {
            s.close()
            session = null
        }
    }
}
