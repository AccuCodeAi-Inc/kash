@file:OptIn(ExperimentalSerializationApi::class)

package com.accucodeai.kash.snapshot

import com.accucodeai.kash.fs.MountedFsSnapshot
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The single, self-describing on-disk/on-storage form for **every** kash
 * snapshot, shared by every host: the JVM file store
 * (`.kash/state.json`), the browser `localStorage` store, and the
 * download/upload "snapshot file" the web UI hands the user.
 *
 * It wraps either a full [MachineSnapshot] (interpreter state for every
 * Snapshottable process plus the user FS — the default) or, when the
 * machine wasn't quiescent at save time, only a [MountedFsSnapshot]
 * (files / dirs / cwd, no shell state). The [format] and [version] tags
 * make the envelope sanity-checkable on read — anything missing the
 * format tag is not a kash snapshot.
 *
 * Encode/decode go through [SnapshotCodec] so the format never drifts
 * between hosts.
 */
@Serializable
public data class SnapshotFile(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val format: String = FILE_FORMAT,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = FILE_VERSION,
    val kind: Kind,
    val name: String = "snapshot",
    val full: MachineSnapshot? = null,
    val fsOnly: MountedFsSnapshot? = null,
) {
    public companion object {
        public const val FILE_FORMAT: String = "kash-snapshot"
        public const val FILE_VERSION: Int = 1
    }
}

/** Which payload shape a [SnapshotFile] carries. */
@Serializable
public enum class Kind { FULL, FS_ONLY }

/** The decoded snapshot payload, narrowed to the shape actually present. */
public sealed interface SnapshotPayload {
    public val kind: Kind

    public data class Full(
        val snapshot: MachineSnapshot,
    ) : SnapshotPayload {
        override val kind: Kind get() = Kind.FULL
    }

    public data class FsOnly(
        val snapshot: MountedFsSnapshot,
    ) : SnapshotPayload {
        override val kind: Kind get() = Kind.FS_ONLY
    }
}

/** A snapshot parsed from a stored/uploaded envelope, with its saved name. */
public data class ImportedSnapshot(
    val name: String,
    val payload: SnapshotPayload,
)

/**
 * Encode/decode for the [SnapshotFile] envelope, over the one canonical
 * [SnapshotJson] codec. Platform-agnostic — hosts supply only the
 * transport (file IO, localStorage, downloads).
 */
public object SnapshotCodec {
    /** Serialize [payload] to a self-describing JSON document. */
    public fun encodeToFile(
        name: String,
        payload: SnapshotPayload,
    ): String {
        val file =
            when (payload) {
                is SnapshotPayload.Full -> {
                    SnapshotFile(kind = Kind.FULL, name = name, full = payload.snapshot)
                }

                is SnapshotPayload.FsOnly -> {
                    SnapshotFile(kind = Kind.FS_ONLY, name = name, fsOnly = payload.snapshot)
                }
            }
        return SnapshotJson.encodeToString(SnapshotFile.serializer(), file)
    }

    /**
     * Parse a snapshot envelope. Returns null if [text] isn't a
     * recognizable kash snapshot (wrong/absent format tag, corrupt JSON,
     * or a kind whose payload field is missing).
     */
    public fun decodeFromFile(text: String): ImportedSnapshot? =
        try {
            val file = SnapshotJson.decodeFromString(SnapshotFile.serializer(), text)
            if (file.format != SnapshotFile.FILE_FORMAT) {
                null
            } else {
                payloadOf(file)?.let { ImportedSnapshot(file.name, it) }
            }
        } catch (_: Throwable) {
            null
        }

    private fun payloadOf(file: SnapshotFile): SnapshotPayload? =
        when (file.kind) {
            Kind.FULL -> file.full?.let { SnapshotPayload.Full(it) }
            Kind.FS_ONLY -> file.fsOnly?.let { SnapshotPayload.FsOnly(it) }
        }
}
