@file:OptIn(ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import com.accucodeai.kash.snapshot.ImportedSnapshot
import com.accucodeai.kash.snapshot.Kind
import com.accucodeai.kash.snapshot.SnapshotCodec
import com.accucodeai.kash.snapshot.SnapshotJson
import com.accucodeai.kash.snapshot.SnapshotPayload
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

    // Autosave lives in its own key so it never pollutes the user-facing
    // snapshot list. The stored value is a full self-describing
    // [com.accucodeai.kash.snapshot.SnapshotFile] envelope, so its kind
    // (FULL machine state vs. FS_ONLY fallback when the machine wasn't
    // quiescent) is recovered from the payload — no sidecar needed.
    private const val AUTOSAVE_DATA_KEY: String = "kash.autosave.data.v1"

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

    // ---- File import / export ------------------------------------------
    //
    // Every persisted snapshot — localStorage entries, the autosave, and
    // the downloadable file — is the same self-describing
    // [com.accucodeai.kash.snapshot.SnapshotFile] envelope (carries its
    // own kind + a format tag we sanity-check on read). [encodeToFile] /
    // [decodeFromFile] are the on-disk shape the user gets via
    // "Download Snapshot…" and feeds back via "Upload Snapshot…"; they
    // just forward to the shared [SnapshotCodec].

    /** Serialize [payload] to a self-describing JSON document for download. */
    public fun encodeToFile(
        name: String,
        payload: SnapshotPayload,
    ): String = SnapshotCodec.encodeToFile(name, payload)

    /**
     * Parse a downloaded snapshot file. Returns null if the text isn't a
     * recognizable kash snapshot envelope (wrong format tag, corrupt JSON,
     * or a kind whose payload field is missing).
     */
    public fun decodeFromFile(text: String): ImportedSnapshot? = SnapshotCodec.decodeFromFile(text)

    /** List all saved snapshots, newest first. */
    public fun list(): List<SavedSnapshotMeta> = readIndex().entries.sortedByDescending { it.savedAtMillis }

    /**
     * Persist [payload] under [name]. Overwrites any existing entry with
     * the same name. Returns the metadata that was recorded.
     */
    public fun save(
        name: String,
        payload: SnapshotPayload,
    ): SavedSnapshotMeta {
        val text = SnapshotCodec.encodeToFile(name, payload)
        window.localStorage.setItem(DATA_PREFIX + name, text)
        val meta =
            SavedSnapshotMeta(
                name = name,
                kind = payload.kind,
                sizeBytes = text.length,
                savedAtMillis = nowMillis(),
            )
        val idx = readIndex()
        val updated = idx.entries.filterNot { it.name == name } + meta
        writeIndex(Index(updated))
        return meta
    }

    /** Load by name. Returns null if missing or corrupt. */
    public fun load(name: String): SnapshotPayload? {
        val text = window.localStorage.getItem(DATA_PREFIX + name) ?: return null
        return SnapshotCodec.decodeFromFile(text)?.payload
    }

    /**
     * Persist [payload] as the page-level autosave. Lives in its own
     * localStorage keys (does NOT appear in [list]). Wrapped in try/catch
     * because `beforeunload` calls must never throw or the page can hang;
     * also covers the QuotaExceededError that fires when localStorage is
     * full. Returns true on success.
     */
    public fun saveAutosave(payload: SnapshotPayload): Boolean =
        try {
            window.localStorage.setItem(AUTOSAVE_DATA_KEY, SnapshotCodec.encodeToFile("autosave", payload))
            true
        } catch (_: Throwable) {
            false
        }

    /** Load the autosave. Returns null if absent or corrupt. */
    public fun loadAutosave(): SnapshotPayload? {
        val text = window.localStorage.getItem(AUTOSAVE_DATA_KEY) ?: return null
        return SnapshotCodec.decodeFromFile(text)?.payload
    }

    /** Drop the autosave entry. Called when the user explicitly resets. */
    public fun clearAutosave() {
        window.localStorage.removeItem(AUTOSAVE_DATA_KEY)
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
