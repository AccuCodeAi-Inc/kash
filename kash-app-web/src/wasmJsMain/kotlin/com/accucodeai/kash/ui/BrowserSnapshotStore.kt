@file:OptIn(ExperimentalWasmJsInterop::class, ExperimentalSerializationApi::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.fs.MountedFsSnapshot
import com.accucodeai.kash.snapshot.MachineSnapshot
import com.accucodeai.kash.snapshot.SnapshotJson
import kotlinx.browser.window
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Browser-side persistence for kash snapshots, backed by `localStorage`.
 *
 * Each saved entry is keyed by a user-chosen name. We store either the
 * full [MachineSnapshot] (interpreter state for every Snapshottable
 * process plus the user FS — default) or, when the user opts in, only
 * the [MountedFsSnapshot] (files / dirs / cwd, no shell state).
 *
 * localStorage was picked over IndexedDB for v1 simplicity: snapshots
 * are kilobytes-to-low-megabytes of JSON text, well under the ~5 MB
 * per-origin quota for typical sessions. If users start hitting the
 * cap we'll graduate to IDBObjectStore.
 */
public object BrowserSnapshotStore {
    private const val INDEX_KEY: String = "kash.snapshots.index.v1"
    private const val DATA_PREFIX: String = "kash.snapshots.data.v1."

    // Autosave lives in its own keys so it never pollutes the user-facing
    // snapshot list. `KIND_KEY` records which payload shape we wrote
    // (FULL machine state vs. FS_ONLY fallback when the machine wasn't
    // quiescent).
    private const val AUTOSAVE_DATA_KEY: String = "kash.autosave.data.v1"
    private const val AUTOSAVE_KIND_KEY: String = "kash.autosave.kind.v1"

    // The one canonical snapshot codec — shared with the JVM store and the
    // shell's slot read/write so FS and VM payloads round-trip identically.
    private val json: Json = SnapshotJson

    @Serializable
    private data class Index(
        val entries: List<SavedSnapshotMeta> = emptyList(),
    )

    @Serializable
    public data class SavedSnapshotMeta(
        val name: String,
        val kind: Kind,
        val sizeBytes: Int,
        val savedAtMillis: Long,
    )

    @Serializable
    public enum class Kind { FULL, FS_ONLY }

    public sealed interface Payload {
        public data class Full(
            val snapshot: MachineSnapshot,
        ) : Payload

        public data class FsOnly(
            val snapshot: MountedFsSnapshot,
        ) : Payload
    }

    // ---- File import / export ------------------------------------------
    //
    // localStorage keys [Kind] separately from the payload; a downloaded
    // file has no sidecar, so we wrap the snapshot in a self-describing
    // envelope that carries its own kind + a format tag we can sanity-check
    // on import. This is the on-disk shape the user gets when they
    // "Download Snapshot…" and feeds back via "Upload Snapshot…".

    private const val FILE_FORMAT: String = "kash-snapshot"
    private const val FILE_VERSION: Int = 1

    @Serializable
    private data class SnapshotFile(
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val format: String = FILE_FORMAT,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS)
        val version: Int = FILE_VERSION,
        val kind: Kind,
        val name: String = "snapshot",
        val full: MachineSnapshot? = null,
        val fsOnly: MountedFsSnapshot? = null,
    )

    /** A snapshot parsed from an uploaded file, with the name it was saved under. */
    public data class ImportedSnapshot(
        val name: String,
        val payload: Payload,
    )

    /** Serialize [payload] to a self-describing JSON document for download. */
    public fun encodeToFile(
        name: String,
        payload: Payload,
    ): String =
        when (payload) {
            is Payload.Full -> {
                json.encodeToString(
                    SnapshotFile.serializer(),
                    SnapshotFile(kind = Kind.FULL, name = name, full = payload.snapshot),
                )
            }

            is Payload.FsOnly -> {
                json.encodeToString(
                    SnapshotFile.serializer(),
                    SnapshotFile(kind = Kind.FS_ONLY, name = name, fsOnly = payload.snapshot),
                )
            }
        }

    /**
     * Parse a downloaded snapshot file. Returns null if the text isn't a
     * recognizable kash snapshot envelope (wrong format tag, corrupt JSON,
     * or a kind whose payload field is missing).
     */
    public fun decodeFromFile(text: String): ImportedSnapshot? =
        try {
            val file = json.decodeFromString(SnapshotFile.serializer(), text)
            if (file.format != FILE_FORMAT) {
                null
            } else {
                val payload =
                    when (file.kind) {
                        Kind.FULL -> file.full?.let { Payload.Full(it) }
                        Kind.FS_ONLY -> file.fsOnly?.let { Payload.FsOnly(it) }
                    }
                payload?.let { ImportedSnapshot(file.name, it) }
            }
        } catch (_: Throwable) {
            null
        }

    /** List all saved snapshots, newest first. */
    public fun list(): List<SavedSnapshotMeta> = readIndex().entries.sortedByDescending { it.savedAtMillis }

    /**
     * Persist [payload] under [name]. Overwrites any existing entry with
     * the same name. Returns the metadata that was recorded.
     */
    public fun save(
        name: String,
        payload: Payload,
    ): SavedSnapshotMeta {
        val (kind, text) =
            when (payload) {
                is Payload.Full -> {
                    Kind.FULL to json.encodeToString(MachineSnapshot.serializer(), payload.snapshot)
                }

                is Payload.FsOnly -> {
                    Kind.FS_ONLY to json.encodeToString(MountedFsSnapshot.serializer(), payload.snapshot)
                }
            }
        window.localStorage.setItem(DATA_PREFIX + name, text)
        val meta =
            SavedSnapshotMeta(
                name = name,
                kind = kind,
                sizeBytes = text.length,
                savedAtMillis = nowMillis(),
            )
        val idx = readIndex()
        val updated = idx.entries.filterNot { it.name == name } + meta
        writeIndex(Index(updated))
        return meta
    }

    /** Load by name. Returns null if missing or corrupt. */
    public fun load(name: String): Payload? {
        val meta = readIndex().entries.firstOrNull { it.name == name } ?: return null
        val text = window.localStorage.getItem(DATA_PREFIX + name) ?: return null
        return try {
            when (meta.kind) {
                Kind.FULL -> Payload.Full(json.decodeFromString(MachineSnapshot.serializer(), text))
                Kind.FS_ONLY -> Payload.FsOnly(json.decodeFromString(MountedFsSnapshot.serializer(), text))
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Persist [payload] as the page-level autosave. Lives in its own
     * localStorage keys (does NOT appear in [list]). Wrapped in try/catch
     * because `beforeunload` calls must never throw or the page can hang;
     * also covers the QuotaExceededError that fires when localStorage is
     * full. Returns true on success.
     */
    public fun saveAutosave(payload: Payload): Boolean =
        try {
            val (kind, text) =
                when (payload) {
                    is Payload.Full -> {
                        Kind.FULL to json.encodeToString(MachineSnapshot.serializer(), payload.snapshot)
                    }

                    is Payload.FsOnly -> {
                        Kind.FS_ONLY to json.encodeToString(MountedFsSnapshot.serializer(), payload.snapshot)
                    }
                }
            window.localStorage.setItem(AUTOSAVE_DATA_KEY, text)
            window.localStorage.setItem(AUTOSAVE_KIND_KEY, kind.name)
            true
        } catch (_: Throwable) {
            false
        }

    /** Load the autosave. Returns null if absent or corrupt. */
    public fun loadAutosave(): Payload? {
        val text = window.localStorage.getItem(AUTOSAVE_DATA_KEY) ?: return null
        val kindName = window.localStorage.getItem(AUTOSAVE_KIND_KEY) ?: return null
        val kind =
            try {
                Kind.valueOf(kindName)
            } catch (_: Throwable) {
                return null
            }
        return try {
            when (kind) {
                Kind.FULL -> Payload.Full(json.decodeFromString(MachineSnapshot.serializer(), text))
                Kind.FS_ONLY -> Payload.FsOnly(json.decodeFromString(MountedFsSnapshot.serializer(), text))
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Drop the autosave entry. Called when the user explicitly resets. */
    public fun clearAutosave() {
        window.localStorage.removeItem(AUTOSAVE_DATA_KEY)
        window.localStorage.removeItem(AUTOSAVE_KIND_KEY)
    }

    /** Delete a saved snapshot by name. Silent no-op if absent. */
    public fun delete(name: String) {
        window.localStorage.removeItem(DATA_PREFIX + name)
        val idx = readIndex()
        writeIndex(Index(idx.entries.filterNot { it.name == name }))
    }

    private fun readIndex(): Index {
        val raw = window.localStorage.getItem(INDEX_KEY) ?: return Index()
        return try {
            json.decodeFromString(Index.serializer(), raw)
        } catch (_: Throwable) {
            Index()
        }
    }

    private fun writeIndex(index: Index) {
        window.localStorage.setItem(INDEX_KEY, json.encodeToString(Index.serializer(), index))
    }

    private fun nowMillis(): Long = jsDateNow().toLong()
}

private fun jsDateNow(): Double = js("Date.now()")
