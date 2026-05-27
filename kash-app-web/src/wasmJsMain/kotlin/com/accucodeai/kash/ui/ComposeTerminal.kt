package com.accucodeai.kash.ui

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.terminal.TerminalSize
import kotlinx.browser.window
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.coroutines.resume

/**
 * The wasmJs analog of `PosixTerminalControl`. Drives a 2D [TerminalGrid]
 * via [AnsiInterpreter] for output, and routes browser keystrokes through
 * a single split-channel model — same architecture as the JVM tty pump.
 *
 * Output (`feedBytes` / [write]) flows through the parser into the grid;
 * a new [Snapshot] is published after each batch for Compose to observe.
 *
 * Input has two consumers:
 *  - the line editor, which calls [readKey] expecting decoded [Key]s
 *  - tools reading `ctx.stdin`, which call into [cookedByteSource] for raw
 *    bytes (cooked-mode echo + EOF on Ctrl-D)
 *
 * Cooked vs raw is gated by [rawCount] — incremented by [enterRawMode],
 * decremented by [exitRawMode]. When raw, no bytes flow into the cooked
 * channel; tools blocked on stdin simply stay blocked while a TUI like
 * nano owns the keyboard.
 */
public class ComposeTerminal : TerminalControl {
    private val grid: TerminalGrid = TerminalGrid(initialCols = 80, initialRows = 24)
    private val parser: AnsiInterpreter = AnsiInterpreter(grid)

    private val _state: MutableStateFlow<Snapshot> = MutableStateFlow(snapshotNow())
    public val state: StateFlow<Snapshot> get() = _state.asStateFlow()

    private val keyChannel: Channel<Key> = Channel(capacity = Channel.UNLIMITED)
    private val cookedBytes: Channel<Int> = Channel(capacity = Channel.UNLIMITED)

    private var rawCount: Int = 0

    // Cooked-mode line buffer. In a real POSIX tty the kernel's line
    // discipline does this: hold typed bytes until the user hits Enter,
    // editing in place (Backspace, Ctrl-U), echo each char to the display.
    // Without it tools that read line-at-a-time stdin (Python REPL, `cat`,
    // `read`) get no echo and no editing — typing feels broken. Active
    // only when [rawCount] == 0; raw-mode tools see keys directly via
    // [readKey] and own all rendering.
    private val cookedLineBuf: StringBuilder = StringBuilder()

    /**
     * Invoked on Ctrl-C in cooked mode. Wired by [KashSessionRunner] to
     * the machine's foreground signal receiver so SIGINT reaches the
     * running shell / foreground command. May be invoked from any thread
     * (the receiver is expected to be thread-safe; the interpreter's
     * `deliverSignal` is a `Channel.trySend`).
     */
    public var onInterrupt: (() -> Unit)? = null

    /**
     * Called when the viewport changes size. Only alt-screen apps
     * (nano, vim, less, …) get a Ctrl-L: they own their viewport and
     * repaint from their own model, so a clear is the right reflow
     * signal.
     *
     * On the main screen we do NOTHING. Real terminals don't synthesize
     * Ctrl-L on resize — they deliver SIGWINCH and bash's readline
     * redraws its prompt in place. We don't have SIGWINCH plumbing on
     * wasm, but firing Ctrl-L is worse than nothing because:
     *
     *  1. `ESC[2J` (from the line editor's Ctrl-L handler) pushes the
     *     current visible — including the prompt + half-typed input —
     *     into scrollback. Tiny window-drag resize events therefore
     *     stamp N copies of the same prompt line into scrollback.
     *  2. `ESC[H` then homes the cursor and the prompt redraws at row
     *     0, leaving the bottom of a grown viewport empty.
     *
     * The grid is already reshaped in-place by [resizeCells]; the
     * prompt stays at its old row. Cosmetic misalignment on next
     * keystroke is acceptable.
     */
    public fun notifyResizeRedraw() {
        if (grid.onAlt) keyChannel.trySend(Key.Ctrl('L'))
    }

    /** Update the grid's working size in cells (called from the Canvas measurer). */
    public fun resizeCells(
        cols: Int,
        rows: Int,
    ) {
        if (cols <= 0 || rows <= 0) return
        if (cols == grid.cols && rows == grid.rows) return
        grid.resize(cols, rows)
        publish()
    }

