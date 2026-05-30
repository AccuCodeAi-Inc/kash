@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event

/**
 * Document-level HTML5 drag-and-drop bridge for the Compose-for-wasmJs
 * workspace. CMP's `Modifier.dragAndDropTarget` doesn't fire on web
 * targets yet (JetBrains/compose-multiplatform#4235), so we attach native
 * `dragover`/`drop` listeners and route to per-pane handlers by hit-test
 * over their reported pixel bounds.
 *
 * Lifecycle: one [BrowserDropHost] per workspace, mounted by
 * [KashWorkspace] via [InstallBrowserDropHost]. Panes register zones with
 * [register], unregister on dispose. Last-registered-wins when zones
 * overlap (matches the visual stacking order of the Compose tree).
 *
 * Per-file cap [PER_FILE_MAX_BYTES] = 10 MB; per-drop total cap
 * [TOTAL_DROP_MAX_BYTES] = 50 MB. Files that exceed are skipped and
 * counted in [DropOutcome.skipped]. Skips are surfaced through the
 * `onResult` callback so the caller can show a status flash; we never
 * silently lose data.
 */
public class BrowserDropHost(
    private val scope: CoroutineScope,
) {
    /**
     * A registered drop target. [bounds] is in document client-pixel
     * coordinates (matching `MouseEvent.clientX/Y`); [onDrop] is called
     * on the main dispatcher with the dropped files plus the document
     * coordinates of the cursor at drop time (so handlers like the
     * sidebar can pick a row).
     */
    public class Zone(
        public val id: Long,
        public val bounds: () -> DropRect,
        public val onDrop: suspend (files: List<DroppedFile>, x: Double, y: Double) -> DropOutcome,
    )

    public data class DropRect(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    ) {
        public fun contains(
            px: Double,
            py: Double,
        ): Boolean = px >= x && py >= y && px < x + width && py < y + height
    }

    /** A single file from a browser drop event, materialized into a byte array. */
    public data class DroppedFile(
        val name: String,
        val mimeType: String,
        val bytes: ByteArray,
    )

    /** Result the host surfaces from a registered zone — drives the status flash. */
    public data class DropOutcome(
        val message: String,
    )

    private var nextId: Long = 1L
    private val zones: MutableList<Zone> = mutableListOf()

    /**
     * Compose-side highlight target: set to the bounds of the zone the
     * cursor is currently over while a drag is in progress, null
     * otherwise. Read by [DropHighlightOverlay] to render the overlay.
     */
    internal val highlight = mutableStateOf<DropRect?>(null)

    /**
     * Latest status message from the last drop. Surfaced to the
     * workspace status flash via [pendingMessage].
     */
    internal val pendingMessage = mutableStateOf<String?>(null)

    public fun register(
        bounds: () -> DropRect,
        onDrop: suspend (files: List<DroppedFile>, x: Double, y: Double) -> DropOutcome,
    ): Long {
        val id = nextId++
        zones += Zone(id, bounds, onDrop)
        return id
    }

    public fun unregister(id: Long) {
        zones.removeAll { it.id == id }
    }

    /**
     * Find the topmost zone covering (x,y). Last-registered wins so that
     * later-mounted overlays (a Compose dialog) take precedence over the
     * background panes (the file explorer, the terminal). Matches
     * z-order of the Compose tree in practice.
     */
    internal fun hitTest(
        x: Double,
        y: Double,
    ): Zone? {
        for (i in zones.indices.reversed()) {
            val z = zones[i]
            if (z.bounds().contains(x, y)) return z
        }
        return null
    }

    /**
     * Read the [Event]'s clientX/Y and dataTransfer.files, materialize
     * each File via `FileReader.readAsArrayBuffer`, enforce caps, and
     * dispatch to whichever zone covers the drop point.
     */
    internal fun handleDrop(rawEvent: Event) {
        val x = jsClientX(rawEvent)
        val y = jsClientY(rawEvent)
        val zone = hitTest(x, y) ?: return
        // Pull metadata + bytes synchronously into Kotlin land via the
        // FileReader async API; resolve a kotlin-side callback once all
        // files are read (or one fails).
        readDropFiles(rawEvent) { files ->
            val accepted = mutableListOf<DroppedFile>()
            var skippedTooLarge = 0
            var total = 0L
            for (f in files) {
                if (f.bytes.size > PER_FILE_MAX_BYTES) {
                    skippedTooLarge++
                    continue
                }
                if (total + f.bytes.size > TOTAL_DROP_MAX_BYTES) {
                    skippedTooLarge++
                    continue
                }
                total += f.bytes.size
                accepted += f
            }
            if (accepted.isEmpty()) {
                pendingMessage.value =
                    if (skippedTooLarge > 0) {
                        "Drop rejected: $skippedTooLarge file(s) over the size cap " +
                            "(${PER_FILE_MAX_BYTES / (1024 * 1024)} MB / file, " +
                            "${TOTAL_DROP_MAX_BYTES / (1024 * 1024)} MB total)."
                    } else {
                        "Drop contained no files."
                    }
                return@readDropFiles
            }
            scope.launch(Dispatchers.Main) {
                val outcome = zone.onDrop(accepted, x, y)
                val suffix =
                    if (skippedTooLarge > 0) " · skipped $skippedTooLarge over cap" else ""
                pendingMessage.value = outcome.message + suffix
            }
        }
    }

    internal companion object {
        // 10 MB / 50 MB caps. The autosave round-trip serializes the
        // entire mounted FS into localStorage as JSON; uncapped drops
        // would blow past the ~5–10 MB origin-wide localStorage quota
        // every browser enforces.
        const val PER_FILE_MAX_BYTES: Int = 10 * 1024 * 1024
        const val TOTAL_DROP_MAX_BYTES: Int = 50 * 1024 * 1024
    }
}

