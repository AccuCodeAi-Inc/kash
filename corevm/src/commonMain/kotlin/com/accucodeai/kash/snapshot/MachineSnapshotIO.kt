package com.accucodeai.kash.snapshot

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.Disposition
import com.accucodeai.kash.api.ExitStatus
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.ProcessState
import com.accucodeai.kash.api.RLimit
import com.accucodeai.kash.api.RLimitPair
import com.accucodeai.kash.api.Session
import com.accucodeai.kash.api.Snapshottable
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.fs.MountedFileSystem

/**
 * Capture the current machine state into a serializable [MachineSnapshot].
 *
 * Quiescence check: every non-init process in `RUNNING` or `STOPPED` state
 * must (a) be running a [Snapshottable] command and (b) already have its
 * [KashMachine.snapshotSlots] populated. Zombies pass the check (they're
 * inert by definition), and init is exempt (it never runs a command).
 *
 * Note: in the typical "Claude Code per-`bash -c` invocation" flow this
 * never trips because the kash shell exits before the host calls
 * `snapshot()`, and init auto-reaps children — so the only entry in
 * `processTable` is init itself.
 */
public fun KashMachine.snapshot(): MachineSnapshot {
    // Live-state publication: long-running Snapshottable commands (the
    // interactive shell, primarily) register a callback that writes
    // their current state into `snapshotSlots`. Invoke them now so the
    // upcoming quiescence check sees populated slots — this is what lets
    // a snapshot be taken mid-session, not just at exit.
    for ((_, publish) in liveSnapshotProviders.toMap()) {
        try {
            publish()
        } catch (_: Throwable) {
            // Best-effort — a broken provider shouldn't sink the snapshot.
        }
    }

    // Quiescence: refuse if any non-init non-zombie process is in a
    // state we can't capture (no Snapshottable command, or no slot).
    for ((pid, p) in processTable) {
        if (pid == 1) continue
        if (p.state == ProcessState.ZOMBIE) continue
        val spec =
            registry[p.commandName]
                ?: throw NonQuiescentException(
                    "pid $pid is running unknown command ${p.commandName}",
                )
        val cmd = spec.command
        if (cmd !is Snapshottable) {
            throw NonQuiescentException(
                "pid $pid is running non-Snapshottable command ${p.commandName}",
            )
        }
        if (pid !in snapshotSlots) {
            throw NonQuiescentException(
                "pid $pid is running Snapshottable command ${p.commandName} but its slot is not populated — mid-execution snapshot is not supported",
            )
        }
    }

    // OFD de-dup. Walk every fdTable entry across every process plus each
    // process's controllingTty OFD; mint a stable id per unique OFD
    // instance. Identity (===) comparison via IdentityHashMap is the
    // POSIX-correct semantics: dup2'd / fork-shared OFDs are the same
    // underlying kernel-side object.
    val ofdIds = IdentityHashMap<OpenFileDescription, Int>()
    val ofdTable = mutableMapOf<Int, OpenFileDescriptionSnapshot>()

    fun mintOfdId(ofd: OpenFileDescription): Int {
        ofdIds[ofd]?.let { return it }
        val id = ofdIds.size + 1
        ofdIds[ofd] = id
        ofdTable[id] = ofdSnapshotOf(id, ofd)
        return id
    }

    val processes =
        processTable.values
            .sortedBy { it.pid }
            .map { p ->
                val fdSnap =
                    p.fdTable.mapValues { (_, entry) ->
                        FdTableEntrySnapshot(
                            ofdId = mintOfdId(entry.ofd),
                            closeOnExec = entry.closeOnExec,
                        )
                    }
                val ttyId = p.controllingTty?.let { mintOfdId(it) }
                ProcessSnapshot(
                    pid = p.pid,
                    ppid = p.ppid,
                    pgid = p.pgid,
                    sid = p.sid,
                    umask = p.umask,
                    cwd = p.cwd,
                    rootDir = p.rootDir,
                    env = p.env.toMap(),
                    fdTable = fdSnap,
                    controllingTtyOfdId = ttyId,
                    realUid = p.realUid,
                    effectiveUid = p.effectiveUid,
                    savedUid = p.savedUid,
                    realGid = p.realGid,
                    effectiveGid = p.effectiveGid,
                    savedGid = p.savedGid,
                    supplementaryGids = p.supplementaryGids.toSet(),
                    signalMask = p.signalMask.map { it.name }.toSet(),
                    pendingSignals = p.pendingSignals.map { it.name }.toSet(),
                    dispositions =
                        p.dispositions.entries.associate { (sig, d) ->
                            sig.name to dispositionSnapshotOf(d)
                        },
                    rlimits =
                        p.rlimits.entries.associate { (l, pair) ->
                            l.name to RLimitPairSnapshot(pair.soft, pair.hard)
                        },
                    niceValue = p.niceValue,
                    state = p.state.name,
                    exitStatus = p.exitStatus?.let { exitStatusSnapshotOf(it) },
                    argv = p.argv,
                    commandName = p.commandName,
                    startTimeMillis = p.startTimeMillis,
                    userCpuMicros = p.userCpuMicros,
                    sysCpuMicros = p.sysCpuMicros,
                    children = p.children.map { it.pid },
                )
            }

    val pgroups: Map<Int, Set<Int>> =
        processGroups.entries.associate { (pgid, pids) -> pgid to pids.toSet() }

    val sessionsSnap =
        sessions.values.sortedBy { it.sid }.map { s ->
            SessionSnapshot(
                sid = s.sid,
                leaderPid = s.leaderPid,
                hasControllingTty = s.controllingTty != null,
            )
        }

    val fsSnap =
        when (val f = fs) {
            is MountedFileSystem -> f.snapshot()

            else -> throw IllegalStateException(
                "MachineSnapshot.snapshot() requires fs to be a MountedFileSystem (got ${f::class.simpleName})",
            )
        }

    return MachineSnapshot(
        nextPid = nextPid,
        processes = processes,
        processGroups = pgroups,
        sessions = sessionsSnap,
        ofdTable = ofdTable,
        snapshotSlots = snapshotSlots.toMap(),
        fs = fsSnap,
    )
}