    /** Push raw bytes from a tool's stdout / stderr or the line editor. */
    public fun feedBytes(bytes: ByteArray) {
        feedString(bytes.decodeToString())
    }

    /**
     * Per-frame byte budget tracking. The actual throttle lives in
     * [TerminalSink.write] (the stdout/stderr path) where it can suspend
     * the producer on [awaitFrame] — true backpressure. This counter just
     * lets that suspension know when to fire.
     *
     * Why backpressure and not just dropping bytes: `kotlinx.coroutines`
     * `yield()` on Kotlin/Wasm is a microtask, not a macrotask. A tight
     * I/O loop that yields per iteration keeps the microtask queue
     * permanently full, so the browser never reaches the render step nor
     * fires rAF nor delivers DOM events (Ctrl-C). Suspending on rAF inside
     * the sink is the only reliable way to actually let the page breathe.
     *
     * 64 KiB/frame at 60fps ≈ 3.8 MiB/sec — comfortable for any realistic
     * interactive output, hard cap for `cat /dev/random`-class producers.
     */
    internal var bytesThisFrame: Int = 0

    private fun feedString(s: String) {
        bytesThisFrame += s.length
        parser.feed(s)
        publish()
    }

    /**
     * Suspend the caller until the next browser animation frame. Used by
     * [TerminalSink.write] to pace fast producers; also clears the
     * per-frame byte budget so the next batch starts fresh. The rAF
     * callback is a macrotask, which is the only thing on Kotlin/Wasm
     * that reliably yields to the browser's render + input pipeline.
     */
    internal suspend fun awaitFrame() {
        suspendCancellableCoroutine<Unit> { cont ->
            window.requestAnimationFrame {
                bytesThisFrame = 0
                cont.resume(Unit)
            }
        }
    }

    /**
     * Coalesce snapshot publishes to once per animation frame. A fast
     * producer (e.g. `cat /dev/random`) can call [feedString] thousands of
     * times per second; without throttling each call would mutate the
     * StateFlow, Compose would queue a recomposition, and Skiko's render
     * buffer (vertex/glyph atlas uploaded via WebGL `texSubImage2D`) would
     * accumulate faster than the GPU can drain it — eventually exceeding
     * the browser's 2 GiB ArrayBuffer upload limit and crashing the page.
     *
     * Setting `publishPending` schedules a single rAF callback; further
     * mutations within the same frame are folded into the same publish.
     */
    private var publishPending: Boolean = false

    private fun publish() {
        if (publishPending) return
        publishPending = true
        window.requestAnimationFrame {
            publishPending = false
            // Reset the per-frame byte counter here too (not just in
            // awaitFrame) so slow-but-steady output paths — like the line
            // editor printing one prompt — don't leave the counter near
            // the budget at start of frame.
            bytesThisFrame = 0
            _state.value = snapshotNow()
        }
    }

    private fun snapshotNow(): Snapshot {
        // Pass LIVE references — no copy. With long output (Python REPL
        // spitting 1000s of lines, `find /`, etc.) copying the full
        // scrollback into every snapshot is O(N²) cumulative cell-copies
        // and was driving Skiko's vertex buffer past 2 GB. The grid only
        // mutates from the single wasmJs thread, and Compose's paint
        // doesn't yield mid-frame, so reading live arrays is safe.
        // [seq] forces equals() to differ on every emit so Compose's
        // StateFlow recomposes.
        return Snapshot(
            seq = nextSnapshotSeq(),
            cols = grid.cols,
            rows = grid.rows,
            visible = grid.activeBuffer(),
            scrollback = grid.scrollback,
            scrollbackTotalAdded = grid.scrollbackTotalAdded,
            cursorRow = grid.cursorRow,
            cursorCol = grid.cursorCol,
            cursorVisible = grid.cursorVisible,
            onAlt = grid.onAlt,
        )
    }

    // -------- TerminalControl impl --------

