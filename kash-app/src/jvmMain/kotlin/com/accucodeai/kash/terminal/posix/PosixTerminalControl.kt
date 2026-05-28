package com.accucodeai.kash.terminal.posix

import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.terminal.Key
import com.accucodeai.kash.api.terminal.TerminalControl
import com.accucodeai.kash.api.terminal.TerminalSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicInteger

/**
 * [TerminalControl] implemented via direct libc calls (Project Panama) and
 * a hand-rolled escape decoder. Replaces JLine entirely for kash's terminal
 * needs on macOS / Linux.
 *
 * **Single host-fd-0 reader.** This class owns the only thread doing
 * `read(0, …)`. It routes each byte by raw-mode state: in raw mode bytes
 * feed the [EscapeDecoder] → [keyChannel] (consumed by [readKey]); in
 * cooked mode (default) bytes feed [cookedBytes] → [cookedByteSource]
 * (installed as fd 0's OFD source, consumed by tools via `ctx.stdin`,
 * including external interpreters embedded in kash such as GraalPy).
 *
 * Lifecycle:
 *
 *  1. Construct: allocates the saved-termios segment in the global arena.
 *  2. [start]: snapshots current termios for restore-on-exit, kicks off
 *     the stdin pump thread and the decoder coroutine. After this both
 *     `readKey()` and reads through `cookedByteSource()` can suspend
 *     successfully.
 *  3. [enterRawMode] / [exitRawMode] toggle raw mode; refcounted so
 *     recursive shells nesting raw-mode regions don't fight each other.
 *  4. [stop] (idempotent): restore termios, mark the pump stopped, and
 *     cancel the decoder coroutine. The pump's daemon thread is left
 *     parked in `read(0, …)` — JVM exit reaps it. We don't `close(0)` to
 *     wake it because on macOS that call blocks until the read returns
 *     (waiting on the user to type another key); see the pump comment.
 *
 * Thread safety: the pump thread, signal handler thread, and shell
 * coroutines all touch this object. Internal state is either immutable
 * (segments, channels) or guarded (atomic refcount).
 *
 * @param byteReader the source of raw bytes from fd 0. Production code
 *   uses the default [LibcByteReader] (Panama `read(0, buf, n)`); tests
 *   inject a deterministic feeder to drive routing scenarios without
 *   touching the real terminal.
 */
