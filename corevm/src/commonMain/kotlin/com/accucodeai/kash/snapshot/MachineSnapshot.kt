package com.accucodeai.kash.snapshot

import com.accucodeai.kash.fs.MountedFsSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Full machine state in serializable form. Captures everything a session
 * needs to resume: the process table, process groups, sessions, the pid
 * allocator, the FD-table OFD layout, per-pid command-state slots, and
 * the filesystem.
 *
 * The on-disk format is plain JSON via `kotlinx-serialization-json`.
 * `ByteArray` payloads (file contents) are encoded as Base64 via
 * [Base64ByteArraySerializer] rather than the default int-array form.
 *
 * **Restore is two-axis**, picked by the host (kash-app's `Main`):
 *
 *   - [restoreFsAndSlots] rehydrates [fs] and [snapshotSlots]. This is
 *     all the per-invocation `bash -c` flow needs; `Main` then copies
 *     exactly one slot — the one identified by `--resume [pid]`,
 *     default 2 — onto the freshly-spawned shell's pid so its
 *     interpreter state comes back.
 *   - [restoreProcessTree] additionally rehydrates `processes`,
 *     `processGroups`, `sessions`, `ofdTable`, and `nextPid`. Not
 *     called by the current `Main` — reserved for a future daemon
 *     model where backgrounded processes survive a JVM restart.
 *
 * Process-tree fields are still captured at save time so a future
 * daemon (or external tool) can read them without changing the on-disk
 * schema.
 */
@Serializable
public data class MachineSnapshot(
    val version: Int = 1,
    val nextPid: Int,
    val processes: List<ProcessSnapshot>,
    val processGroups: Map<Int, Set<Int>>,
    val sessions: List<SessionSnapshot>,
    val ofdTable: Map<Int, OpenFileDescriptionSnapshot>,
    /**
     * Per-pid [com.accucodeai.kash.api.Snapshottable] state. Survives the
     * process being reaped (the map is on the machine, not the process),
     * so the shell's interpreter snapshot persists across the auto-reap
     * that fires when a child of init exits.
     */
    val snapshotSlots: Map<Int, JsonElement>,
    val fs: MountedFsSnapshot,
)

@Serializable
public data class ProcessSnapshot(
    val pid: Int,
    val ppid: Int?,
    val pgid: Int,
    val sid: Int,
    val umask: Int,
    val cwd: String,
    val rootDir: String,
    val env: Map<String, String>,
    val fdTable: Map<Int, FdTableEntrySnapshot>,
    val controllingTtyOfdId: Int?,
    val realUid: Int,
    val effectiveUid: Int,
    val savedUid: Int,
    val realGid: Int,
    val effectiveGid: Int,
    val savedGid: Int,
    val supplementaryGids: Set<Int>,
    val signalMask: Set<String>,
    val pendingSignals: Set<String>,
    val dispositions: Map<String, DispositionSnapshot>,
    val rlimits: Map<String, RLimitPairSnapshot>,
    val niceValue: Int,
    val state: String,
    val exitStatus: ExitStatusSnapshot?,
    val argv: List<String>,
    val commandName: String,
    val startTimeMillis: Long,
    val userCpuMicros: Long,
    val sysCpuMicros: Long,
    val children: List<Int>,
)

@Serializable
public data class FdTableEntrySnapshot(
    val ofdId: Int,
    val closeOnExec: Boolean,
)

/**
 * Serializable form of an `OpenFileDescription`. `source`, `sink`, and
 * `terminalControl` are intentionally omitted — they're live streams /
 * terminal handles that can't cross a process boundary. Restoration uses
 * [kind] to decide how to rebuild them:
 *
 *   - `FILE` → reopen via the restored filesystem at [path] with [accessMode] and `seek([offset])`.
 *   - `STDIO` → host rewires fd 0/1/2 to fresh `System*` adapters; [isTty] is recomputed live.
 *   - `TTY` → re-resolve through the session's controlling-tty bundle the host wired at boot.
 *   - `PIPE` → must NOT appear in a quiescent snapshot (the quiescence check refuses to save first); restore will throw if it does.
 */
@Serializable
public data class OpenFileDescriptionSnapshot(
    val ofdId: Int,
    val accessMode: String,
    val statusFlags: Int,
    val offset: Long,
    val path: String?,
    val isTty: Boolean,
    val owning: Boolean,
    val kind: OfdKind,
    val signalOwner: Int?,
)

public enum class OfdKind { FILE, PIPE, STDIO, TTY }

@Serializable
public data class SessionSnapshot(
    val sid: Int,
    val leaderPid: Int,
    /**
     * Whether the session had a controlling-tty bundle attached at
     * snapshot time. The bundle itself is host-owned and not serialized;
     * at restore the host re-wires the live `ControllingTty` for
     * whichever session(s) it chooses. This flag is informational.
     */
    val hasControllingTty: Boolean,
)

@Serializable
public sealed interface DispositionSnapshot {
    @Serializable
    public data object Default : DispositionSnapshot

    @Serializable
    public data object Ignore : DispositionSnapshot

    @Serializable
    public data class Handler(
        val script: String,
    ) : DispositionSnapshot
}

@Serializable
public data class RLimitPairSnapshot(
    val soft: Long,
    val hard: Long,
)

@Serializable
public sealed interface ExitStatusSnapshot {
    @Serializable
    public data class Exited(
        val code: Int,
    ) : ExitStatusSnapshot

    @Serializable
    public data class Signaled(
        val signal: String,
        val coreDumped: Boolean,
    ) : ExitStatusSnapshot

    @Serializable
    public data class Stopped(
        val signal: String,
    ) : ExitStatusSnapshot
}

/**
 * Thrown by [com.accucodeai.kash.snapshot.snapshot] when the machine has
 * processes that are still live and either (a) running a command that
 * isn't [com.accucodeai.kash.api.Snapshottable], or (b) running a
 * Snapshottable command whose per-pid slot wasn't populated by the time
 * the writer ran. Refusing to save half-state matches the design
 * philosophy: a snapshot is only valid at rest.
 */
public class NonQuiescentException(
    message: String,
) : IllegalStateException(message)
