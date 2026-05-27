package com.accucodeai.kash.tools.python3.graalpy

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import java.io.InputStream
import java.io.OutputStream

/**
 * Bridge a kash [SuspendSource] to a synchronous Java [InputStream] so it
 * can be handed to GraalVM's `Context.Builder.in(InputStream)`. Truffle's
 * embed surface only accepts blocking JVM streams; this adapter is the
 * minimum runBlocking-bound shim.
 *
 * Justified runBlocking — same rationale as the GraalPy filesystem bridge
 * at `KashPolyglotFileSystem.kt` `SeekableByteChannel`. The bound is
 * "Truffle's API is synchronous, we have to suspend somewhere", and the
 * caller is GraalPy's REPL/runtime, which expects classical blocking
 * read semantics.
 *
 * Single-byte and bulk `read` both go through the same suspend path; the
 * single-byte form allocates a one-byte staging buffer per call because
 * CPython's `code.interact()` reads one character at a time through
 * `sys.stdin.readline()` and the cost there is dwarfed by the
 * interpreter-side work.
 */
public class SuspendSourceInputStream(
    private val src: SuspendSource,
) : InputStream() {
    private val staging: Buffer = Buffer()
    private val one = ByteArray(1)

    override fun read(): Int {
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else (one[0].toInt() and 0xFF)
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        require(off >= 0 && len >= 0 && off + len <= b.size) {
            "out of bounds: off=$off len=$len size=${b.size}"
        }
        val n =
            runBlocking {
                src.readAtMostTo(staging, len.toLong())
            }
        if (n <= 0L) return -1
        val read = n.toInt()
        for (i in 0 until read) {
            b[off + i] = staging.readByte()
        }
        return read
    }

    override fun close() {
        // Non-owning: the SuspendSource belongs to the calling tool's
        // OFD lifecycle, not to this adapter. Truffle calls close() on
        // engine shutdown — we don't want to drop the host stdin.
    }
}

/**
 * Bridge a kash [SuspendSink] to a synchronous Java [OutputStream] for
 * `Context.Builder.out(OutputStream)` / `.err(OutputStream)`. Per-write
 * flush keeps line-buffered semantics for the REPL — `print('x')` at the
 * `>>>` prompt has to surface before the next prompt is drawn, and the
 * upstream `python.TerminalIsInteractive` option only sets sys.stdout's
 * Python-side buffering mode; it doesn't guarantee anything about the
 * underlying Java OutputStream we hand to Truffle.
 */
public class SuspendSinkOutputStream(
    private val sink: SuspendSink,
) : OutputStream() {
    private val staging: Buffer = Buffer()

    override fun write(b: Int) {
        staging.writeByte(b.toByte())
        runBlocking {
            sink.write(staging, 1L)
            sink.flush()
        }
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (len == 0) return
        require(off >= 0 && len >= 0 && off + len <= b.size) {
            "out of bounds: off=$off len=$len size=${b.size}"
        }
        staging.write(b, off, off + len)
        runBlocking {
            sink.write(staging, len.toLong())
            sink.flush()
        }
    }

    override fun flush() {
        runBlocking { sink.flush() }
    }

    override fun close() {
        // Non-owning, same reason as SuspendSourceInputStream.close.
    }
}
