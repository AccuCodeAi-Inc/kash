@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.accucodeai.kash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.fs.sanitizeDropName
import com.accucodeai.kash.fs.uniqueDropPath
import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import kotlin.math.floor

/**
 * Single-pane terminal app for the wasmJs build.
 *
 * Rendering: a 2D cell grid driven by a VT100/xterm parser. See
 * [TerminalCanvas] for the pixel-level paint loop and the measurement
 * pass that derives `(cols, rows)` from the available area.
 *
 * Input: keystrokes captured at the DOM level (`KeyboardEvent.key`) to
 * sidestep Compose Multiplatform's wasmJs key mis-mappings. Mouse and
 * wheel events also DOM-level. Cmd/Ctrl+C copies the active selection
 * to the clipboard.
 *
 * Scrolling: bounded scrollback owned by [ComposeTerminal]; the
 * renderer tracks a view position (rows above the live bottom) here.
 * Mouse wheel and PgUp/PgDn nudge it; any non-navigation keystroke
 * while scrolled snaps back to live.
 *
 * Selection: mouse-drag selects a rectangular character range across
 * scrollback + live viewport. Visible as a translucent overlay.
 * Cmd/Ctrl+C copies the selection to the system clipboard. Esc or any
 * non-modifier keystroke clears the selection.
 *
 * Cursor: 500 ms blink loop. Reset to "on" on each user keystroke so
 * the cursor doesn't blink-off immediately after typing.
 */
