@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.fs.MountedFsSnapshot
import com.accucodeai.kash.snapshot.MachineSnapshot
import kotlinx.browser.window
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

    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

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