    override suspend fun enterRawMode() {
        rawCount++
        // Drain raw-input keys queued during the cooked-mode window —
        // they were already echoed and (where relevant) flushed into
        // cookedBytes, so handing them to the line editor on top of
        // that would double-process them (e.g. a Ctrl-D pressed in the
        // Python REPL would re-fire as EOF in the freshly-resumed kash
        // REPL, exiting the shell).
        //
        // BUT keep Key.Paste / Key.PrintAbove: those skip cookedModeFeedKey
        // (see feedKey()) and live only in keyChannel, so they were never
        // "double-processed" — they're out-of-band events queued
        // specifically for the next raw consumer. Drag-and-drop into
        // the agent during a tool-call window depends on this: the
        // drop's PrintAbove banner + Paste of `[attachment N]` need to
        // survive until the next readLine.
        val keep = mutableListOf<Key>()
        while (true) {
            val r = keyChannel.tryReceive()
            if (!r.isSuccess) break
            val k = r.getOrNull() ?: continue
            if (k is Key.Paste || k is Key.PrintAbove) keep += k
        }
        for (k in keep) keyChannel.trySend(k)
    }

    override suspend fun exitRawMode() {
        if (rawCount > 0) rawCount--
    }

    override suspend fun useAlternateScreen(enable: Boolean) {
        feedString(if (enable) "[?1049h" else "[?1049l")
    }

    override fun size(): TerminalSize = TerminalSize(cols = grid.cols, rows = grid.rows)

    override suspend fun readKey(): Key = keyChannel.receive()

    override fun pushKey(key: Key) {
        // Bypass the rawCount routing of feedKey() — out-of-band keys
        // (Key.PrintAbove from a drop handler) need to land in the raw
        // queue every time, since their only consumer is the line
        // editor's readKey loop. Falling through cookedModeFeedKey
        // would either drop the event or echo garbage.
        keyChannel.trySend(key)
    }

    override suspend fun write(s: String) {
        feedString(s)
    }

    override suspend fun flush() {}

    /**
     * Extract the text inside a selection rectangle. Both endpoints are
     * `(absRow, col)` in *absolute row space* — scrollback rows numbered
     * 0..scrollback.size-1, then visible rows immediately after. Rows are
     * concatenated with `\n` between them, trailing spaces trimmed.
     * Caller is responsible for normalizing start/end ordering.
     */
    public fun selectionText(
        startAbsRow: Int,
        startCol: Int,
        endAbsRow: Int,
        endCol: Int,
    ): String {
        val snap = _state.value
        val sb = snap.scrollback
        val vis = snap.visible
        val totalRows = sb.size + vis.size

        fun rowAt(absRow: Int): Array<Cell>? =
            when {
                absRow < 0 || absRow >= totalRows -> null
                absRow < sb.size -> sb[absRow]
                else -> vis[absRow - sb.size]
            }
        val (a0, c0, a1, c1) = normalizedRange(startAbsRow, startCol, endAbsRow, endCol)
        val out = StringBuilder()
        for (r in a0..a1) {
            val row = rowAt(r) ?: continue
            val lo = if (r == a0) c0 else 0
            val hi = if (r == a1) (c1 + 1).coerceAtMost(row.size) else row.size
            val sb2 = StringBuilder()
            for (c in lo until hi) sb2.append(row[c].ch)
            // Trim trailing spaces — terminal cells are space-padded.
            var end = sb2.length
            while (end > 0 && sb2[end - 1] == ' ') end--
            out.append(sb2, 0, end)
            if (r != a1) out.append('\n')
        }
        return out.toString()
    }

    private data class Range(
        val r0: Int,
        val c0: Int,
        val r1: Int,
        val c1: Int,
    )

    private fun normalizedRange(
        r0: Int,
        c0: Int,
        r1: Int,
        c1: Int,
    ): Range =
        if (r0 < r1 || (r0 == r1 && c0 <= c1)) {
            Range(r0, c0, r1, c1)
        } else {
            Range(r1, c1, r0, c0)
        }