/**
 * Rehydrate just the filesystem and the [KashMachine.snapshotSlots] map.
 * The process table, process groups, sessions, OFD table, and `nextPid`
 * are NOT touched.
 *
 * This is the only restore the per-invocation `bash -c` flow needs: the
 * host clears the slots immediately after this call and then copies
 * exactly one source slot onto the new shell's pid (see `Main.kt`'s
 * `--resume [pid]` handling). [restoreProcessTree] is the heavier sister
 * used by the (future) daemon model.
 */
public fun KashMachine.restoreFsAndSlots(s: MachineSnapshot) {
    val mountedFs =
        fs as? MountedFileSystem
            ?: throw IllegalStateException(
                "restoreFsAndSlots requires fs to be a MountedFileSystem",
            )
    mountedFs.restore(s.fs)
    snapshotSlots.clear()
    snapshotSlots.putAll(s.snapshotSlots)
}

/**
 * Full process-tree restore: filesystem, snapshot slots, process table,
 * process groups, sessions (without their host-owned `ControllingTty`
 * bundle — the host rewires that separately), the OFD table, and
 * `nextPid`. Not wired into the current `Main`; reserved for the future
 * daemon model where backgrounded processes survive a JVM restart.
 *
 * All OFDs are reconstructed with `owning = false` and null
 * `source`/`sink`/`terminalControl` — refcount semantics are no-ops on
 * non-owning OFDs, so we don't need to track per-id retain counts in
 * v1. The host is responsible for rewiring stdio for the foreground
 * shell after this call returns. Reopening FILE OFDs against the
 * restored filesystem (with seek-to-offset) is a future improvement,
 * deliberately punted in v1 because the only real consumer (the
 * daemon) doesn't exist yet.
 *
 * The implementation deliberately discards any process whose pid clashes
 * with init (pid 1) — the host's `ensureInit` has already created init
 * by the time this runs; the snapshot's init entry would otherwise
 * collide. ProcessSnapshot identity for init is otherwise stateless
 * (no fds, no command) so dropping it is lossless.
 */
