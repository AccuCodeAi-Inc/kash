package com.accucodeai.kash.test

import com.accucodeai.kash.api.io.EmptySuspendSource
import com.accucodeai.kash.api.io.NullSuspendSink
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.fs.FileSystem

/**
 * No-op [FileSystem] for tests that should never hit the disk — every
 * read returns an empty source, every write silently discards.
 */
public open class NullFs : FileSystem {
    override fun exists(path: String): Boolean = false

    override fun isDirectory(path: String): Boolean = false

    override fun source(path: String): SuspendSource = EmptySuspendSource

    override fun sink(
        path: String,
        append: Boolean,
        mode: Int,
    ): SuspendSink = NullSuspendSink

    override fun mkdirs(
        path: String,
        mode: Int,
    ) {}

    override fun list(path: String): List<String> = emptyList()

    override fun remove(path: String) {}
}