    /**
     * Push a decoded keystroke from the Compose UI. Routes either to
     * [keyChannel] (raw mode — line editor / nano / pager are reading
     * keys directly) OR to [cookedModeFeedKey] (cooked mode — emulate
     * tty line discipline so tools reading byte-level stdin see the
     * line on Enter). Never both: double-routing made Ctrl-D in the
     * Python REPL also queue a Key.Ctrl('D') into keyChannel, which
     * the next kash readLine consumed as EOF and killed the shell.
     */
    public fun feedKey(k: Key) {
        // Key.Paste always goes to keyChannel, even in cooked mode.
        // Rationale: pastes happen asynchronously (browser paste / file
        // drop) and may land while the foreground command has
        // temporarily exited raw mode — e.g. the `agent` between
        // readLine calls during a tool-call stream. Routing to
        // cookedModeFeedKey would buffer into cookedLineBuf, which is
        // never drained without an Enter, so the bytes disappear from
        // the line editor's perspective when raw mode resumes.
        // keyChannel is Channel.UNLIMITED so this queues durably until
        // the next readLine consumes it.
        if (k is Key.Paste) {
            keyChannel.trySend(k)
            return
        }
        if (rawCount > 0) {
            keyChannel.trySend(k)
        } else {
            cookedModeFeedKey(k)
        }
    }

    /**
     * Cooked-mode line discipline. Buffer typed bytes locally, echo to
     * the display, and only push to [cookedBytes] when Enter materializes
     * the line. Mirrors what `ICANON | ECHO | ECHOE | ECHOK` give you on
     * a POSIX tty — without it Python REPL, `cat`, etc. show no input
     * and have no per-line editing.
     *
     * Special keys handled:
     *   - Enter  → flush buffer + `\n` to cookedBytes; echo `\r\n`
     *   - Backspace / Ctrl-H → drop last char; echo `\b \b` (erase one cell)
     *   - Ctrl-U → kill the whole line; echo erases each cell
     *   - Ctrl-D → if buffer empty, signal EOF; else flush buffer (no LF),
     *              matching POSIX VEOF behavior mid-line
     *   - Ctrl-C → discard buffer; echo `^C\r\n` (SIGINT routing TBD)
     *   - Other Ctrl-letter → forwarded as the literal control byte (no echo)
     *
     * Tab is currently echoed as a literal `\t` and pushed verbatim;
     * real ttys would expand tabs to next column, which our parser does
     * inside the grid via `grid.tab()`.
     */
    private fun cookedModeFeedKey(k: Key) {
        when (k) {
            is Key.Char -> {
                val s = codepointToString(k.codepoint)
                cookedLineBuf.append(s)
                feedString(s)
            }

            Key.Named.ENTER -> {
                val line = cookedLineBuf.toString()
                cookedLineBuf.clear()
                for (b in line.encodeToByteArray()) cookedBytes.trySend(b.toInt() and 0xFF)
                cookedBytes.trySend('\n'.code)
                feedString("\r\n")
            }

            Key.Named.BACKSPACE -> {
                if (cookedLineBuf.isNotEmpty()) {
                    cookedLineBuf.deleteAt(cookedLineBuf.length - 1)
                    feedString("\b \b")
                }
            }

            Key.Named.TAB -> {
                cookedLineBuf.append('\t')
                feedString("\t")
            }

            is Key.Ctrl -> {
                when (k.letter) {
                    'D' -> {
                        if (cookedLineBuf.isEmpty()) {
                            cookedBytes.trySend(EOF_MARKER)
                        } else {
                            val line = cookedLineBuf.toString()
                            cookedLineBuf.clear()
                            for (b in line.encodeToByteArray()) cookedBytes.trySend(b.toInt() and 0xFF)
                        }
                    }

                    'C' -> {
                        cookedLineBuf.clear()
                        feedString("^C\r\n")
                        onInterrupt?.invoke()
                    }

                    'U' -> {
                        val erase = "\b \b".repeat(cookedLineBuf.length)
                        cookedLineBuf.clear()
                        if (erase.isNotEmpty()) feedString(erase)
                    }

                    'H' -> {
                        if (cookedLineBuf.isNotEmpty()) {
                            cookedLineBuf.deleteAt(cookedLineBuf.length - 1)
                            feedString("\b \b")
                        }
                    }

                    else -> {
                        val byte = (k.letter.uppercaseChar().code - 'A'.code + 1) and 0x7F
                        cookedBytes.trySend(byte)
                    }
                }
            }

            is Key.Paste -> {
                cookedLineBuf.append(k.text)
                feedString(k.text)
                // Pastes that include a newline should commit each line
                // immediately the way Enter would, but a simple approach
                // is to commit the whole buffer if the paste contains
                // \n. Defer until real-world need.
            }

            else -> {}
        }
    }