public fun KashMachine.restoreProcessTree(s: MachineSnapshot) {
    val mountedFs =
        fs as? MountedFileSystem
            ?: throw IllegalStateException(
                "restoreProcessTree requires fs to be a MountedFileSystem",
            )
    mountedFs.restore(s.fs)

    // Build OFD instances first so processes can reference them. PIPE
    // is illegal in a quiescent snapshot — refuse early.
    val ofds = mutableMapOf<Int, OpenFileDescription>()
    for ((id, snap) in s.ofdTable) {
        if (snap.kind == OfdKind.PIPE) {
            throw IllegalStateException(
                "non-quiescent pipe OFD #$id in snapshot — cannot restore",
            )
        }
        val mode =
            when (snap.accessMode) {
                "RDONLY" -> AccessMode.RDONLY
                "WRONLY" -> AccessMode.WRONLY
                "RDWR" -> AccessMode.RDWR
                else -> AccessMode.RDWR
            }
        // For FILE OFDs we'd normally reopen via fs.open(path, mode) and
        // seek. The host fs adapter for that isn't on the FileSystem
        // interface yet, so v1 punts: emit a "shell" OFD with no source/
        // sink — the slot-based interpreter snapshot reconstructs cwd
        // etc. independently, and STDIO OFDs are rewired by Main.kt.
        val ofd =
            OpenFileDescription(
                source = null,
                sink = null,
                accessMode = mode,
                statusFlags = snap.statusFlags,
                offset = snap.offset,
                path = snap.path,
                isTty = snap.isTty,
                owning = false,
            )
        ofds[id] = ofd
    }

    // Drop existing processTable (except init, which was already
    // created by ensureInit before this call) and rebuild from snapshot.
    val init = processTable[1]
    processTable.clear()
    init?.let { processTable[1] = it }

    for (p in s.processes) {
        if (p.pid == 1) continue // init already exists
        val proc =
            KashProcess(
                machine = this,
                pid = p.pid,
                cwd = p.cwd,
                env = p.env.toMutableMap(),
                ppid = p.ppid,
            )
        proc.umask = p.umask
        proc.rootDir = p.rootDir
        proc.pgid = p.pgid
        proc.sid = p.sid
        proc.realUid = p.realUid
        proc.effectiveUid = p.effectiveUid
        proc.savedUid = p.savedUid
        proc.realGid = p.realGid
        proc.effectiveGid = p.effectiveGid
        proc.savedGid = p.savedGid
        proc.supplementaryGids.clear()
        proc.supplementaryGids.addAll(p.supplementaryGids)
        proc.signalMask.clear()
        for (n in p.signalMask) KashSignal.parse(n)?.let { proc.signalMask += it }
        proc.pendingSignals.clear()
        for (n in p.pendingSignals) KashSignal.parse(n)?.let { proc.pendingSignals += it }
        proc.dispositions.clear()
        for ((sigName, d) in p.dispositions) {
            val sig = KashSignal.parse(sigName) ?: continue
            proc.dispositions[sig] = dispositionOf(d)
        }
        proc.rlimits.clear()
        for ((name, pair) in p.rlimits) {
            val l = runCatching { RLimit.valueOf(name) }.getOrNull() ?: continue
            proc.rlimits[l] = RLimitPair(pair.soft, pair.hard)
        }
        proc.niceValue = p.niceValue
        proc.state =
            runCatching { ProcessState.valueOf(p.state) }.getOrDefault(ProcessState.ZOMBIE)
        proc.exitStatus = p.exitStatus?.let { exitStatusOf(it) }
        proc.argv = p.argv
        proc.commandName = p.commandName
        proc.userCpuMicros = p.userCpuMicros
        proc.sysCpuMicros = p.sysCpuMicros

        // fdTable rebuild. Restored OFDs are constructed with owning=false
        // and null source/sink, so retain()/release() are no-ops on them —
        // refcount semantics don't matter for v1. (When FILE OFDs get
        // wired to real reopened sources in a later phase, we'll need
        // proper per-id retain/consume tracking; for now identity-shared
        // OFDs across fdTable entries just point at the same instance.)
        for ((fd, entry) in p.fdTable) {
            val ofd = ofds[entry.ofdId] ?: continue
            proc.fdTable[fd] = FdTableEntry(ofd = ofd, closeOnExec = entry.closeOnExec)
        }
        proc.controllingTty = p.controllingTtyOfdId?.let { ofds[it] }

        processTable[p.pid] = proc
    }

    // Process groups
    processGroups.clear()
    for ((pgid, pids) in s.processGroups) {
        processGroups[pgid] = pids.toMutableSet()
    }

    // Sessions — host wires controllingTty separately for whichever
    // session it chooses.
    sessions.clear()
    for (sess in s.sessions) {
        sessions[sess.sid] = Session(sid = sess.sid, leaderPid = sess.leaderPid)
    }

    // Pid allocator
    nextPid = s.nextPid

    // Slots — same as FS-only restore
    snapshotSlots.clear()
    snapshotSlots.putAll(s.snapshotSlots)

    // Rebuild parent.children lists from ppid relationships
    for (p in processTable.values) {
        p.children.clear()
    }
    for (p in processTable.values) {
        val parent = p.ppid?.let { processTable[it] } ?: continue
        parent.children.add(p)
    }
}