@Composable
@Suppress("ktlint:standard:function-naming")
public fun KashTerminalApp(
    runner: KashSessionRunner,
    modifier: Modifier = Modifier,
    dropHost: BrowserDropHost? = null,
    inputEnabled: Boolean = true,
) {
    val snapshot by runner.terminal.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Scroll position as a fractional rows-above-live offset, for smooth
    // sub-row scrolling. `scrollTarget` is where the user wants to be;
    // `scrollAnim` is the rendered offset that eases toward it (very minor
    // ~90 ms inertia so a discrete wheel notch glides instead of snapping).
    var scrollTarget by remember { mutableStateOf(0f) }
    val scrollAnim = remember { Animatable(0f) }

    // Drive the rendered offset toward `target`. `snap = true` jumps with no
    // glide (typing, paste, re-anchor); otherwise the short tween animates.
    // Launched on the composition scope so DOM event callbacks (which aren't
    // suspend) can trigger it.
    fun scrollTo(
        target: Float,
        snap: Boolean,
    ) {
        val clamped = target.coerceIn(0f, snapshot.scrollback.size.toFloat())
        scrollTarget = clamped
        scope.launch {
            if (snap) {
                scrollAnim.snapTo(clamped)
            } else {
                scrollAnim.animateTo(clamped, tween(durationMillis = 90, easing = LinearOutSlowInEasing))
            }
        }
    }

    // Track the absolute "total rows ever pushed to scrollback" we last
    // saw so we can re-anchor a scrolled-up view on stable content when
    // new output rotates the ring. Without this fix, the user's view
    // drifts toward the live edge every time a new line scrolls in —
    // and once the ring hits its cap, what they're looking at silently
    // changes underneath them. Same trick xterm.js / WezTerm / ghostty
    // use (scroll position is anchored on an absolute line ID, not a
    // ring offset).
    var lastTotalAdded by remember { mutableStateOf(0L) }

    // Cell metrics surface up from the renderer so we can translate mouse
    // pixel coords into cell (row, col).
    var cellW by remember { mutableStateOf(1f) }
    var cellH by remember { mutableStateOf(1f) }

    // Canvas top-left (CSS pixels, window-relative). Subtracted from
    // `clientX/clientY` in selection math so a TopAppBar or sidebar
    // doesn't offset which cell the click lands on. Without this, the
    // multi-pane workspace's 64dp app bar shifts every drag down by
    // ~3 rows.
    var canvasOriginX by remember { mutableStateOf(0f) }
    var canvasOriginY by remember { mutableStateOf(0f) }

    // Font size, in sp. Cmd/Ctrl + and Cmd/Ctrl - bump it; Cmd/Ctrl 0
    // resets. Compose re-measures the cell on each layout, so this also
    // re-derives cols/rows for free — the shell sees a SIGWINCH-like
    // resize on the next prompt redraw.
    var fontSizeSp by remember { mutableStateOf(13) }

    // Cursor blink: toggles every 500 ms; reset to true on each keystroke.
    var cursorBlinkOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            cursorBlinkOn = !cursorBlinkOn
        }
    }

    // Selection state.
    var selection by remember { mutableStateOf<SelectionRange?>(null) }
    var selecting by remember { mutableStateOf(false) }

    LaunchedEffect(runner) { runner.start() }

    LaunchedEffect(snapshot.scrollbackTotalAdded, snapshot.onAlt) {
        if (snapshot.onAlt) {
            scrollTarget = 0f
            scrollAnim.snapTo(0f)
            selection = null
            lastTotalAdded = snapshot.scrollbackTotalAdded
            return@LaunchedEffect
        }
        val delta = snapshot.scrollbackTotalAdded - lastTotalAdded
        lastTotalAdded = snapshot.scrollbackTotalAdded
        val maxOff = snapshot.scrollback.size.toFloat()
        if (delta > 0 && scrollTarget > 0f) {
            // User is scrolled up; advance the view-from-bottom by the
            // number of newly-pushed rows so the visible content stays
            // anchored on the same line even when the ring rotates. Snap
            // (no animation) so the anchored content doesn't visibly drift.
            scrollTarget = (scrollTarget + delta.toInt()).coerceAtMost(maxOff)
            scrollAnim.snapTo((scrollAnim.value + delta.toInt()).coerceAtMost(maxOff))
        } else if (scrollTarget > maxOff) {
            scrollTarget = maxOff
            scrollAnim.snapTo(maxOff)
        }
    }

    // The terminal grabs keyboard/mouse/wheel at the document level (to dodge
    // CMP-wasm key mis-mapping). Everything — terminal and dialogs — shares one
    // canvas, so those global listeners would otherwise swallow input meant for
    // a modal. Gating each effect on `inputEnabled` simply unregisters them
    // while a dialog is open, which is cleaner than checking a flag per event.
    DisposableEffect(runner, inputEnabled) {
        if (!inputEnabled) return@DisposableEffect onDispose { }
        val handler: (Event) -> Unit = { rawEvent ->
            val event = rawEvent as KeyboardEvent
            // Copy: Cmd/Ctrl+C with a non-empty selection → clipboard,
            // and DO NOT forward (we'd otherwise send SIGINT). Match
            // event.code too — same robustness reason as paste below.
            val isCopy =
                (event.ctrlKey || event.metaKey) &&
                    !event.altKey && !event.shiftKey &&
                    (event.key.lowercase() == "c" || event.code == "KeyC")
            // Cmd/Ctrl + "+" / "-" / "0" — font size shortcuts. Match
            // browser zoom muscle memory. Plus / Equal keys both
            // produce "+" or "=" depending on Shift; accept both. We
            // never forward these to the shell.
            val isMod = event.ctrlKey || event.metaKey
            val isZoomKey =
                isMod && !event.altKey &&
                    (event.key == "+" || event.key == "=" || event.key == "-" || event.key == "_" || event.key == "0")
            if (isZoomKey) {
                fontSizeSp =
                    when (event.key) {
                        "+", "=" -> (fontSizeSp + 1).coerceAtMost(48)
                        "-", "_" -> (fontSizeSp - 1).coerceAtLeast(8)
                        "0" -> 13
                        else -> fontSizeSp
                    }
                event.preventDefault()
                event.stopPropagation()
            } else if (isCopy && selection != null) {
                val text =
                    runner.terminal.selectionText(
                        selection!!.startAbsRow,
                        selection!!.startCol,
                        selection!!.endAbsRow,
                        selection!!.endCol,
                    )
                writeToClipboard(text)
                event.preventDefault()
                event.stopPropagation()
            } else if ((event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey &&
                (event.key.lowercase() == "v" || event.code == "KeyV")
            ) {
                // Cmd/Ctrl+V — let it fall through to the browser. The
                // browser's native paste action dispatches a `paste`
                // event on the focused element, which our document-level
                // paste listener catches synchronously via
                // `clipboardData.getData('text/plain')`. No permission
                // prompt because the event itself IS the user gesture.
                //
                // Deliberately do NOT call
                // `navigator.clipboard.readText()` here. On macOS 15.4+
                // that API triggers a single-item "Paste" privacy popup
                // whenever it isn't called from a paste-UI gesture
                // (Cmd+V doesn't count). Also deliberately do NOT
                // preventDefault — that would suppress the browser's
                // paste-event dispatch and leave us with nothing.
            } else {
                val key = browserKeyToKash(event)
                if (key != null) {
                    cursorBlinkOn = true
                    if (key == Key.Named.ESC && selection != null) {
                        selection = null
                        event.preventDefault()
                        event.stopPropagation()
                    } else {
                        val handledByScroll =
                            handleScrollKey(key, snapshot.scrollback.size, snapshot.onAlt) {
                                scrollTo(it, false)
                            }
                        if (!handledByScroll) {
                            // Typing snaps back to live instantly (no glide).
                            if (scrollTarget > 0f && !isNavigationKey(key)) scrollTo(0f, true)
                            // Any non-navigation keystroke also clears any
                            // active selection — matches xterm.
                            if (selection != null && !isNavigationKey(key)) selection = null
                            runner.feedKey(key)
                        }
                        event.preventDefault()
                        event.stopPropagation()
                    }
                }
            }
        }
        // Capture phase: fire BEFORE the focused Compose widget (a
        // Material3 Button that's been clicked, an open DropdownMenu)
        // can absorb the keystroke. Without this, Space / Enter activate
        // the most-recently-clicked workspace button instead of reaching
        // the terminal. preventDefault + stopPropagation then halts
        // further dispatch.
        document.addEventListener("keydown", handler, true)
        onDispose { document.removeEventListener("keydown", handler, true) }
    }

    DisposableEffect(runner, inputEnabled) {
        if (!inputEnabled) return@DisposableEffect onDispose { }
        val wheel: (Event) -> Unit = { rawEvent ->
            if (!snapshot.onAlt) {
                val event = rawEvent as WheelEvent
                val deltaY = event.deltaY
                if (deltaY != 0.0) {
                    // Normalize the browser's wheel delta to a fractional row
                    // count. `deltaMode` differs across browsers — Chrome
                    // sends pixels (≈100/notch), Firefox sends lines (≈1-3),
                    // page mode sends viewport pages. Convert each to rows
                    // with NO rounding so sub-row deltas scroll smoothly.
                    val cellPx = cellH.toDouble().coerceAtLeast(1.0)
                    val deltaRows =
                        when (event.deltaMode) {
                            1 -> deltaY

                            // DOM_DELTA_LINE: already in lines
                            2 -> deltaY * snapshot.rows

                            // DOM_DELTA_PAGE
                            else -> deltaY / cellPx // DOM_DELTA_PIXEL
                        }
                    // Wheel-up (deltaY < 0) scrolls into history (offset grows).
                    scrollTo((scrollTarget.toDouble() - deltaRows).toFloat(), false)
                }
            }
        }
        document.addEventListener("wheel", wheel)
        onDispose { document.removeEventListener("wheel", wheel) }
    }

    // Native browser `paste` event fallback. Some macOS Chrome layouts
    // swallow Cmd+V at keydown level (the OS / browser routes it to the
    // Edit-menu paste handler before our keydown sees it), but a `paste`
    // event still fires on document. Catch it, pull `text/plain` out of
    // clipboardData, and deliver as Key.Paste — same code path as the
    // keydown branch.
    DisposableEffect(runner, inputEnabled) {
        if (!inputEnabled) return@DisposableEffect onDispose { }
        val onPaste: (Event) -> Unit = { rawEvent ->
            val text = jsExtractPasteText(rawEvent)
            if (text.isNotEmpty()) {
                if (scrollTarget > 0f) scrollTo(0f, true)
                if (selection != null) selection = null
                runner.feedKey(Key.Paste(text))
            }
            rawEvent.preventDefault()
            rawEvent.stopPropagation()
        }
        document.addEventListener("paste", onPaste)
        onDispose { document.removeEventListener("paste", onPaste) }
    }

    // Right-click → "smart paste": copy if there's an active selection,
    // otherwise paste from the clipboard. Linux/X11 terminal convention
    // (xterm, Konsole, gnome-terminal all do some variant of this). We
    // suppress the browser's native context menu so paste actually
    // happens in the terminal instead of opening "Save As…".
    DisposableEffect(runner, inputEnabled) {
        if (!inputEnabled) return@DisposableEffect onDispose { }
        val onContext: (Event) -> Unit = { rawEvent ->
            val e = rawEvent as MouseEvent
            // Only intercept right-clicks that land inside the terminal
            // canvas. Outside the canvas (file-explorer sidebar, top app
            // bar, padding gutter) we let the browser's native context
            // menu through so the user can right-click those surfaces
            // normally.
            val canvasW = cellW.toDouble() * snapshot.cols
            val canvasH = cellH.toDouble() * snapshot.rows
            val x = e.clientX.toDouble()
            val y = e.clientY.toDouble()
            val inside =
                x >= canvasOriginX && x < canvasOriginX + canvasW &&
                    y >= canvasOriginY && y < canvasOriginY + canvasH
            if (inside) {
                e.preventDefault()
                e.stopPropagation()
                val sel = selection
                if (sel != null) {
                    val text =
                        runner.terminal.selectionText(
                            sel.startAbsRow,
                            sel.startCol,
                            sel.endAbsRow,
                            sel.endCol,
                        )
                    writeToClipboard(text)
                    selection = null
                } else {
                    readClipboardThen { text ->
                        if (text.isNotEmpty()) {
                            if (scrollTarget > 0f) scrollTo(0f, true)
                            runner.feedKey(Key.Paste(text))
                        }
                    }
                }
            }
        }
        // Capture phase so we beat any descendant listener that might
        // stopPropagation before bubble — without this, the browser's
        // native context menu can slip through on certain element
        // hierarchies.
        document.addEventListener("contextmenu", onContext, true)
        onDispose { document.removeEventListener("contextmenu", onContext, true) }
    }

    // Mouse drag → selection.
    DisposableEffect(runner, inputEnabled) {
        if (!inputEnabled) return@DisposableEffect onDispose { }

        fun toAbsCell(e: MouseEvent): Pair<Int, Int> {
            val localX = (e.clientX - canvasOriginX.toDouble()).coerceAtLeast(0.0)
            val localY = (e.clientY - canvasOriginY.toDouble()).coerceAtLeast(0.0)
            var col = floor(localX / cellW.toDouble()).toInt().coerceAtLeast(0)
            val snap = snapshot
            // Must mirror drawTerminal's viewport window exactly, otherwise
            // the selection anchor lands on the wrong absolute row. The
            // integer part `n` picks the window (two regimes below); the
            // fractional part shifts drawn content down by frac*cellH, so we
            // subtract it from the pixel→row conversion or the picked row is
            // off by the sub-row shift.
            //   n == 0 → bottom-anchor `vis` at viewport bottom
            //   n  > 0 → slide window n rows up through abs space
            val totalRows = snap.scrollback.size + snap.visible.size
            val offset =
                if (snap.onAlt) 0f else scrollAnim.value.coerceIn(0f, snap.scrollback.size.toFloat())
            val n = floor(offset).toInt()
            val frac = offset - n
            val viewRow = floor(localY / cellH.toDouble() - frac).toInt()
            val firstAbsRow =
                if (n == 0) {
                    totalRows - snap.rows
                } else {
                    (totalRows - n - snap.rows).coerceAtLeast(0)
                }
            val absRow = (firstAbsRow + viewRow).coerceIn(0, totalRows - 1)
            // Snap to leader on wide chars: a click on the right half of
            // a wide glyph lands in the continuation cell (width=0) by
            // pixel math, but the user means to select the whole char.
            // Resolving to the leader here keeps the saved selection
            // range on grid-aligned, atomic positions — selection tint
            // and copy both expect that.
            val rowCells =
                when {
                    absRow < snap.scrollback.size -> snap.scrollback.getOrNull(absRow)
                    else -> snap.visible.getOrNull(absRow - snap.scrollback.size)
                }
            if (rowCells != null && col in rowCells.indices &&
                rowCells[col].width == 0 && col > 0
            ) {
                col--
            }
            return absRow to col
        }
        val onDown: (Event) -> Unit = { rawEvent ->
            val e = rawEvent as MouseEvent
            if (e.button == 0.toShort()) {
                val (r, c) = toAbsCell(e)
                selection = SelectionRange(r, c, r, c)
                selecting = true
            }
        }
        val onMove: (Event) -> Unit = { rawEvent ->
            if (selecting) {
                val e = rawEvent as MouseEvent
                val (r, c) = toAbsCell(e)
                val s = selection
                if (s != null) selection = s.copy(endAbsRow = r, endCol = c)
            }
        }
        val onUp: (Event) -> Unit = { rawEvent ->
            val e = rawEvent as MouseEvent
            if (e.button == 0.toShort() && selecting) {
                selecting = false
                // If user just clicked (no drag), clear the selection.
                val s = selection
                if (s != null && s.startAbsRow == s.endAbsRow && s.startCol == s.endCol) {
                    selection = null
                }
            }
        }
        document.addEventListener("mousedown", onDown)
        document.addEventListener("mousemove", onMove)
        document.addEventListener("mouseup", onUp)
        onDispose {
            document.removeEventListener("mousedown", onDown)
            document.removeEventListener("mousemove", onMove)
            document.removeEventListener("mouseup", onUp)
        }
    }

    // File-drop zone over the terminal canvas. Only registered when a
    // drop host is wired in (i.e. when called from the workspace).
    if (dropHost != null) {
        DisposableEffect(dropHost, runner) {
            val id =
                dropHost.register(
                    bounds = {
                        BrowserDropHost.DropRect(
                            x = canvasOriginX.toDouble(),
                            y = canvasOriginY.toDouble(),
                            width = cellW.toDouble() * snapshot.cols.coerceAtLeast(0),
                            height = cellH.toDouble() * snapshot.rows.coerceAtLeast(0),
                        )
                    },
                    onDrop = { files, _, _ ->
                        handleTerminalDrop(runner, files)
                    },
                )
            onDispose { dropHost.unregister(id) }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(BACKGROUND)
                .padding(start = 8.dp),
    ) {
        TerminalCanvas(
            snapshot = snapshot,
            scrollOffsetRows = scrollAnim.value,
            cursorBlinkOn = cursorBlinkOn,
            selection = selection,
            fontSizeSp = fontSizeSp,
            background = BACKGROUND,
            foreground = FOREGROUND,
            onCellSize = { cols, rows -> runner.resizeViewport(cols, rows) },
            onCellMetrics = { w, h ->
                cellW = w
                cellH = h
            },
            onCanvasOrigin = { x, y ->
                canvasOriginX = x
                canvasOriginY = y
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun handleScrollKey(
    key: Key,
    scrollbackSize: Int,
    onAlt: Boolean,
    setOffset: (Float) -> Unit,
): Boolean {
    if (onAlt) return false
    return when (key) {
        Key.Named.PGUP -> {
            setOffset(10f.coerceAtMost(scrollbackSize.toFloat()))
            true
        }

        Key.Named.PGDN -> {
            setOffset(0f)
            true
        }

        else -> {
            false
        }
    }
}

private fun isNavigationKey(key: Key): Boolean =
    when (key) {
        Key.Named.PGUP, Key.Named.PGDN, Key.Named.HOME, Key.Named.END,
        Key.Named.UP, Key.Named.DOWN, Key.Named.LEFT, Key.Named.RIGHT,
        -> true

        else -> false
    }

private fun browserKeyToKash(event: KeyboardEvent): Key? {
    val k = event.key
    when (k) {
        "Enter" -> return Key.Named.ENTER
        "Backspace" -> return Key.Named.BACKSPACE
        "Delete" -> return Key.Named.DELETE
        "Tab" -> return Key.Named.TAB
        "Escape" -> return Key.Named.ESC
        "ArrowUp" -> return Key.Named.UP
        "ArrowDown" -> return Key.Named.DOWN
        "ArrowLeft" -> return Key.Named.LEFT
        "ArrowRight" -> return Key.Named.RIGHT
        "Home" -> return Key.Named.HOME
        "End" -> return Key.Named.END
        "PageUp" -> return Key.Named.PGUP
        "PageDown" -> return Key.Named.PGDN
    }
    if (k.length in 2..3 && k.startsWith("F")) {
        val n = k.substring(1).toIntOrNull()
        if (n != null && n in 1..12) return Key.Function(n)
    }
    if (k.length > 1) return null
    val ch = k[0]
    if (event.ctrlKey && !event.metaKey && !event.altKey) {
        val upper = ch.uppercaseChar()
        if (upper in 'A'..'Z') return Key.Ctrl(upper)
    }
    if (event.metaKey || event.altKey) return null
    if (ch.code < 0x20) return null
    return Key.Char(ch.code)
}

internal fun writeToClipboard(text: String) {
    jsWriteClipboard(text)
}

/**
 * Read the system clipboard asynchronously and invoke [callback] with the
 * text (empty string on any failure — no clipboard, permission denied,
 * unsupported MIME). The async-callback shape is necessary because
 * `navigator.clipboard.readText()` returns a Promise; there's no
 * synchronous path on the modern API.
 */
private fun readClipboardThen(callback: (String) -> Unit) {
    jsReadClipboard { text -> callback(text) }
}

// Pull `text/plain` out of a browser ClipboardEvent's dataTransfer. Used
// by the `paste` event fallback path. Returns empty string when the event
// has no clipboardData (older browsers) or no text MIME.
private fun jsExtractPasteText(e: Event): String = jsClipboardEventText(e)

private fun jsClipboardEventText(e: Event): String =
    js(
        """{
            try {
                if (e && e.clipboardData && typeof e.clipboardData.getData === 'function') {
                    return e.clipboardData.getData('text/plain') || '';
                }
            } catch (_) { /* fall through */ }
            return '';
        }""",
    )

private fun jsReadClipboard(cb: (String) -> Unit): Unit =
    js(
        """{
            try {
                if (navigator && navigator.clipboard && navigator.clipboard.readText) {
                    navigator.clipboard.readText()
                        .then(function(t) { cb(t || ''); })
                        .catch(function() { cb(''); });
                    return;
                }
            } catch (_) { /* fall through */ }
            cb('');
        }""",
    )

// navigator.clipboard.writeText only works when the document has user
// activation AND focus — if the user clicked into dev tools (or focus
// otherwise drifted) the promise rejects with "Clipboard write is not
// allowed", which surfaces as an uncaught error in the console. Catch
// the rejection and fall back to the legacy textarea + execCommand
// path, which doesn't require the focus check.
private fun jsWriteClipboard(text: String): Unit =
    js(
        """{
            try {
                if (navigator && navigator.clipboard && document.hasFocus()) {
                    var p = navigator.clipboard.writeText(text);
                    if (p && typeof p.catch === 'function') {
                        p.catch(function() { fallbackCopy(text); });
                    }
                    return;
                }
            } catch (_) { /* fall through */ }
            fallbackCopy(text);
            function fallbackCopy(s) {
                try {
                    var ta = document.createElement('textarea');
                    ta.value = s;
                    ta.setAttribute('readonly', '');
                    ta.style.position = 'fixed';
                    ta.style.opacity = '0';
                    document.body.appendChild(ta);
                    ta.select();
                    document.execCommand('copy');
                    document.body.removeChild(ta);
                } catch (_) { /* give up silently */ }
            }
        }""",
    )

/**
 * Handle a file drop on the terminal canvas. Two code paths depending on
 * whether the foreground process has registered an [com.accucodeai.kash.api.AttachmentSink]:
 *
 *  - **Sink registered** (today: `agent`): write bytes to a per-session
 *    `/tmp/drops/sess-<sid>/<name>` directory, call `sink.add(...)`, and
 *    paste `[attachment N] ` into the prompt via `Key.Paste`. The agent
 *    sees the marker, expands it server-side on submit.
 *  - **No sink**: write bytes to `/tmp/drops/<name>` and paste the path
 *    token so the user can `cat` / `file` / pipe it from a regular
 *    shell prompt.
 *
 * Both paths return a status message for the workspace's flash toast.
 */
private suspend fun handleTerminalDrop(
    runner: KashSessionRunner,
    files: List<BrowserDropHost.DroppedFile>,
): BrowserDropHost.DropOutcome {
    if (files.isEmpty()) {
        return BrowserDropHost.DropOutcome(message = "Drop contained no files.")
    }
    val fs = runner.machineFs
    val sink = runner.findAttachmentSink()
    val targetDir =
        if (sink != null) "/tmp/drops/sess-${runner.sid}" else "/tmp/drops"
    try {
        if (!fs.exists(targetDir)) fs.mkdirs(targetDir)
    } catch (_: Throwable) {
    }
    if (sink != null) {
        // Sink-aware path: agent surfaces these as [attachment N]. We
        // paste the marker as a single bracketed-paste chunk so it lands
        // in the line editor verbatim, then a trailing space so the
        // user keeps typing naturally.
        val sb = StringBuilder()
        var attached = 0
        for (f in files) {
            val safe = sanitizeDropName(f.name)
            val finalPath = uniqueDropPath(fs, targetDir, safe)
            try {
                fs.writeBytes(finalPath, f.bytes)
                val mime = f.mimeType.ifEmpty { "application/octet-stream" }
                val n = sink.add(finalPath, mime, f.bytes.size.toLong(), safe)
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append("[attachment ").append(n).append(']')
                attached++
            } catch (_: Throwable) {
            }
        }
        if (sb.isNotEmpty()) {
            sb.append(' ')
            runner.feedKey(Key.Paste(sb.toString()))
        }
        return BrowserDropHost.DropOutcome(
            message = "Attached $attached file(s) to the foreground command.",
        )
    }
    // Generic path-paste fallback.
    val sb = StringBuilder()
    var written = 0
    for (f in files) {
        val safe = sanitizeDropName(f.name)
        val finalPath = uniqueDropPath(fs, targetDir, safe)
        try {
            fs.writeBytes(finalPath, f.bytes)
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(finalPath)
            written++
        } catch (_: Throwable) {
        }
    }
    if (sb.isNotEmpty()) {
        sb.append(' ')
        runner.feedKey(Key.Paste(sb.toString()))
    }
    return BrowserDropHost.DropOutcome(
        message = "Wrote $written file(s) to $targetDir and pasted path(s).",
    )
}

private val BACKGROUND = Color(0xFF0B0B0B)
private val FOREGROUND = Color(0xFFEAEAEA)
