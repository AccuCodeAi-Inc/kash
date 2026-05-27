package com.accucodeai.kash.tools.git.plumbing

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

public actual fun zlibDeflate(
    data: ByteArray,
    level: Int,
): ByteArray {
    val def = Deflater(level)
    try {
        def.setInput(data)
        def.finish()
        val out = ByteArrayOutputStream(data.size + 16)
        val buf = ByteArray(4096)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        def.end()
    }
}

public actual fun zlibInflate(data: ByteArray): ByteArray {
    val inf = Inflater()
    try {
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(4096)
        while (true) {
            val n = inf.inflate(buf)
            if (n > 0) out.write(buf, 0, n)
            if (inf.finished()) break
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) {
                    error("zlib inflate: truncated input")
                }
            }
        }
        return out.toByteArray()
    } finally {
        inf.end()
    }
}

public actual fun zlibInflateChunk(
    data: ByteArray,
    offset: Int,
): InflatedChunk {
    val inf = Inflater()
    try {
        inf.setInput(data, offset, data.size - offset)
        val out = ByteArrayOutputStream(64)
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n > 0) {
                out.write(buf, 0, n)
            } else if (inf.needsInput() || inf.needsDictionary()) {
                error("zlib inflate: truncated input")
            }
        }
        return InflatedChunk(out.toByteArray(), inf.bytesRead.toInt())
    } finally {
        inf.end()
    }
}