// ---------- snapshot helpers ----------

private fun ofdSnapshotOf(
    id: Int,
    ofd: OpenFileDescription,
): OpenFileDescriptionSnapshot {
    val kind: OfdKind =
        when {
            // STDIO and TTY classification needs richer per-OFD typing
            // than we have today; the host rewires fds 0/1/2 unconditionally
            // and any OFD with a real path is FILE-shaped. Pipes have no
            // path AND no terminalControl AND aren't owning (the
            // interpreter pipeline code constructs them with owning=true
            // for the buffer side, but we conservatively classify
            // path-null + isTty=false as PIPE so quiescence-violators
            // surface loudly).
            ofd.isTty -> OfdKind.TTY

            ofd.path == "/dev/tty" -> OfdKind.TTY

            ofd.path != null -> OfdKind.FILE

            else -> OfdKind.PIPE
        }
    return OpenFileDescriptionSnapshot(
        ofdId = id,
        accessMode = ofd.accessMode.name,
        statusFlags = ofd.statusFlags,
        offset = ofd.offset,
        path = ofd.path,
        isTty = ofd.isTty,
        owning = ofd.owning,
        kind = kind,
        signalOwner = ofd.signalOwner,
    )
}

private fun dispositionSnapshotOf(d: Disposition): DispositionSnapshot =
    when (d) {
        Disposition.Default -> DispositionSnapshot.Default
        Disposition.Ignore -> DispositionSnapshot.Ignore
        is Disposition.Handler -> DispositionSnapshot.Handler(d.script)
    }

private fun exitStatusSnapshotOf(e: ExitStatus): ExitStatusSnapshot =
    when (e) {
        is ExitStatus.Exited -> ExitStatusSnapshot.Exited(e.code)
        is ExitStatus.Signaled -> ExitStatusSnapshot.Signaled(e.signal.name, e.coreDumped)
        is ExitStatus.Stopped -> ExitStatusSnapshot.Stopped(e.signal.name)
    }

// ---------- restore helpers ----------

private fun dispositionOf(d: DispositionSnapshot): Disposition =
    when (d) {
        DispositionSnapshot.Default -> Disposition.Default
        DispositionSnapshot.Ignore -> Disposition.Ignore
        is DispositionSnapshot.Handler -> Disposition.Handler(d.script)
    }

private fun exitStatusOf(e: ExitStatusSnapshot): ExitStatus =
    when (e) {
        is ExitStatusSnapshot.Exited -> {
            ExitStatus.Exited(e.code)
        }

        is ExitStatusSnapshot.Signaled -> {
            val sig = KashSignal.parse(e.signal) ?: KashSignal.parse("TERM")!!
            ExitStatus.Signaled(sig, e.coreDumped)
        }

        is ExitStatusSnapshot.Stopped -> {
            val sig = KashSignal.parse(e.signal) ?: KashSignal.parse("STOP")!!
            ExitStatus.Stopped(sig)
        }
    }

// Tiny KMP-portable IdentityHashMap shim — kotlin stdlib doesn't ship
// one in commonMain, and we need identity semantics (not value equality)
// because two OpenFileDescription instances may have equal field values
// but be distinct objects from `dup2`'s perspective.
private class IdentityHashMap<K : Any, V> {
    private val entries = ArrayList<Pair<K, V>>()

    operator fun get(key: K): V? {
        for ((k, v) in entries) if (k === key) return v
        return null
    }

    operator fun set(
        key: K,
        value: V,
    ) {
        for (i in entries.indices) {
            if (entries[i].first === key) {
                entries[i] = key to value
                return
            }
        }
        entries.add(key to value)
    }

    val size: Int get() = entries.size
}
