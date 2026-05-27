package com.accucodeai.kash.fs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryFsTest {
    @Test
    fun defaultLayoutExists() =
        runTest {
            val fs = InMemoryFs()
            listOf("/", "/bin", "/usr/bin", "/tmp", "/home/user").forEach {
                assertTrue(fs.isDirectory(it), "expected directory: $it")
            }
        }

    @Test
    fun writeThenReadRoundTrips() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/home/user/note.txt", "hello\n".encodeToByteArray())
            assertTrue(fs.exists("/home/user/note.txt"))
            assertFalse(fs.isDirectory("/home/user/note.txt"))
            assertContentEquals("hello\n".encodeToByteArray(), fs.readBytes("/home/user/note.txt"))
        }

    @Test
    fun writeAutoCreatesParents() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/a/b/c/d.txt", "x".encodeToByteArray())
            assertTrue(fs.isDirectory("/a/b/c"))
        }

    @Test
    fun appendBytesConcatenates() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/tmp/log", "one\n".encodeToByteArray())
            fs.appendBytes("/tmp/log", "two\n".encodeToByteArray())
            assertEquals("one\ntwo\n", fs.readBytes("/tmp/log").decodeToString())
        }

    @Test
    fun missingFileThrows() =
        runTest {
            val fs = InMemoryFs()
            assertFailsWith<FileNotFound> { fs.readBytes("/nope") }
        }

    @Test
    fun listReturnsTopLevelEntriesOnly() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data/a.txt", ByteArray(0))
            fs.writeBytes("/data/sub/b.txt", ByteArray(0))
            assertEquals(listOf("a.txt", "sub"), fs.list("/data"))
        }

    @Test
    fun listOnFileFails() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x", ByteArray(0))
            assertFailsWith<NotADirectory> { fs.list("/x") }
        }

    @Test
    fun removeDeletesFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/x", ByteArray(0))
            fs.remove("/x")
            assertFalse(fs.exists("/x"))
        }

    // ---- symlinks ----

    @Test
    fun symlinkCreateReadStat() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/target.txt", "hi".encodeToByteArray())
            fs.createSymlink("/link", "/target.txt")
            assertEquals("/target.txt", fs.readSymlink("/link"))
            // lstat sees the link itself.
            val l = fs.statLink("/link")
            assertEquals(FileType.SYMLINK, l.type)
            assertEquals("/target.txt", l.symlinkTarget)
            // stat follows.
            val s = fs.stat("/link")
            assertEquals(FileType.REGULAR, s.type)
        }

    @Test
    fun symlinkFollowOnRead() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "payload".encodeToByteArray())
            fs.createSymlink("/alias", "/data")
            assertEquals("payload", fs.readBytes("/alias").decodeToString())
        }

    @Test
    fun symlinkRelativeTargetResolvesAgainstLinkParent() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/home/user/data.txt", "x".encodeToByteArray())
            fs.createSymlink("/home/user/alias", "data.txt")
            assertEquals("x", fs.readBytes("/home/user/alias").decodeToString())
        }

    @Test
    fun symlinkRemoveDoesNotFollow() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            fs.createSymlink("/alias", "/data")
            fs.remove("/alias")
            assertFalse(fs.exists("/alias"))
            // Target is untouched.
            assertTrue(fs.exists("/data"))
        }

    @Test
    fun symlinkCycleThrowsLoop() =
        runTest {
            val fs = InMemoryFs()
            fs.createSymlink("/a", "/b")
            fs.createSymlink("/b", "/a")
            assertFailsWith<SymlinkLoop> { fs.stat("/a") }
        }

    @Test
    fun symlinkDanglingThrowsFileNotFound() =
        runTest {
            val fs = InMemoryFs()
            fs.createSymlink("/dangle", "/missing")
            assertFailsWith<FileNotFound> { fs.stat("/dangle") }
            // lstat still works.
            assertEquals(FileType.SYMLINK, fs.statLink("/dangle").type)
        }

    @Test
    fun readSymlinkOnNonLinkThrowsNotASymlink() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/regular", "x".encodeToByteArray())
            assertFailsWith<NotASymlink> { fs.readSymlink("/regular") }
        }

    @Test
    fun createSymlinkRejectsExisting() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/existing", "x".encodeToByteArray())
            assertFailsWith<RuntimeException> { fs.createSymlink("/existing", "/elsewhere") }
        }

    @Test
    fun mkdirsRejectsPathCollidingWithSymlink() =
        runTest {
            val fs = InMemoryFs()
            fs.createSymlink("/blocked", "/wherever")
            assertFailsWith<RuntimeException> { fs.mkdirs("/blocked/under") }
        }

    @Test
    fun snapshotRoundTripPreservesSymlinks() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "v".encodeToByteArray())
            fs.createSymlink("/link", "/data")
            val snap = fs.snapshot()
            val restored = InMemoryFs(snap)
            assertEquals("/data", restored.readSymlink("/link"))
            assertEquals("v", restored.readBytes("/link").decodeToString())
        }

    // ---- hard links ----

    @Test
    fun hardLinkSharesContent() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/orig", "v1".encodeToByteArray())
            fs.createHardLink("/copy", "/orig")
            // Both paths see the same content.
            assertEquals("v1", fs.readBytes("/copy").decodeToString())
            // Writing through one is visible through the other.
            fs.writeBytes("/orig", "v2".encodeToByteArray())
            assertEquals("v2", fs.readBytes("/copy").decodeToString())
            // nlink == 2 on both ends.
            assertEquals(2, fs.stat("/orig").nlink)
            assertEquals(2, fs.stat("/copy").nlink)
        }

    @Test
    fun hardLinkRemoveDecrementsNlinkContentStaysAlive() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/orig", "payload".encodeToByteArray())
            fs.createHardLink("/copy", "/orig")
            fs.remove("/orig")
            assertFalse(fs.exists("/orig"))
            // Content survives via the remaining link.
            assertEquals("payload", fs.readBytes("/copy").decodeToString())
            assertEquals(1, fs.stat("/copy").nlink)
        }

    @Test
    fun hardLinkToDirectoryRejected() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/dir")
            assertFailsWith<RuntimeException> { fs.createHardLink("/aliasdir", "/dir") }
        }

    @Test
    fun hardLinkToMissingSourceRejected() =
        runTest {
            val fs = InMemoryFs()
            assertFailsWith<FileNotFound> { fs.createHardLink("/alias", "/missing") }
        }

    @Test
    fun hardLinkExistingLinkPathRejected() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/src", "x".encodeToByteArray())
            fs.writeBytes("/dst", "y".encodeToByteArray())
            assertFailsWith<RuntimeException> { fs.createHardLink("/dst", "/src") }
        }

    // ---- trailing slash (POSIX ENOTDIR) ----

    @Test
    fun trailingSlashOnRegularFileFailsToOpen() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/file.txt", "x".encodeToByteArray())
            assertFailsWith<NotADirectory> { fs.source("/file.txt/") }
        }

    @Test
    fun trailingSlashOnSymlinkToFileFailsToOpen() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            fs.createSymlink("/link", "/data")
            assertFailsWith<NotADirectory> { fs.source("/link/") }
        }

    @Test
    fun trailingSlashOnSinkFailsBeforeCreating() =
        runTest {
            val fs = InMemoryFs()
            assertFailsWith<NotADirectory> { fs.sink("/newfile/") }
            assertFalse(fs.exists("/newfile"))
        }

    // ---- O_NOFOLLOW ----

    @Test
    fun sourceNoFollowRejectsSymlinkAcceptsRegular() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            fs.createSymlink("/link", "/data")
            assertFailsWith<SymlinkLoop> { fs.sourceNoFollow("/link") }
            // Regular file still opens.
            fs.sourceNoFollow("/data").close()
        }

    @Test
    fun sinkNoFollowRejectsSymlink() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            fs.createSymlink("/link", "/data")
            assertFailsWith<SymlinkLoop> { fs.sinkNoFollow("/link") }
        }

    @Test
    fun symlinkModePinnedTo777() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/data", "x".encodeToByteArray())
            fs.createSymlink("/link", "/data")
            assertEquals(0b111_111_111, fs.statLink("/link").mode)
        }

    @Test
    fun hardLinkNoFollowOnSymlink() =
        runTest {
            // Linux link(2) default: a hard link whose target is a
            // symlink creates a second name for the SYMLINK entry, not
            // its target. lstat on both names must report SYMLINK.
            val fs = InMemoryFs()
            fs.writeBytes("/data", "payload".encodeToByteArray())
            fs.createSymlink("/orig", "/data")
            fs.createHardLink("/dup", "/orig")
            assertEquals(FileType.SYMLINK, fs.statLink("/dup").type)
            assertEquals("/data", fs.readSymlink("/dup"))
        }

    @Test
    fun autoParentDirsUseFileModeSearchBits() =
        runTest {
            // Auto-created parents on `> /a/b/c` carry execute/search
            // bits derived from the file's mode (which the caller
            // already umask-masked). 0o644 file → 0o755 parents.
            val fs = InMemoryFs()
            fs.sink("/auto/sub/file", append = false, mode = 0b110_100_100).close()
            val parent = fs.stat("/auto/sub")
            assertEquals(0b111_101_101, parent.mode)
        }

    @Test
    fun pathNormalization() =
        runTest {
            assertEquals("/a/b", Paths.normalize("/a//b/"))
            assertEquals("/a/c", Paths.normalize("/a/b/../c"))
            assertEquals("/", Paths.normalize("/.."))
            assertEquals("/x", Paths.resolve("/home/user", "/x"))
            assertEquals("/home/user/x", Paths.resolve("/home/user", "x"))
            assertEquals("/home/user", Paths.resolve("/home/user/sub", ".."))
            assertEquals("/a/b", Paths.parent("/a/b/c.txt"))
            assertEquals("c.txt", Paths.basename("/a/b/c.txt"))
        }
}
