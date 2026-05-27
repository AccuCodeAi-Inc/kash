package com.accucodeai.kash.api.io

import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/**
 * Sync helpers that operate on kotlinx-io primitives — used by tools that
 * build intermediate `Buffer`s locally before flushing them through a
 * [SuspendSink]. The pipeline-facing equivalents on [SuspendSink] /
 * [SuspendSource] live in [SuspendIo.kt].
 */

public fun RawSink.writeUtf8(s: String) {
    if (s.isEmpty()) return
    val buf = Buffer()
    buf.writeString(s)
    write(buf, buf.size)
    flush()
}

public fun RawSink.writeLine(s: String) {
    writeUtf8(s)
    writeUtf8("\n")
}

public fun RawSink.writeBytes(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    val buf = Buffer()
    buf.write(bytes)
    write(buf, buf.size)
    flush()
}

public fun RawSink.transferFrom(source: RawSource): Long {
    val buf = Buffer()
    var total = 0L
    while (true) {
        val n = source.readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
        write(buf, buf.size)
        total += n
    }
    flush()
    return total
}

public fun RawSource.readUtf8Text(): String {
    val buf = Buffer()
    while (true) {
        val n = readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
    }
    return buf.readString()
}

public fun RawSource.readAllBytes(): ByteArray {
    val buf = Buffer()
    while (true) {
        val n = readAtMostTo(buf, 8 * 1024L)
        if (n == -1L) break
    }
    return buf.readByteArray()
}

public fun RawSource.readUtf8LineOrNull(): String? = readUtf8DelimitedOrNull('\n'.code.toByte())

public fun RawSource.readUtf8DelimitedOrNull(delim: Byte): String? {
    val chunk = Buffer()
    val out = StringBuilder()
    var any = false
    while (true) {
        val n = readAtMostTo(chunk, 1L)
        if (n == -1L) break
        any = true
        val b = chunk.readByte()
        if (b == delim) return out.toString()
        out.append(b.toInt().toChar())
    }
    return if (any) out.toString() else null
}