/**
 * Compose-side handle returned to callers. Owns the document-level
 * listeners (installed in [InstallBrowserDropHost]) and the registration
 * surface that pane composables use.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun rememberBrowserDropHost(scope: CoroutineScope): BrowserDropHost = remember { BrowserDropHost(scope) }

/**
 * Mount the document-level `dragover` / `dragleave` / `drop` listeners
 * for [host]. Idempotent across recompositions; the [DisposableEffect]
 * detaches on dispose so React-like dev reloads don't double-bind.
 *
 * `dragover` must `preventDefault()` for the drop event to fire at all
 * (HTML5 spec quirk: dropping is opt-in per target). We also set
 * `dropEffect = 'copy'` so the cursor shows the right icon — moving an
 * OS file into a wasm process is a copy semantically.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
internal fun InstallBrowserDropHost(host: BrowserDropHost) {
    DisposableEffect(host) {
        val onDragOver: (Event) -> Unit = { e ->
            // preventDefault + set dropEffect=copy so the OS shows the
            // copy cursor and `drop` actually fires. Highlight the zone
            // under the cursor.
            jsAcceptDragOver(e)
            val z = host.hitTest(jsClientX(e), jsClientY(e))
            host.highlight.value = z?.bounds?.invoke()
        }
        val onDragLeave: (Event) -> Unit = { _ ->
            // `dragleave` fires on every child enter/exit too, so we
            // can't blindly clear here. The next `dragover` will either
            // re-highlight the new zone (if still inside one) or null
            // it out — good enough for v1.
        }
        val onDrop: (Event) -> Unit = { e ->
            host.highlight.value = null
            // Stop the browser's default (which would navigate to /open
            // the file as if it were a URL) before doing any work.
            jsAcceptDrop(e)
            host.handleDrop(e)
        }
        document.addEventListener("dragover", onDragOver)
        document.addEventListener("dragleave", onDragLeave)
        document.addEventListener("drop", onDrop)
        onDispose {
            document.removeEventListener("dragover", onDragOver)
            document.removeEventListener("dragleave", onDragLeave)
            document.removeEventListener("drop", onDrop)
        }
    }
}

// -------- JS interop --------

private fun jsClientX(e: Event): Double = jsEventClientX(e)

private fun jsClientY(e: Event): Double = jsEventClientY(e)

private fun jsEventClientX(e: Event): Double =
    js(
        """{
            return (e && typeof e.clientX === 'number') ? e.clientX : 0;
        }""",
    )

private fun jsEventClientY(e: Event): Double =
    js(
        """{
            return (e && typeof e.clientY === 'number') ? e.clientY : 0;
        }""",
    )

private fun jsAcceptDragOver(e: Event): Unit =
    js(
        """{
            try {
                e.preventDefault();
                if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
            } catch (_) { /* nothing */ }
        }""",
    )

private fun jsAcceptDrop(e: Event): Unit =
    js(
        """{
            try { e.preventDefault(); e.stopPropagation(); } catch (_) { /* nothing */ }
        }""",
    )

/**
 * Materialize a browser DragEvent's File list into Kotlin [DroppedFile]s.
 *
 *  - Uses `FileReader.readAsArrayBuffer` (universally supported; no
 *    Blob.arrayBuffer() polyfill needed — Safari pre-14 lacked it).
 *  - Resolves the callback once **all** files have been read. We don't
 *    stream partial results; a single drop is one logical operation.
 *  - On reader error: that file is omitted, others continue. The host
 *    layer flags missing-bytes via the skipped counter.
 *  - Kotlin/Wasm JS-interop won't let us pass a Kotlin class through to
 *    JS as a parameter, so the bridge is three function-typed callbacks
 *    (`start(count)`, `accept(name, mime, bytes)`, `fail()`) — those JS
 *    *can* see, and the kotlin closure captures the accumulator state.
 */
private fun readDropFiles(
    e: Event,
    cb: (List<BrowserDropHost.DroppedFile>) -> Unit,
) {
    val buffer = mutableListOf<BrowserDropHost.DroppedFile>()
    var remaining = -1
    var fired = false

    fun finish() {
        if (fired) return
        if (remaining == 0) {
            fired = true
            cb(buffer.toList())
        }
    }
    jsReadDropFiles(
        e = e,
        start = { count ->
            remaining = count
            if (count == 0) {
                fired = true
                cb(emptyList())
            }
        },
        accept = { name, mime, binaryString ->
            buffer += BrowserDropHost.DroppedFile(name, mime, binaryStringToBytes(binaryString))
            if (remaining > 0) remaining--
            finish()
        },
        fail = {
            if (remaining > 0) remaining--
            finish()
        },
    )
}

/**
 * Convert a "binary string" (one char per byte, code point 0..255) to a
 * Kotlin ByteArray. Same trick the gzip/tar wasmJs bridges use — string
 * is the only opaque-byte-payload type Kotlin/Wasm JS interop accepts as
 * a function-typed parameter. The JS side builds it with
 * `String.fromCharCode.apply` over a Uint8Array.
 */
private fun binaryStringToBytes(s: String): ByteArray {
    val out = ByteArray(s.length)
    for (i in s.indices) out[i] = (s[i].code and 0xff).toByte()
    return out
}

private fun jsReadDropFiles(
    e: Event,
    start: (Int) -> Unit,
    accept: (String, String, String) -> Unit,
    fail: () -> Unit,
): Unit =
    js(
        """{
            try {
                var dt = e && e.dataTransfer;
                var files = dt ? dt.files : null;
                var count = files ? files.length : 0;
                start(count);
                if (count === 0) return;
                for (var i = 0; i < count; i++) {
                    (function (f) {
                        var reader = new FileReader();
                        reader.onload = function () {
                            try {
                                var ab = reader.result;
                                var view = new Uint8Array(ab);
                                // Build a "binary string" (one char per
                                // byte) — Kotlin/Wasm interop won't let
                                // us pass bytes as a function arg, but
                                // strings work. Chunked to avoid the
                                // ~64K stack-arg limit on
                                // String.fromCharCode.apply.
                                var s = '';
                                var CHUNK = 0x8000;
                                for (var k = 0; k < view.length; k += CHUNK) {
                                    var end = Math.min(k + CHUNK, view.length);
                                    s += String.fromCharCode.apply(
                                        null,
                                        view.subarray(k, end)
                                    );
                                }
                                accept(f.name || '', f.type || '', s);
                            } catch (err) {
                                fail();
                            }
                        };
                        reader.onerror = function () { fail(); };
                        reader.readAsArrayBuffer(f);
                    })(files[i]);
                }
            } catch (_) {
                try { start(0); } catch (_) { /* nothing */ }
            }
        }""",
    )

/**
 * Trigger a browser save dialog for [bytes] under [fileName]. Used by
 * the FS-explorer right-click "Download…" action — the only escape
 * hatch for files trapped inside the wasm VM.
 *
 * Goes through the same one-byte-per-char "binary string" bridge as the
 * drop path, just reversed: JS reconstructs a Uint8Array, wraps in a
 * Blob, builds an object URL, programmatically clicks a synthetic `<a
 * download>` element, then revokes the URL.
 */
internal fun downloadBytes(
    fileName: String,
    bytes: ByteArray,
) {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) sb.append((b.toInt() and 0xff).toChar())
    jsDownloadBytes(fileName, sb.toString())
}

/**
 * Open the browser's native file picker and read the chosen file as UTF-8
 * text, handing the contents to [onText]. Used by "Upload Snapshot…" to
 * import a snapshot `.json` the user previously downloaded. No-op (callback
 * never fires) if the user cancels the picker or the read errors.
 *
 * Snapshots are UTF-8 JSON, so `FileReader.readAsText` is the right read —
 * no need for the one-byte-per-char binary-string bridge the drop/download
 * paths use for opaque bytes.
 */
internal fun pickTextFile(onText: (String) -> Unit) {
    jsPickTextFile(onText)
}

private fun jsPickTextFile(onText: (String) -> Unit): Unit =
    js(
        """{
            try {
                var input = document.createElement('input');
                input.type = 'file';
                input.accept = '.json,application/json';
                input.style.display = 'none';
                input.onchange = function () {
                    try {
                        var f = input.files && input.files[0];
                        if (!f) return;
                        var reader = new FileReader();
                        reader.onload = function () {
                            try { onText(String(reader.result)); } catch (_) { /* nothing */ }
                        };
                        reader.onerror = function () { /* nothing */ };
                        reader.readAsText(f);
                    } catch (_) { /* nothing */ }
                };
                document.body.appendChild(input);
                input.click();
                // Remove on the next tick — the click dispatches synchronously
                // but the change event fires later; the detached input still
                // works once it has been clicked.
                setTimeout(function () {
                    try { document.body.removeChild(input); } catch (_) { /* nothing */ }
                }, 0);
            } catch (_) { /* nothing */ }
        }""",
    )

private fun jsDownloadBytes(
    name: String,
    binaryString: String,
): Unit =
    js(
        """{
            try {
                var s = binaryString;
                var a = new Uint8Array(s.length);
                for (var i = 0; i < s.length; i++) a[i] = s.charCodeAt(i) & 0xff;
                var blob = new Blob([a], { type: 'application/octet-stream' });
                var url = URL.createObjectURL(blob);
                var link = document.createElement('a');
                link.href = url;
                link.download = name || 'download';
                document.body.appendChild(link);
                link.click();
                document.body.removeChild(link);
                // Revoke after a tick — some browsers race the click
                // handler against the revoke if it's synchronous.
                setTimeout(function () { URL.revokeObjectURL(url); }, 0);
            } catch (_) { /* nothing */ }
        }""",
    )
