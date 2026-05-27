package com.accucodeai.kash.tools.zip

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource

/**
 * One entry as seen by `unzip -l` / streamed during extract.
 *
 * Sizes are -1 if the archive was written with the streaming bit set and
 * the local file header didn't carry them (uncommon for kash-produced
 * archives, but Java's `ZipInputStream` can hand back -1 in that case).
 */
public data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val uncompressedSize: Long,
    val mtimeEpochSeconds: Long,
)

/**
 * Streaming reader over a ZIP archive backed by [source]. Entries are
 * yielded sequentially; each [readNextEntry] returns the header, and
 * [entryBytes] / [skipEntry] consume the entry's body before moving on.
 *
 * Backed by `java.util.zip.ZipInputStream` on JVM; throws on wasmJs.
 */
public expect class ZipReader(
    source: SuspendSource,
) {
    /** Returns the next entry, or null at end of archive. */
    public suspend fun readNextEntry(): ZipEntryInfo?

    /** Read the current entry's body fully into memory. */
    public suspend fun entryBytes(): ByteArray

    /** Copy current entry's body to [sink] (8 KiB chunks). */
    public suspend fun copyEntryTo(sink: SuspendSink)

    /** Discard the current entry's body. */
    public suspend fun skipEntry()

    public fun close()
}

/**
 * Streaming writer over a ZIP archive backed by [sink].
 *
 * Compression level 0 = STORED (no compression), 1..9 = DEFLATE at that
 * level (default 6). Per-file mtime defaults to "now" if not specified.
 */
public expect class ZipWriter(
    sink: SuspendSink,
    level: Int = 6,
) {
    /** Begin a directory entry (no body). */
    public suspend fun putDirEntry(
        name: String,
        mtimeEpochSeconds: Long? = null,
    )

    /**
     * Begin a file entry, write [bytes] as its body, and close the entry.
     * Single-shot — for streaming bodies use [openFileEntry].
     */
    public suspend fun putFileEntry(
        name: String,
        bytes: ByteArray,
        mtimeEpochSeconds: Long? = null,
    )

    /**
     * Begin a streaming file entry. Caller writes the body to the returned
     * sink and MUST call [closeEntry] when done (the sink's own `close()`
     * is a no-op — it just delegates back to the underlying archive sink).
     */
    public suspend fun openFileEntry(
        name: String,
        mtimeEpochSeconds: Long? = null,
    ): SuspendSink

    public suspend fun closeEntry()

    /** Finish the central directory and flush to the underlying sink. */
    public suspend fun close()
}
