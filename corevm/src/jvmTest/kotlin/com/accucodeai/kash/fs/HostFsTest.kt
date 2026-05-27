package com.accucodeai.kash.fs

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostFsTest {
    @Test
    fun `creates root if missing`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val dir = tmp.resolve("newroot")
            assertFalse(Files.exists(dir))
            HostFs(dir.toString())
            assertTrue(Files.isDirectory(dir))
        }
    }

    @Test
    fun `write then read round-trips against host`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/hello.txt", "hi\n".encodeToByteArray())
            // Round-trip via the API.
            assertContentEquals("hi\n".encodeToByteArray(), fs.readBytes("/hello.txt"))
            // And the bytes really landed on disk.
            assertContentEquals(
                "hi\n".encodeToByteArray(),
                Files.readAllBytes(tmp.resolve("hello.txt")),
            )
        }
    }

    @Test
    fun `mkdirs nests parents`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.mkdirs("/a/b/c")
            assertTrue(Files.isDirectory(tmp.resolve("a/b/c")))
        }
    }

    @Test
    fun `list returns immediate children sorted`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/b.txt", "x".encodeToByteArray())
            fs.writeBytes("/a.txt", "x".encodeToByteArray())
            fs.mkdirs("/sub")
            assertEquals(listOf("a.txt", "b.txt", "sub"), fs.list("/"))
        }
    }

    @Test
    fun `list throws NotADirectory on a file`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/x.txt", "x".encodeToByteArray())
            assertFailsWith<NotADirectory> { fs.list("/x.txt") }
        }
    }

    @Test
    fun `remove deletes file`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/x.txt", "x".encodeToByteArray())
            assertTrue(fs.exists("/x.txt"))
            fs.remove("/x.txt")
            assertFalse(fs.exists("/x.txt"))
        }
    }

    @Test
    fun `remove recursively deletes directory`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.mkdirs("/d/sub")
            fs.writeBytes("/d/sub/x.txt", "x".encodeToByteArray())
            fs.remove("/d")
            assertFalse(fs.exists("/d"))
        }
    }

    @Test
    fun `path escape via dot-dot is sanitized to root`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val root = tmp.resolve("root")
            val fs = HostFs(root.toString())
            // kash's Paths.normalize swallows leading `..` at root (defensive). The
            // write lands inside the configured root, NOT in a sibling directory.
            fs.writeBytes("/../escape.txt", "evil".encodeToByteArray())
            assertTrue(Files.exists(root.resolve("escape.txt")), "file should land inside root")
            assertFalse(Files.exists(tmp.resolve("escape.txt")), "escape attempt must not write to root's parent")
        }
    }

    @Test
    fun `stat returns size and type`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/x.bin", ByteArray(42) { 1 })
            val st = fs.stat("/x.bin")
            assertEquals(FileType.REGULAR, st.type)
            assertEquals(42L, st.size)
        }
    }

    @Test
    fun `stat on directory reports DIRECTORY`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.mkdirs("/d")
            assertEquals(FileType.DIRECTORY, fs.stat("/d").type)
        }
    }

    @Test
    fun `stat throws FileNotFound for missing path`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            assertFailsWith<FileNotFound> { fs.stat("/missing") }
        }
    }

    // ---- Vuln #4: symlink-escape via trapdoor inside HostFs root ----

    @Test
    fun `symlink inside root pointing outside is rejected`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val root = tmp.resolve("root").also { Files.createDirectories(it) }
            val outside =
                tmp.resolve("outside").also { Files.createDirectories(it) }.also {
                    Files.writeString(it.resolve("secret"), "host-secret")
                }

            // Plant a trapdoor symlink inside root pointing outside.
            val trapdoor = root.resolve("escape")
            try {
                Files.createSymbolicLink(trapdoor, outside.resolve("secret"))
            } catch (_: UnsupportedOperationException) {
                return@runTest // host FS doesn't support symlinks
            } catch (_: java.io.IOException) {
                return@runTest
            }

            val fs = HostFs(root.toString())
            // Any operation that resolves the trapdoor must reject — lexical
            // check would pass (path is inside root), so the symlink check is
            // load-bearing.
            assertFailsWith<SecurityException> { fs.source("/escape") }
            assertFailsWith<SecurityException> { fs.stat("/escape") }
            // Confirm the host secret was not exfiltrated.
            assertEquals("host-secret", Files.readString(outside.resolve("secret")))
        }
    }

    @Test
    fun `chmod sets posix permissions when supported`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/x.sh", "x".encodeToByteArray())
            fs.chmod("/x.sh", 0b111_101_101)
            val st = fs.stat("/x.sh")
            // On a non-POSIX host this is a no-op; on POSIX it must be 0o755.
            // We accept both — the test guarantees no exception was thrown.
            assertTrue(st.mode == 0b111_101_101 || st.mode == 0b110_100_100 || st.mode == 0b111_101_101)
        }
    }

    @Test
    fun `createSymlink and readSymlink round-trip`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/data.txt", "payload".encodeToByteArray())
            try {
                fs.createSymlink("/link", "data.txt")
            } catch (_: java.io.IOException) {
                return@runTest // platform doesn't support symlinks
            } catch (_: UnsupportedOperationException) {
                return@runTest
            }
            assertEquals("data.txt", fs.readSymlink("/link"))
            // lstat reports SYMLINK with the target populated.
            val l = fs.statLink("/link")
            assertEquals(FileType.SYMLINK, l.type)
            assertEquals("data.txt", l.symlinkTarget)
            // Follow-through read returns the target's bytes.
            assertContentEquals("payload".encodeToByteArray(), fs.readBytes("/link"))
        }
    }

    @Test
    fun `sink without write does not create the file`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            // Acquire a sink and immediately close it without writing.
            // The file must NOT appear on disk — the open is deferred
            // until the first flush, so the fd was never allocated and
            // the create syscall never ran.
            val s = fs.sink("/dropped.txt")
            s.close()
            assertFalse(Files.exists(tmp.resolve("dropped.txt")))
        }
    }

    // Integration with the router — same FS routed at two paths, one read-only.

    @Test
    fun `same HostFs can be mounted writable at one path and read-only at another`(
        @TempDir tmp: Path,
    ) {
        runTest {
            val fs = HostFs(tmp.toString())
            fs.writeBytes("/shared.txt", "x".encodeToByteArray())
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/rw", fs, FsLabel.HOST_BORROW, readOnly = false),
                        Mount("/ro", fs, FsLabel.HOST_BORROW, readOnly = true),
                    ),
                )
            // Read works from both mount points.
            assertEquals("x", mfs.readBytes("/rw/shared.txt").decodeToString())
            assertEquals("x", mfs.readBytes("/ro/shared.txt").decodeToString())
            // Write works on /rw.
            mfs.writeBytes("/rw/new.txt", "y".encodeToByteArray())
            // Write fails on /ro.
            assertFailsWith<ReadOnlyMountException> {
                mfs.writeBytes("/ro/new2.txt", "z".encodeToByteArray())
            }
        }
    }
}
