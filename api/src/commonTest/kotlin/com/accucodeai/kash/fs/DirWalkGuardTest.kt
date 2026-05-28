package com.accucodeai.kash.fs

import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pins [DirWalkGuard] — the shared descent policy every recursive tool uses. */
class DirWalkGuardTest {
    /** Minimal FS: [dirs] are directories, [links] maps symlink path → target. */
    private class FakeFs(
        val dirs: Set<String>,
        val links: Map<String, String> = emptyMap(),
    ) : FileSystem {
        override fun exists(path: String) = path in dirs || path in links

        override fun isDirectory(path: String) = path in dirs || (links[path]?.let { it in dirs } ?: false)

        override fun statLink(path: String): FileStat =
            if (path in links) {
                FileStat(path, FileType.SYMLINK, 0, 0, 0, symlinkTarget = links[path])
            } else {
                stat(path)
            }

        override fun readSymlink(path: String): String = links[path] ?: error("not a link: $path")

        override fun list(path: String): List<String> = emptyList()

        override fun source(path: String): SuspendSource = error("unused")

        override fun sink(
            path: String,
            append: Boolean,
            mode: Int,
        ): SuspendSink = error("unused")

        override fun mkdirs(
            path: String,
            mode: Int,
        ) {}

        override fun remove(path: String) {}

        override suspend fun readBytes(path: String): ByteArray = ByteArray(0)

        override suspend fun writeBytes(
            path: String,
            bytes: ByteArray,
            mode: Int,
        ) {}
    }

    @Test fun descends_into_real_directory() {
        val fs = FakeFs(dirs = setOf("/a", "/a/sub"))
        assertTrue(DirWalkGuard(fs).shouldDescend("/a/sub", 1))
    }

    @Test fun does_not_descend_into_plain_file() {
        val fs = FakeFs(dirs = setOf("/a"))
        assertFalse(DirWalkGuard(fs).shouldDescend("/a/file", 1))
    }

    @Test fun skips_symlinked_dir_when_not_following() {
        val fs = FakeFs(dirs = setOf("/a", "/a/real"), links = mapOf("/a/link" to "/a/real"))
        val g = DirWalkGuard(fs, followSymlinks = false)
        assertFalse(g.shouldDescend("/a/link", 1)) // symlink-to-dir not followed
        assertTrue(g.shouldDescend("/a/real", 1)) // real dir still descended
    }

    @Test fun follows_symlinked_dir_when_following_but_breaks_cycle() {
        val fs = FakeFs(dirs = setOf("/a"), links = mapOf("/a/self" to "/a", "/a/self2" to "/a"))
        val g = DirWalkGuard(fs, followSymlinks = true)
        assertTrue(g.shouldDescend("/a/self", 1)) // first link to /a → follow
        assertFalse(g.shouldDescend("/a/self2", 1)) // another link to /a → cycle, skip
    }

    @Test fun refuses_descent_at_or_past_max_depth() {
        val fs = FakeFs(dirs = setOf("/a", "/a/sub"))
        val g = DirWalkGuard(fs, maxDepth = 3)
        assertTrue(g.shouldDescend("/a/sub", 2))
        assertFalse(g.shouldDescend("/a/sub", 3)) // depth == maxDepth → refused
    }
}
