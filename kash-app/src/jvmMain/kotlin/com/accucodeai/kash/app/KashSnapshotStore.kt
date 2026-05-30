package com.accucodeai.kash.app

import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * On-disk persistence for [MachineSnapshot], plus a PID-file lock that
 * serializes concurrent kash invocations against the same snapshot.
 *
 * Layout for the default path `<cwd>/.kash/state.json`:
 *   .kash/state.json        — JSON-serialized [MachineSnapshot].
 *   .kash/state.json.lock   — PID of the kash process currently holding it.
 *
 * Format is JSON. The snapshot data classes are all `@Serializable`;
 * `ByteArray` file contents are encoded as Base64 via
 * [com.accucodeai.kash.snapshot.Base64ByteArraySerializer] so binary
 * payloads stay compact (~4/3× raw instead of JSON's default ~4× via
 * integer arrays). Per-command state slots are stored as `JsonElement`
 * for forward extensibility.
 *
 * Lock is advisory and process-pair-aware: if the recorded PID is dead
 * we silently overwrite (crashed predecessor). If it's live, [acquire]
 * blocks-and-retries for up to [DEFAULT_WAIT_TIMEOUT_MS] before reporting
 * the holder. `--force-unlock` (handled at the call site) bypasses the
 * wait entirely.
 */
public class KashSnapshotStore(
    public val snapshotPath: Path,
) {
    public val lockPath: Path =
        snapshotPath.resolveSibling(snapshotPath.fileName.toString() + ".lock")

    /**
     * Acquire the lock by writing our PID into [lockPath]. If another live
     * process holds it, block (50 ms polls) until release or until
     * [waitTimeoutMs] elapses. Returns null on success, or the
     * still-holding PID if we timed out.
     */
    public fun acquire(
        force: Boolean,
        waitTimeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ): Long? {
        Files.createDirectories(snapshotPath.parent ?: return null)
        val myPid = ProcessHandle.current().pid()
        val deadline = System.currentTimeMillis() + waitTimeoutMs
        while (true) {
            if (force || !Files.exists(lockPath)) break
            val existing = readPid(lockPath) ?: break
            if (existing == myPid) break
            if (!ProcessHandle.of(existing).isPresent) break
            if (System.currentTimeMillis() >= deadline) return existing
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return existing
            }
        }
        Files.writeString(lockPath, myPid.toString())
        return null
    }

    /** Remove the lock file iff we still own it. Best-effort. */
    public fun release() {
        try {
            val myPid = ProcessHandle.current().pid()
            if (Files.exists(lockPath) && readPid(lockPath) == myPid) {
                Files.deleteIfExists(lockPath)
            }
        } catch (_: Throwable) {
            // shutdown-path; nothing actionable
        }
    }

    /**
     * Read [snapshotPath] and decode a [MachineSnapshot]. Returns null on
     * missing-file or any decode failure — the caller should treat null
     * as "no prior state, fresh boot."
     */
    public fun loadOrNull(): MachineSnapshot? {
        if (!Files.exists(snapshotPath)) return null
        return try {
            val text = Files.readString(snapshotPath)
            json.decodeFromString(MachineSnapshot.serializer(), text)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Atomically write [snapshot] to [snapshotPath]: encode → write to
     * `<path>.tmp` → atomic rename. A crash mid-write leaves the previous
     * snapshot intact rather than truncated.
     */
    public fun save(snapshot: MachineSnapshot) {
        Files.createDirectories(snapshotPath.parent ?: return)
        val text = json.encodeToString(MachineSnapshot.serializer(), snapshot)
        val tmp = snapshotPath.resolveSibling(snapshotPath.fileName.toString() + ".tmp")
        Files.writeString(tmp, text)
        Files.move(
            tmp,
            snapshotPath,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun readPid(p: Path): Long? =
        try {
            Files.readString(p).trim().toLongOrNull()
        } catch (_: Throwable) {
            null
        }

    public companion object {
        public const val DEFAULT_WAIT_TIMEOUT_MS: Long = 60_000

        // The one canonical snapshot codec (see :corevm SnapshotJson),
        // shared with the web store and the shell's slot read/write so the
        // on-disk state file round-trips identically across layers.
        private val json: Json = SnapshotJson
    }
}
