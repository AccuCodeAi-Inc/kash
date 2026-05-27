package com.accucodeai.kash.snapshot

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.CommandRegistry
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.KashMachine
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.Session
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies [MachineSnapshot] serialization and the [snapshot] /
 * [restoreFsAndSlots] / [restoreProcessTree] round-trip:
 *
 *  - Plain JSON encode → decode roundtrip is lossless.
 *  - [restoreFsAndSlots] (per-invocation `bash -c` flow) brings back
 *    FS contents and `snapshotSlots`, leaves `nextPid` untouched.
 *  - OFD identity is preserved across the snapshot boundary —
 *    `dup2`-shared fds round-trip to the same OFD instance.
 *  - [restoreProcessTree] (reserved for the future daemon model)
 *    rehydrates sessions and `nextPid`.
 */
class MachineSnapshotRoundtripTest {
    private val json: Json = Json { encodeDefaults = true }

    private fun bootMachine(): KashMachine {
        val inMem = InMemoryFs()
        val mounted = MountedFileSystem(listOf(Mount("/", inMem, FsLabel.USER, readOnly = false)))
        return KashMachine(fs = mounted, registry = CommandRegistry(emptyList()))
    }

    @Test
    fun snapshotJsonRoundTripIsLossless() {
        val machine = bootMachine()
        val init = machine.ensureInit()
        init.env["FOO"] = "bar"
        machine.snapshotSlots[2] = buildJsonObject { put("hello", JsonPrimitive("world")) }

        val snap1 = machine.snapshot()
        val text = json.encodeToString(MachineSnapshot.serializer(), snap1)
        val snap2 = json.decodeFromString(MachineSnapshot.serializer(), text)

        assertEquals(snap1, snap2)
    }

    @Test
    fun fsAndSlotsRestoreCarriesSlotsButNotProcessTree() {
        // Build a "first invocation": run a fake shell that touches a file
        // and stashes a slot, then auto-reaps so only init remains.
        val machineA = bootMachine()
        val init = machineA.ensureInit()
        // Pretend pid 2 ran a shell and persisted state to its slot.
        machineA.snapshotSlots[2] = buildJsonObject { put("env", JsonPrimitive("FOO=hello")) }
        machineA.fs.mkdirs("/work")
        val sinkA = machineA.fs.sink("/work/marker", append = false, mode = 0b110_100_100)
        sinkA.close()

        val snapText = json.encodeToString(MachineSnapshot.serializer(), machineA.snapshot())
        val snap = json.decodeFromString(MachineSnapshot.serializer(), snapText)

        // "Second invocation": fresh machine, restore via FS+slots-only.
        val machineB = bootMachine()
        machineB.ensureInit()
        machineB.restoreFsAndSlots(snap)

        // File from invocation A is visible in invocation B.
        assertTrue(machineB.fs.exists("/work/marker"))
        // Slot from invocation A is in invocation B's machine.
        val slot = machineB.snapshotSlots[2]
        assertNotNull(slot)
        // Critical: nextPid is NOT touched by FS+slots restore. A
        // brand-new spawn from init will allocate pid 2 (matching the
        // slot, so the new shell picks up the saved state).
        assertEquals(2, machineB.nextPid)
    }

    @Test
    fun ofdSharingIsDedupedInSnapshot() {
        // Build a process whose fdTable has fd 1 and fd 3 pointing at the
        // SAME OpenFileDescription (POSIX `dup2` semantics). The snapshot
        // writer must mint a single ofdId for both references.
        val machine = bootMachine()
        val init = machine.ensureInit()
        val sharedOfd =
            OpenFileDescription(
                accessMode = AccessMode.WRONLY,
                path = "/tmp/log",
                isTty = false,
                owning = true,
            )
        init.fdTable[1] = FdTableEntry(ofd = sharedOfd, closeOnExec = false)
        sharedOfd.retain()
        init.fdTable[3] = FdTableEntry(ofd = sharedOfd, closeOnExec = false)
        machine.sessions[1] = Session(sid = 1, leaderPid = 1)

        val snap = machine.snapshot()

        // Both fdTable entries reference the same ofdId.
        val initSnap = snap.processes.single { it.pid == 1 }
        assertEquals(initSnap.fdTable[1]?.ofdId, initSnap.fdTable[3]?.ofdId)
        // The OFD table itself has only one entry for the shared OFD.
        val sharedId = initSnap.fdTable[1]!!.ofdId
        assertNotNull(snap.ofdTable[sharedId])
        assertEquals("/tmp/log", snap.ofdTable[sharedId]?.path)
        // FILE classification (non-null path, non-tty).
        assertEquals(OfdKind.FILE, snap.ofdTable[sharedId]?.kind)
    }

    @Test
    fun fullResumeRestoresSessionsAndNextPid() {
        val machineA = bootMachine()
        machineA.ensureInit()
        machineA.sessions[1] = Session(sid = 1, leaderPid = 1)
        machineA.nextPid = 7

        val snap = machineA.snapshot()
        val machineB = bootMachine()
        machineB.ensureInit()
        machineB.restoreProcessTree(snap)

        assertEquals(1, machineB.sessions[1]?.leaderPid)
        assertEquals(7, machineB.nextPid)
    }
}