internal class PosixTerminalControl(
    private val byteReader: ByteReader = LibcByteReader,
) : TerminalControl {
    private val savedTermios: Termios = Termios.allocate(Libc.GLOBAL_ARENA)

    /**
     * Reference count for raw-mode entry. Two concurrent callers
     * (recursive `kash`-in-kash) must each call [enterRawMode] and
     * [exitRawMode] in pairs — the 0→1 transition does the syscall,
     * 1→2+ no-ops, and decrement reverses. The pump reads this atomic
     * per-byte to decide routing, so raw-mode transitions take effect
     * on the next byte without locking.
     */
    private val rawCount: AtomicInteger = AtomicInteger(0)

    @Volatile
    private var rawSaved: Boolean = false

    /**
     * Track which terminal modes we've actually flipped on, so [stop] only
     * undoes what it set. Emitting `\e[?1049l` when alt-screen was never
     * entered, or `\e>` when keypad-xmit was never enabled, is not a no-op
     * on macOS Terminal / iTerm — `\e[?1049l` restores the screen to a
     * "saved" state that the terminal may have from an entirely different
     * app, scrambling the host shell's scrollback.
     */
    @Volatile
    private var altScreenEntered: Boolean = false

    @Volatile
    private var keypadXmitEntered: Boolean = false

    /** Bytes routed to the escape decoder when raw mode is active. */
    private val rawBytes: Channel<Byte> = Channel(Channel.UNLIMITED)

    /**
     * Bytes routed to [cookedByteSource] when raw mode is inactive.
     * `Int` (not `Byte`) so the sentinel [EOF_MARKER] (-1) can carry an
     * end-of-stream signal through the same channel without closing it.
     * That distinction matters in cooked mode: typing Ctrl-D at an empty
     * line makes `libc.read(0)` return 0 — a per-read EOF that Python's
     * REPL expects to see — but the fd is still open and the next
     * `read(0)` will block for more input. Closing the channel here
     * would break the line editor's keyChannel (also fed by the pump)
     * for the entire session.
     */
    private val cookedBytes: Channel<Int> = Channel(Channel.UNLIMITED)

    /** Decoded keys, consumed by [readKey]. */
    private val keyChannel: Channel<Key> = Channel(Channel.UNLIMITED)

    private val decoder: EscapeDecoder = EscapeDecoder()

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var decoderJob: Job? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var pumpThread: Thread? = null

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var stopped: Boolean = false

    /**
     * Snapshot the controlling terminal's attributes (for restore at exit),
     * launch the byte-pump thread and decoder coroutine. Must be called
     * before any [enterRawMode] / [readKey] / read through [cookedByteSource].
     */
    fun start() {
        check(!started) { "PosixTerminalControl already started" }
        check(!stopped) { "PosixTerminalControl already stopped" }
        // Snapshot current termios so [stop] can restore. If fd 0 isn't a
        // tty we treat it as non-fatal — enterRawMode is a no-op in that
        // case (rawSaved stays false) and the cooked-bytes path still works.
        val rc = Libc.tcgetattr(0, savedTermios.segment)
        rawSaved = (rc == 0)
        running = true
        decoderJob =
            scope.launch {
                decoder.decode(rawBytes, keyChannel)
            }
        startPumpThread()
        started = true
    }

    /**
     * Single host-fd-0 reader. Daemon thread parked in [byteReader.read].
     * Routes each byte by [rawCount] state — atomic read, no locking.
     *
     * Daemon because:
     *  - JVM process exit reaps the thread even mid-syscall, so we don't
     *    need to interrupt the read at shutdown.
     *  - We can't `close(0)` to wake it from another thread: on macOS
     *    `close(fd)` *waits* for any `read(fd, …)` blocked in another
     *    thread to return rather than unblocking it (jline3 PR #1817
     *    has the canonical writeup of this POSIX wrinkle). Calling close
     *    here used to add seconds of wall-time to shutdown until the
     *    user typed another keystroke.
     *
     * **POSIX edge cases.**
     *  - `read(2)` returning 0 in cooked mode is VEOF (Ctrl-D at empty
     *    line) — surfaced as [EOF_MARKER] on [cookedBytes], not as a
     *    pump death. Handled below.
     *  - `read(2)` returning -1 with `errno == EINTR` is a signal
     *    interrupt. We rely on `sun.misc.Signal` installing handlers
     *    with `SA_RESTART` (true on all current macOS/Linux JVMs), so
     *    in practice EINTR doesn't surface and -1 reliably means a
     *    real fd close (terminal hangup, ssh disconnect). If a future
     *    JDK drops SA_RESTART we'd need errno access via Panama to
     *    differentiate; today the assumption holds.
     *  - Concurrent readers of [cookedBytes] from multiple tools would
     *    split bytes arbitrarily. Not currently possible: the kash
     *    interpreter runs one foreground command at a time, and
     *    piped/background stages use their own AsyncPipe-backed source
     *    instead of inheriting fd 0.
     */
    private fun startPumpThread() {
        val buf = ByteArray(4096)
        val t =
            Thread {
                while (running) {
                    val n =
                        try {
                            byteReader.read(buf)
                        } catch (_: Throwable) {
                            -1
                        }
                    if (n < 0) break // hard error/EOF on the fd
                    if (n == 0) {
                        // In cooked mode, the kernel's line discipline
                        // returns 0 when the user types VEOF (Ctrl-D) at
                        // an empty line. That's NOT a fd close — the
                        // terminal is still open and the next read() will
                        // block for more input. Forward an EOF marker so
                        // the current cooked-mode reader (e.g. an embedded
                        // python3 REPL waiting for input) sees end-of-line
                        // and can exit cleanly, but keep the pump alive
                        // for subsequent input from the line editor and
                        // future tools. In raw mode there's no kernel
                        // line discipline, so 0-reads don't happen unless
                        // the fd is truly closed (handled by n<0 above).
                        if (rawCount.get() == 0) cookedBytes.trySend(EOF_MARKER)
                        continue
                    }
                    for (i in 0 until n) {
                        val b = buf[i]
                        if (rawCount.get() > 0) {
                            if (rawBytes.trySend(b).isFailure) break
                        } else {
                            if (cookedBytes.trySend(b.toInt() and 0xFF).isFailure) break
                        }
                    }
                }
                rawBytes.close()
                cookedBytes.close()
            }
        t.isDaemon = true
        t.name = "kash-stdin-pump"
        pumpThread = t
        t.start()
    }

    /**
     * Restore termios + emit cursor/alt-screen reset escapes. Safe to call
     * multiple times. Called from both the JVM shutdown hook and
     * (optionally) from kash-app's graceful-exit path.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        // Best-effort restoration: emit escapes for the alt-screen and
        // cursor *before* tcsetattr so the terminal renders them in raw
        // mode (no line-buffering quirks).
        runCatching {
            // Only undo modes we actually set. `\e[?1049l`
            // (exit alt-screen) is NOT a no-op when we never entered
            // alt-screen: on macOS Terminal/iTerm it switches to a
            // "saved" buffer that may belong to a prior app, scrambling
            // the host shell's scrollback. Same with `\e>` (keypad-local)
            // when `\e=` (keypad-xmit) was never emitted.
            val sb = StringBuilder()
            if (altScreenEntered) sb.append("[?1049l")
            // [?25h (show cursor) is gated on rawSaved — the only
            // emitters of [?25l in kash are full-screen tools (nano,
            // less, vi, fzf, tput civis) that run in raw mode, which
            // requires fd 0 to be a tty at start time. Without a tty
            // we could not have hidden the cursor, and emitting [?25h
            // would just litter piped stdout (e.g. Claude Code's
            // bash tool reading our output via a pipe).
            if (rawSaved) sb.append("[?25h")
            if (keypadXmitEntered) sb.append(">")
            if (sb.isNotEmpty()) writeRaw(sb.toString())
        }
        if (rawSaved) {
            runCatching { savedTermios.apply(0) }
        }
        running = false
        runCatching { keyChannel.close() }
        decoderJob?.cancel()
    }

    override suspend fun enterRawMode() {
        val before = rawCount.getAndIncrement()
        if (before == 0) {
            // 0 → 1 transition: actually enter raw mode. If fd 0 wasn't a
            // tty at start time (rawSaved=false), there's nothing to flip;
            // the rawCount still increments so exit pairs balance, and the
            // pump still routes to rawBytes per-byte — which is harmless
            // since no decoder consumer is paired with that branch unless
            // a real terminal is in play.
            if (rawSaved) {
                val work = savedTermios.copy(Libc.GLOBAL_ARENA)
                work.makeRaw()
                val rc = work.apply(0)
                check(rc == 0) { "tcsetattr to enter raw mode failed: rc=$rc" }
                // Emit keypad-xmit so arrow keys / function keys go through
                // CSI / SS3 (matches JLine's keypad numeric-mode behavior).
                // DECKPAM = ESC '='.
                writeRaw("=")
                keypadXmitEntered = true
            }
        }
        // 1 → 2+ no-op (refcount).
    }

    override suspend fun exitRawMode() {
        val before = rawCount.getAndDecrement()
        if (before == 1) {
            // 1 → 0 transition: actually leave raw mode.
            if (rawSaved) {
                writeRaw("\u001b>")
                keypadXmitEntered = false
                runCatching { savedTermios.apply(0) }
            }
        } else if (before <= 0) {
            // exit without matching enter — bug somewhere. Clamp at 0.
            rawCount.set(0)
        }
    }

    override suspend fun useAlternateScreen(enable: Boolean) {
        writeRaw(if (enable) "\u001b[?1049h" else "\u001b[?1049l")
        altScreenEntered = enable
    }

    /**
     * Synthesize a Ctrl-L into [keyChannel] so whoever is reading keys
     * repaints at the new width. Ctrl-L is universally bound to
     * "refresh / redraw" — line editors clear+redraw, nano's Refresh
     * binding repaints from its model, less-style pagers also redraw.
     * Fired regardless of raw mode for that reason.
     */
    fun notifyResizeRedraw() {
        keyChannel.trySend(Key.Ctrl('L'))
    }

    override fun size(): TerminalSize =
        Winsize.query(0)
            // ioctl can fail mid-resize or when running without a controlling
            // tty (rare on JVM). Fall back to a conservative 80×24 — same
            // floor JLine uses internally — rather than throwing.
            ?: TerminalSize(cols = 80, rows = 24)

    override suspend fun readKey(): Key = keyChannel.receive()

    override fun pushKey(key: Key) {
        // Out-of-band keys (Key.PrintAbove from a host hook, Key.Paste
        // synthesized by an OSC-52/bracketed-paste wrapper, etc.) land
        // directly in keyChannel — same path the byte-decoder pump uses.
        // Fired regardless of raw mode; raw-only consumers will see
        // them on their next readKey.
        keyChannel.trySend(key)
    }

    override fun drainKeys(keep: (Key) -> Boolean): List<Key> {
        // Non-blocking pull from [keyChannel]: try-receive in a loop
        // until empty, partition into keepers vs discards, re-queue
        // the keepers in original order so out-of-band events
        // (Key.Paste / Key.PrintAbove) survive across a drain window.
        val keepers = mutableListOf<Key>()
        val discarded = mutableListOf<Key>()
        while (true) {
            val r = keyChannel.tryReceive()
            if (!r.isSuccess) break
            val k = r.getOrNull() ?: continue
            if (keep(k)) keepers += k else discarded += k
        }
        for (k in keepers) keyChannel.trySend(k)
        return discarded
    }

    override suspend fun write(s: String) {
        writeRaw(s)
    }

    override suspend fun flush() {
        // Direct libc write(1, …) is unbuffered. The contract still requires
        // a flush method for parity with buffered implementations.
    }

    /**
     * The canonical fd 0 [SuspendSource] — bytes flow here whenever raw
     * mode is OFF. Installed as the source of the fd 0 OFD in `Main.kt`,
     * so every tool reading `ctx.stdin` (including embedded interpreters
     * like GraalPy that bridge to a Java `InputStream`) pulls from the
     * same byte stream this class owns. Eliminates the previous race
     * between this class and a parallel `System.in.read()` reader.
     */
    fun cookedByteSource(): SuspendSource =
        object : SuspendSource {
            override suspend fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (byteCount <= 0L) return 0L
                // Block on the first item so callers see standard
                // read-at-least-one semantics. Channel close (pump exit
                // on JVM shutdown) and per-read EOF (Ctrl-D in cooked
                // mode) both surface as readAtMostTo returning -1L; the
                // difference is whether the next call also blocks (channel
                // open, just waiting for input) or returns -1L again
                // (channel closed).
                val first =
                    try {
                        cookedBytes.receive()
                    } catch (_: ClosedReceiveChannelException) {
                        return -1L
                    }
                if (first == EOF_MARKER) return -1L
                sink.writeByte(first.toByte())
                var written = 1L
                // Drain any other items already buffered without blocking,
                // up to byteCount. Matches POSIX read(2) "what's ready"
                // semantics on a pipe/tty. Stop at the first EOF marker:
                // we *don't* push it back — the caller is mid-read and
                // got bytes, so they'll see a non-EOF return now. The
                // EOF was a one-shot per-read signal (e.g. user pressed
                // Ctrl-D in the middle of typing). Consuming it here
                // means a *new* Ctrl-D would need to fire for the next
                // reader to see EOF, which matches what the kernel does:
                // VEOF is per-read, not a sticky stream state.
                while (written < byteCount) {
                    val r = cookedBytes.tryReceive()
                    val v = r.getOrNull() ?: break
                    if (v == EOF_MARKER) break
                    sink.writeByte(v.toByte())
                    written++
                }
                return written
            }

            override fun close() {
                // The channel is owned by PosixTerminalControl; closing the
                // source is a no-op (matches the non-owning OFD contract
                // used for installStdio).
            }
        }

    /**
     * Direct UTF-8 write to fd 1 via libc. Bypasses Java's PrintStream
     * buffering — important during raw mode where we control the cursor
     * byte-by-byte and can't tolerate the JVM re-ordering writes around our
     * escape sequences.
     */
    private fun writeRaw(s: String) {
        val bytes = s.encodeToByteArray()
        if (bytes.isEmpty()) return
        Arena.ofConfined().use { arena ->
            val seg: MemorySegment = arena.allocate(bytes.size.toLong())
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.size)
            var remaining = bytes.size.toLong()
            var offset = 0L
            while (remaining > 0L) {
                val n = Libc.write(1, seg.asSlice(offset), remaining)
                if (n <= 0L) break
                remaining -= n
                offset += n
            }
        }
    }

    /**
     * Abstraction over the source of raw bytes. The default implementation
     * calls `libc.read(0, buf, buf.length)`. Tests inject a deterministic
     * feeder to drive routing scenarios without touching the real terminal.
     */
    internal fun interface ByteReader {
        /** Return bytes read into [buf] starting at index 0, or ≤0 on EOF/error. */
        fun read(buf: ByteArray): Int
    }

    internal companion object {
        /**
         * Sentinel value sent into [cookedBytes] when [byteReader.read]
         * returns 0 in cooked mode (kernel line-discipline VEOF / Ctrl-D
         * at empty line). Distinguishable from a real byte because we
         * widen byte values to `0..255` ints when posting them.
         */
        internal const val EOF_MARKER: Int = -1
    }

    /** Production [ByteReader] — Panama-bound `read(0, buf, n)`. */
    internal object LibcByteReader : ByteReader {
        override fun read(buf: ByteArray): Int =
            Arena.ofConfined().use { arena ->
                val seg = arena.allocate(buf.size.toLong())
                val n = Libc.read(0, seg, buf.size.toLong()).toInt()
                if (n > 0) {
                    MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0L, buf, 0, n)
                }
                n
            }
    }
}
