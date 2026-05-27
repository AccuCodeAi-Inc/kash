package com.accucodeai.kash.api.util

import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSource
import kotlinx.io.Buffer
import kotlinx.io.writeString

/** Wrap a byte array in a one-shot [SuspendSource]. */
public fun bufferOf(bytes: ByteArray): SuspendSource {
    val b = Buffer()
    if (bytes.isNotEmpty()) b.write(bytes)
    return b.asSuspendSource()
}

/** Encode a UTF-8 string and wrap it in a one-shot [SuspendSource]. */
public fun bufferOf(text: String): SuspendSource {
    val b = Buffer()
    if (text.isNotEmpty()) b.writeString(text)
    return b.asSuspendSource()
}

/** A drained [SuspendSource] — reading from it yields EOF immediately. */
public fun emptySource(): SuspendSource = EmptySuspendSource
