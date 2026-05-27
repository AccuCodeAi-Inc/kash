package com.accucodeai.kash.fs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun singleRoot(): MountedFileSystem = MountedFileSystem(listOf(Mount("/", InMemoryFs(), FsLabel.USER)))

private fun rootAndTmp(): Pair<MountedFileSystem, Pair<InMemoryFs, InMemoryFs>> {
    val root = InMemoryFs()
    val tmp = InMemoryFs()
    val mfs =
        MountedFileSystem(
            listOf(
                Mount("/", root, FsLabel.USER),
                Mount("/tmp", tmp, FsLabel.EPHEMERAL),
            ),
        )
    return mfs to (root to tmp)
}

class MountedFileSystemTest {
    // ---- A2/audit: construction validation ----

    @Test
    fun `requires a root mount`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                MountedFileSystem(listOf(Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL)))
            }
        }

    @Test
    fun `rejects duplicate mount points`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL),
                        Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL),
                    ),
                )
            }
        }

    @Test
    fun `rejects empty mount list`() =
        runTest {
            assertFailsWith<IllegalArgumentException> {
                MountedFileSystem(emptyList())
            }
        }

    @Test
    fun `mount points are normalized at construction`() =
        runTest {
            // Trailing slash should fold to no trailing slash, and `/tmp/` should
            // be considered equivalent to `/tmp` for duplicate detection.
            assertFailsWith<IllegalArgumentException> {
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL),
                        Mount("/tmp/", InMemoryFs(), FsLabel.EPHEMERAL),
                    ),
                )
            }
        }

    // ---- single root: behaves like the underlying FS ----

    @Test
    fun `single root delegates writes and reads`() =
        runTest {
            val mfs = singleRoot()
            mfs.writeBytes("/home/user/note.txt", "hi".encodeToByteArray())
            assertTrue(mfs.exists("/home/user/note.txt"))
            assertEquals("hi", mfs.readBytes("/home/user/note.txt").decodeToString())
        }

    @Test
    fun `single root list matches underlying`() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x.txt", "x".encodeToByteArray())
            fs.writeBytes("/y.txt", "y".encodeToByteArray())
            val mfs = MountedFileSystem(listOf(Mount("/", fs, FsLabel.USER)))
            assertEquals(listOf("bin", "env", "home", "tmp", "usr", "x.txt", "y.txt"), mfs.list("/"))
        }

    // ---- two-mount routing ----

    @Test
    fun `writes to mount path land in mount, not root`() =
        runTest {
            val (mfs, fss) = rootAndTmp()
            val (root, tmp) = fss
            mfs.writeBytes("/tmp/scratch.txt", "data".encodeToByteArray())
            assertTrue(tmp.exists("/scratch.txt"), "tmp mount should hold the file at its own root")
            assertFalse(root.exists("/tmp/scratch.txt"), "root mount must not see writes routed to /tmp")
        }

    @Test
    fun `reads to root path do not leak into tmp`() =
        runTest {
            val (mfs, fss) = rootAndTmp()
            val (root, _) = fss
            root.writeBytes("/home/user/hello.txt", "x".encodeToByteArray())
            assertTrue(mfs.exists("/home/user/hello.txt"))
            assertFalse(mfs.exists("/foo")) // /foo is in root, not /tmp — but doesn't exist
        }

    // ---- longest-prefix wins ----

    @Test
    fun `nested mounts use longest-prefix routing`() =
        runTest {
            val a = InMemoryFs()
            val ab = InMemoryFs()
            a.writeBytes("/x", "from-a".encodeToByteArray())
            ab.writeBytes("/x", "from-ab".encodeToByteArray())
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/a", a, FsLabel.USER),
                        Mount("/a/b", ab, FsLabel.USER),
                    ),
                )
            // /a/x should hit the /a mount with relative path /x → "from-a"
            assertEquals("from-a", mfs.readBytes("/a/x").decodeToString())
            // /a/b/x should hit the /a/b mount with relative path /x → "from-ab"
            assertEquals("from-ab", mfs.readBytes("/a/b/x").decodeToString())
        }

    // ---- virtual mount-point directories ----

    @Test
    fun `mount points report as existing directories`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/graalpy", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            assertTrue(mfs.exists("/.cache/graalpy"))
            assertTrue(mfs.isDirectory("/.cache/graalpy"))
            // Ancestor too.
            assertTrue(mfs.exists("/.cache"))
            assertTrue(mfs.isDirectory("/.cache"))
        }

    @Test
    fun `list of virtual ancestor returns sub-mount names`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/graalpy", InMemoryFs(), FsLabel.ENGINE_CACHE),
                        Mount("/.cache/other", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            assertEquals(listOf("graalpy", "other"), mfs.list("/.cache"))
        }

    @Test
    fun `list merges root entries with sub-mount names`() =
        runTest {
            val (mfs, fss) = rootAndTmp()
            val (root, _) = fss
            root.writeBytes("/data/x.txt", "x".encodeToByteArray())
            // / has /data, /tmp is also a mount → list("/") includes both
            val rootList = mfs.list("/")
            assertTrue("data" in rootList)
            assertTrue("tmp" in rootList)
        }

    // ---- shadow rule ----

    @Test
    fun `mount shadows underlying content at mount point`() =
        runTest {
            val root = InMemoryFs()
            val tmp = InMemoryFs()
            // root has /tmp/leaked.txt; /tmp is mounted on top — reads of /tmp/* go to tmp mount.
            root.writeBytes("/tmp/leaked.txt", "should-not-be-visible".encodeToByteArray())
            tmp.writeBytes("/visible.txt", "visible-via-mount".encodeToByteArray())
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", root, FsLabel.USER),
                        Mount("/tmp", tmp, FsLabel.EPHEMERAL),
                    ),
                )
            // Through the router: only the mount's view is visible.
            assertFalse(mfs.exists("/tmp/leaked.txt"), "underlying entry must be masked by the mount")
            assertTrue(mfs.exists("/tmp/visible.txt"))
            // list of /tmp reflects the mount's contents (which include the InMemoryFs default
            // layout that the mount itself constructs at its root). The point of the shadow
            // assertion is that "leaked.txt" is NOT in this list.
            val tmpContents = mfs.list("/tmp")
            assertFalse("leaked.txt" in tmpContents, "shadowed entry leaked through router: $tmpContents")
            assertTrue("visible.txt" in tmpContents)
        }

    // ---- dot-dot normalization ----

    @Test
    fun `dot-dot normalizes before routing`() =
        runTest {
            val a = InMemoryFs()
            val b = InMemoryFs()
            a.writeBytes("/marker", "from-a".encodeToByteArray())
            b.writeBytes("/marker", "from-b".encodeToByteArray())
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/a", a, FsLabel.USER),
                        Mount("/b", b, FsLabel.USER),
                    ),
                )
            // /a/../b/marker should route to /b, not /a.
            assertEquals("from-b", mfs.readBytes("/a/../b/marker").decodeToString())
        }

    @Test
    fun `consecutive slashes normalize`() =
        runTest {
            val (mfs, fss) = rootAndTmp()
            val (_, tmp) = fss
            mfs.writeBytes("//tmp///foo", "x".encodeToByteArray())
            assertTrue(tmp.exists("/foo"))
        }

    // ---- read-only enforcement ----

    @Test
    fun `read-only mount rejects sink`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/ro", InMemoryFs(), FsLabel.ENGINE_CACHE, readOnly = true),
                    ),
                )
            assertFailsWith<ReadOnlyMountException> {
                mfs.writeBytes("/ro/foo", "x".encodeToByteArray())
            }
        }

    @Test
    fun `read-only mount rejects mkdirs and remove and chmod`() =
        runTest {
            val ro = InMemoryFs()
            ro.writeBytes("/preexisting", "x".encodeToByteArray())
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/ro", ro, FsLabel.ENGINE_CACHE, readOnly = true),
                    ),
                )
            assertFailsWith<ReadOnlyMountException> { mfs.mkdirs("/ro/newdir") }
            assertFailsWith<ReadOnlyMountException> { mfs.remove("/ro/preexisting") }
            assertFailsWith<ReadOnlyMountException> { mfs.chmod("/ro/preexisting", 0b110_100_100) }
            // Reads still work.
            assertEquals("x", mfs.readBytes("/ro/preexisting").decodeToString())
        }

    // ---- synthetic mount-point stat ----

    @Test
    fun `stat of mount point returns DIRECTORY metadata`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/graalpy", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            val stat = mfs.stat("/.cache/graalpy")
            assertEquals(FileType.DIRECTORY, stat.type)
            assertEquals("/.cache/graalpy", stat.path)
        }

    @Test
    fun `stat of virtual ancestor returns DIRECTORY metadata`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/graalpy", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            // No /.cache directory in root mount but it's an ancestor of a sub-mount.
            val stat = mfs.stat("/.cache")
            assertEquals(FileType.DIRECTORY, stat.type)
            assertEquals("/.cache", stat.path)
        }

    // ---- mount-point un-removable ----

    @Test
    fun `remove on a mount point throws`() =
        runTest {
            val (mfs, _) = rootAndTmp()
            assertFailsWith<RuntimeException> { mfs.remove("/tmp") }
        }

    @Test
    fun `remove on ancestor of mount point throws`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/engine", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            // /.cache is a virtual ancestor of /.cache/engine. Removing it would
            // only clear the parent mount's view (leaving the sub-mount stranded)
            // and is rejected for consistency.
            assertFailsWith<RuntimeException> { mfs.remove("/.cache") }
        }

    // ---- labels are queryable ----

    @Test
    fun `mounts query returns all labels`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/tmp", InMemoryFs(), FsLabel.EPHEMERAL),
                        Mount("/.cache", InMemoryFs(), FsLabel.ENGINE_CACHE, readOnly = true),
                    ),
                )
            val labels = mfs.mounts().map { it.label }.toSet()
            assertEquals(setOf(FsLabel.USER, FsLabel.ENGINE_CACHE, FsLabel.EPHEMERAL), labels)
            val ro = mfs.mounts().single { it.label == FsLabel.ENGINE_CACHE }
            assertTrue(ro.readOnly)
        }

    // ---- end-to-end cross-mount usage ----

    @Test
    fun `chmod routes to the owning mount`() =
        runTest {
            val (mfs, fss) = rootAndTmp()
            val (_, tmp) = fss
            mfs.writeBytes("/tmp/script.sh", "x".encodeToByteArray())
            mfs.chmod("/tmp/script.sh", 0b111_101_101)
            // Verify by reading the underlying tmp mount's stat.
            assertEquals(0b111_101_101, tmp.stat("/script.sh").mode)
        }

    @Test
    fun `listStat returns one entry per merged child`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/graalpy", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            val stats = mfs.listStat("/.cache")
            assertEquals(1, stats.size)
            assertEquals("/.cache/graalpy", stats[0].path)
            assertEquals(FileType.DIRECTORY, stats[0].type)
        }

    // ---- symlinks ----

    @Test
    fun `absolute symlink target re-routes across mounts`() =
        runTest {
            val root = InMemoryFs()
            val cache = InMemoryFs()
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", root, FsLabel.USER),
                        Mount("/.cache/x", cache, FsLabel.ENGINE_CACHE),
                    ),
                )
            mfs.writeBytes("/.cache/x/payload", "cross-mount".encodeToByteArray())
            mfs.createSymlink("/alias", "/.cache/x/payload")
            assertEquals("cross-mount", mfs.readBytes("/alias").decodeToString())
            assertEquals(FileType.SYMLINK, mfs.statLink("/alias").type)
            assertEquals(FileType.REGULAR, mfs.stat("/alias").type)
        }

    @Test
    fun `createSymlink on read-only mount throws`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/ro", InMemoryFs(), FsLabel.HOST_BORROW, readOnly = true),
                    ),
                )
            assertFailsWith<ReadOnlyMountException> { mfs.createSymlink("/ro/link", "/anywhere") }
        }

    @Test
    fun `symlink cycle across mounts throws SymlinkLoop`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/m", InMemoryFs(), FsLabel.USER),
                    ),
                )
            mfs.createSymlink("/a", "/m/b")
            mfs.createSymlink("/m/b", "/a")
            assertFailsWith<SymlinkLoop> { mfs.stat("/a") }
        }

    @Test
    fun `hard link across mounts throws CrossMountException`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/m", InMemoryFs(), FsLabel.USER),
                    ),
                )
            mfs.writeBytes("/m/file", "x".encodeToByteArray())
            assertFailsWith<CrossMountException> { mfs.createHardLink("/copy", "/m/file") }
        }

    @Test
    fun `hard link within the same mount works`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/m", InMemoryFs(), FsLabel.USER),
                    ),
                )
            mfs.writeBytes("/m/orig", "v1".encodeToByteArray())
            mfs.createHardLink("/m/copy", "/m/orig")
            // Routed to the /m mount; FileNode shared.
            assertEquals(2, mfs.stat("/m/orig").nlink)
            assertEquals("v1", mfs.readBytes("/m/copy").decodeToString())
        }

    @Test
    fun `readSymlink routes to owning mount`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/m", InMemoryFs(), FsLabel.USER),
                    ),
                )
            mfs.createSymlink("/m/link", "../somewhere")
            assertEquals("../somewhere", mfs.readSymlink("/m/link"))
        }
}