    private fun codepointToString(cp: Int): String =
        if (cp <= 0xFFFF) {
            cp.toChar().toString()
        } else {
            val v = cp - 0x10000
            val hi = 0xD800 or (v shr 10)
            val lo = 0xDC00 or (v and 0x3FF)
            charArrayOf(hi.toChar(), lo.toChar()).concatToString()
        }

    private fun codepointToUtf8(cp: Int): ByteArray =
        when {
            cp < 0x80 -> {
                byteArrayOf(cp.toByte())
            }

            cp < 0x800 -> {
                byteArrayOf(
                    (0xC0 or (cp shr 6)).toByte(),
                    (0x80 or (cp and 0x3F)).toByte(),
                )
            }

            cp < 0x10000 -> {
                byteArrayOf(
                    (0xE0 or (cp shr 12)).toByte(),
                    (0x80 or ((cp shr 6) and 0x3F)).toByte(),
                    (0x80 or (cp and 0x3F)).toByte(),
                )
            }

            else -> {
                byteArrayOf(
                    (0xF0 or (cp shr 18)).toByte(),
                    (0x80 or ((cp shr 12) and 0x3F)).toByte(),
                    (0x80 or ((cp shr 6) and 0x3F)).toByte(),
                    (0x80 or (cp and 0x3F)).toByte(),
                )
            }
        }

    /**
     * Same contract as `PosixTerminalControl.cookedByteSource`. Per-read
     * EOF on the `-1` sentinel; channel itself stays open so a subsequent
     * read blocks normally for the next keystroke.
     */
    public fun cookedByteSource(): SuspendSource =
        object : SuspendSource {
            override suspend fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (byteCount <= 0L) return 0L
                val first =
                    try {
                        cookedBytes.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        return -1L
                    }
                if (first == EOF_MARKER) return -1L
                sink.writeByte(first.toByte())
                var written = 1L
                while (written < byteCount) {
                    val r = cookedBytes.tryReceive()
                    val v = r.getOrNull() ?: break
                    if (v == EOF_MARKER) break
                    sink.writeByte(v.toByte())
                    written++
                }
                return written
            }

            override fun close() {}
        }

    /** Sink wired as stdout/stderr — bytes flow through the parser. */
    public val asSink: SuspendSink = TerminalSink(this)

    /**
     * Display state for Compose to observe. Arrays here are LIVE
     * references into the grid (no copy). Renderer must read
     * defensively — call sites use indexed access with bounds checks.
     */
    public data class Snapshot(
        val seq: Long,
        val cols: Int,
        val rows: Int,
        val visible: Array<Array<Cell>>,
        val scrollback: ArrayDeque<Array<Cell>>,
        /** Monotonic count of rows ever appended; used to keep a scrolled
         *  view anchored on the same content when the ring rotates. */
        val scrollbackTotalAdded: Long,
        val cursorRow: Int,
        val cursorCol: Int,
        val cursorVisible: Boolean,
        val onAlt: Boolean,
    )

    public companion object {
        public const val EOF_MARKER: Int = -1
        internal const val BYTES_PER_FRAME: Int = 64 * 1024
    }
}

private var nextSnapshotSeqCounter: Long = 0

private fun nextSnapshotSeq(): Long {
    nextSnapshotSeqCounter++
    return nextSnapshotSeqCounter
}

private class TerminalSink(
    private val terminal: ComposeTerminal,
) : SuspendSink {
    override suspend fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        if (byteCount <= 0L) return
        val bytes = source.readByteArray(byteCount.toInt())
        // Backpressure: at most BYTES_PER_FRAME pass through per browser
        // frame. If we'd blow past the budget, suspend on the next rAF —
        // a real macrotask boundary, which lets the JS event loop paint,
        // process input, and deliver Ctrl-C. Without this an infinite
        // producer (cat /dev/random) keeps the microtask queue full
        // forever and the browser never gets a chance to render.
        if (terminal.bytesThisFrame >= ComposeTerminal.BYTES_PER_FRAME) {
            terminal.awaitFrame()
        }
        terminal.feedBytes(bytes)
    }

    override suspend fun flush() {}

    override fun close() {}
}
