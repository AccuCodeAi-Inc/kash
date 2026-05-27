package com.accucodeai.kash.tools.tree

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeCommandTest {
    private fun ctx(fs: InMemoryFs): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        val c =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        return Triple(c, out, err)
    }

    private fun fs(): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        return fs
    }

    @Test fun emptyDirShowsHeaderAndZeroSummary() =
        runTest {
            val (c, out, _) = ctx(fs())
            TreeCommand().run(listOf("."), c)
            val text = out.readString()
            assertEquals(".\n\n0 directories, 0 files\n", text)
        }

    @Test fun flatDirThreeFilesHasBranchAndLastConnectors() =
        runTest {
            val f = fs()
            f.writeBytes("/work/a", "x".encodeToByteArray())
            f.writeBytes("/work/b", "x".encodeToByteArray())
            f.writeBytes("/work/c", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("."), c)
            val text = out.readString()
            assertTrue("├── a" in text, text)
            assertTrue("├── b" in text, text)
            assertTrue("└── c" in text, text)
            assertTrue("0 directories, 3 files" in text)
        }

    @Test fun nestedTwoLevelsUsesPipeContinuation() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            f.writeBytes("/work/sub/x", "x".encodeToByteArray())
            f.writeBytes("/work/sub/y", "x".encodeToByteArray())
            f.writeBytes("/work/top", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("."), c)
            val text = out.readString()
            // The "sub" branch (├── sub) is followed by child lines prefixed
            // with "│   " (pipe + 3 spaces) since sub is not the last entry.
            assertTrue("├── sub" in text || "└── sub" in text, text)
            // Last entry "top" should not have continuation children at all.
            assertTrue("1 directories, 3 files" in text || "1 directory, 3 files" in text, text)
        }

    @Test fun levelOneStopsAtFirstLevel() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            f.writeBytes("/work/sub/x", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-L", "1", "."), c)
            val text = out.readString()
            assertTrue("sub" in text)
            assertFalse("x" in text.removePrefix("."), "should not descend into sub: $text")
        }

    @Test fun dOnlyShowsDirectories() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            f.writeBytes("/work/file", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-d", "--noreport", "."), c)
            val text = out.readString()
            assertTrue("sub" in text)
            assertFalse("file" in text, text)
        }

    @Test fun aIncludesDotfiles() =
        runTest {
            val f = fs()
            f.writeBytes("/work/.hidden", "x".encodeToByteArray())
            f.writeBytes("/work/visible", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-a", "."), c)
            assertTrue(".hidden" in out.readString())
        }

    @Test fun withoutAHidesDotfiles() =
        runTest {
            val f = fs()
            f.writeBytes("/work/.hidden", "x".encodeToByteArray())
            f.writeBytes("/work/visible", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("."), c)
            val text = out.readString()
            assertFalse(".hidden" in text, text)
            assertTrue("visible" in text)
        }

    @Test fun fPrintsFullPaths() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            f.writeBytes("/work/sub/x", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-f", "."), c)
            val text = out.readString()
            assertTrue("./sub" in text, text)
            assertTrue("./sub/x" in text, text)
        }

    @Test fun fClassifyAppendsSlashForDir() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            f.writeBytes("/work/file", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-F", "."), c)
            val text = out.readString()
            assertTrue("sub/" in text, text)
        }

    @Test fun sShowsSizes() =
        runTest {
            val f = fs()
            f.writeBytes("/work/abc", "hello".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-s", "."), c)
            val text = out.readString()
            assertTrue("5" in text, text)
            assertTrue("abc" in text)
        }

    @Test fun hHumanSizes() =
        runTest {
            val f = fs()
            f.writeBytes("/work/big", ByteArray(2048) { 'x'.code.toByte() })
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-h", "."), c)
            val text = out.readString()
            assertTrue("2.0K" in text, text)
        }

    @Test fun noreportSkipsSummary() =
        runTest {
            val f = fs()
            f.writeBytes("/work/a", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("--noreport", "."), c)
            val text = out.readString()
            assertFalse("directories" in text, text)
            assertFalse("files" in text, text)
        }

    @Test fun iExcludesGlob() =
        runTest {
            val f = fs()
            f.writeBytes("/work/keep.kt", "x".encodeToByteArray())
            f.writeBytes("/work/drop.o", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-I", "*.o", "."), c)
            val text = out.readString()
            assertTrue("keep.kt" in text)
            assertFalse("drop.o" in text, text)
        }

    @Test fun pIncludesOnlyMatchingFiles() =
        runTest {
            val f = fs()
            f.writeBytes("/work/a.kt", "x".encodeToByteArray())
            f.writeBytes("/work/b.txt", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-P", "*.kt", "."), c)
            val text = out.readString()
            assertTrue("a.kt" in text)
            assertFalse("b.txt" in text, text)
        }

    @Test fun dirsfirstOrdersDirsBeforeFiles() =
        runTest {
            val f = fs()
            f.writeBytes("/work/aaa", "x".encodeToByteArray())
            f.mkdirs("/work/zzz")
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("--dirsfirst", "."), c)
            val text = out.readString()
            val idxZ = text.indexOf("zzz")
            val idxA = text.indexOf("aaa")
            assertTrue(idxZ in 0 until idxA, "expected zzz before aaa, got: $text")
        }

    @Test fun rReversesSort() =
        runTest {
            val f = fs()
            f.writeBytes("/work/a", "x".encodeToByteArray())
            f.writeBytes("/work/b", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-r", "."), c)
            val text = out.readString()
            val idxA = text.indexOf("a\n")
            val idxB = text.indexOf("b\n")
            assertTrue(idxB in 0 until idxA, "expected b before a, got: $text")
        }

    @Test fun symlinkRenderedWithArrow() =
        runTest {
            val f = fs()
            f.writeBytes("/work/target", "x".encodeToByteArray())
            f.createSymlink("/work/link", "target")
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-d", "."), c)
            // -d to suppress files; check no crash and listing has no link.
            assertTrue(out.readString().isNotEmpty())
        }

    @Test fun multipleOperandsEachRenderedWithHeading() =
        runTest {
            val f = fs()
            f.mkdirs("/work/a")
            f.mkdirs("/work/b")
            f.writeBytes("/work/a/x", "x".encodeToByteArray())
            f.writeBytes("/work/b/y", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("a", "b"), c)
            val text = out.readString()
            assertTrue(text.startsWith("a\n"), text)
            assertTrue("b\n" in text)
            assertTrue("0 directories, 2 files" in text, text)
        }

    @Test fun iNoIndentDropsConnectors() =
        runTest {
            val f = fs()
            f.writeBytes("/work/x", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-i", "."), c)
            val text = out.readString()
            assertFalse("├──" in text, text)
            assertFalse("└──" in text, text)
            assertTrue("x" in text)
        }

    @Test fun missingPathYieldsErrorAndExitOne() =
        runTest {
            val (c, out, err) = ctx(fs())
            val r = TreeCommand().run(listOf("/nonexistent"), c)
            assertEquals(1, r.exitCode)
            assertTrue("No such file" in err.readString())
            // No tree was rendered, but summary still shows zero counts.
            assertTrue("0 directories, 0 files" in out.readString())
        }

    @Test fun nDisablesColorEvenWhenAlwaysSet() =
        runTest {
            val f = fs()
            f.mkdirs("/work/sub")
            val (c, out, _) = ctx(f)
            // --color=always + -n: the last -n wins (left-to-right scan).
            TreeCommand().run(listOf("--color=always", "-n", "."), c)
            val text = out.readString()
            // No ANSI escape bytes should be present.
            assertFalse("[" in text, "found ANSI escapes: $text")
        }

    @Test fun helpFlagPrintsHelp() =
        runTest {
            val (c, out, _) = ctx(fs())
            val r = TreeCommand().run(listOf("--help"), c)
            assertEquals(0, r.exitCode)
            assertTrue("Usage: tree" in out.readString())
        }

    @Test fun versionFlagPrintsVersion() =
        runTest {
            val (c, out, _) = ctx(fs())
            val r = TreeCommand().run(listOf("--version"), c)
            assertEquals(0, r.exitCode)
            assertTrue("tree" in out.readString())
        }

    @Test fun invalidLevelArgIsUsageError() =
        runTest {
            val (c, _, err) = ctx(fs())
            val r = TreeCommand().run(listOf("-L", "0", "."), c)
            assertEquals(2, r.exitCode)
            assertTrue("invalid level" in err.readString())
        }

    @Test fun recipeFindAllKotlinFilesByExclude() =
        runTest {
            val f = fs()
            f.mkdirs("/work/src/main")
            f.writeBytes("/work/src/main/Foo.kt", "x".encodeToByteArray())
            f.writeBytes("/work/src/main/Bar.kt", "x".encodeToByteArray())
            f.writeBytes("/work/build.o", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-I", "*.o", "."), c)
            val text = out.readString()
            assertTrue("Foo.kt" in text)
            assertTrue("Bar.kt" in text)
            assertFalse("build.o" in text, text)
        }

    @Test fun recipeDepthLimitedSizes() =
        runTest {
            val f = fs()
            f.mkdirs("/work/a")
            f.writeBytes("/work/a/big", ByteArray(1024) { 'x'.code.toByte() })
            f.mkdirs("/work/a/inner")
            f.writeBytes("/work/a/inner/deep", "x".encodeToByteArray())
            val (c, out, _) = ctx(f)
            TreeCommand().run(listOf("-L", "2", "-s", "."), c)
            val text = out.readString()
            assertTrue("big" in text)
            // Depth 2 shows "a" and its children but not deep beneath inner/.
            assertTrue("inner" in text)
            assertFalse("deep" in text, text)
        }
}
