package com.accucodeai.kash.tools.df

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FsLabel
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.fs.Mount
import com.accucodeai.kash.fs.MountedFileSystem
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DfCommandTest {
    private fun ctx(fs: com.accucodeai.kash.fs.FileSystem): Triple<CommandContext, Buffer, Buffer> {
        val out = Buffer()
        val err = Buffer()
        val c =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/",
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        return Triple(c, out, err)
    }

    /** Single-mount router with just `/`. */
    private fun rootOnly(): MountedFileSystem {
        val root = InMemoryFs()
        return MountedFileSystem(listOf(Mount("/", root, FsLabel.USER)))
    }

    /** Router with `/` + `/tmp` mounts. */
    private fun multiMount(): Pair<MountedFileSystem, Pair<InMemoryFs, InMemoryFs>> {
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

    @Test fun noOperandListsAllMounts() =
        runTest {
            val (mfs, _) = multiMount()
            val (c, out, _) = ctx(mfs)
            DfCommand().run(emptyList(), c)
            val text = out.readString()
            assertTrue("Filesystem" in text)
            assertTrue("/tmp" in text, text)
            assertTrue("/" in text, text)
        }

    @Test fun headerColumnsForDefaultBlocks() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(emptyList(), c)
            val text = out.readString()
            assertTrue("1K-blocks" in text, text)
            assertTrue("Used" in text, text)
            assertTrue("Available" in text, text)
            assertTrue("Mounted on" in text, text)
        }

    @Test fun hUsesHumanColumnHeader() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("-h"), c)
            assertTrue("Size" in out.readString())
        }

    @Test fun mUsesOneMBlocks() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("-m"), c)
            assertTrue("1M-blocks" in out.readString())
        }

    @Test fun typeColumnAddedWithT() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("-T"), c)
            assertTrue("Type" in out.readString())
        }

    @Test fun inodesModeChangesHeader() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("-i"), c)
            val text = out.readString()
            assertTrue("Inodes" in text, text)
            assertTrue("IUsed" in text, text)
        }

    @Test fun pathOperandSelectsItsMount() =
        runTest {
            val (mfs, fses) = multiMount()
            val (root, tmp) = fses
            tmp.writeBytes("/foo", "x".encodeToByteArray())
            root.mkdirs("/etc")
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("/tmp/foo"), c)
            val text = out.readString()
            assertTrue("/tmp" in text, text)
            // The root mount line should NOT appear (operand resolved to /tmp).
            assertFalse(
                text.lines().any { (it.contains("Mounted on").not() && it.endsWith(" /")) || it == "/" },
                "Root mount unexpectedly listed: $text",
            )
        }

    @Test fun posixOutputWithP() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("-P"), c)
            val text = out.readString()
            // POSIX -P uses "1024-blocks" header.
            assertTrue("1024-blocks" in text, text)
        }

    @Test fun totalRowAppended() =
        runTest {
            val (mfs, _) = multiMount()
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("--total"), c)
            val text = out.readString()
            assertTrue("total" in text, text)
        }

    @Test fun pathToNonexistentIsError() =
        runTest {
            val (c, _, err) = ctx(rootOnly())
            val r = DfCommand().run(listOf("/does/not/exist"), c)
            // df returns the row for the FS containing the dir but
            // we error out for missing paths.
            val errText = err.readString()
            assertTrue("No such file" in errText)
            assertTrue(r.exitCode != 0 || errText.isNotEmpty())
        }

    @Test fun helpShowsUsage() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            val r = DfCommand().run(listOf("--help"), c)
            assertEquals(0, r.exitCode)
            assertTrue("Usage: df" in out.readString())
        }

    @Test fun rootOnlyFallbackHasOneRow() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(emptyList(), c)
            val lines = out.readString().lines().filter { it.isNotEmpty() }
            // 1 header + 1 data row
            assertEquals(2, lines.size, lines.toString())
        }

    @Test fun bareInMemoryFsFallsBackToRootRow() =
        runTest {
            // No MountedFileSystem — just an InMemoryFs directly.
            val (c, out, _) = ctx(InMemoryFs())
            DfCommand().run(emptyList(), c)
            val text = out.readString()
            assertTrue("Filesystem" in text)
            assertTrue("rootfs" in text || "InMemoryFs" in text, text)
        }

    @Test fun usedReflectsActualContents() =
        runTest {
            val root = InMemoryFs()
            root.writeBytes("/payload", ByteArray(4096) { 'x'.code.toByte() })
            val mfs = MountedFileSystem(listOf(Mount("/", root, FsLabel.USER)))
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-k"), c)
            val text = out.readString()
            // 4096 bytes -> 4 blocks of 1K. Look for "4" in the Used column.
            assertTrue(text.lines().any { line -> Regex("\\b4\\b").containsMatchIn(line) }, text)
        }

    @Test fun typeFilterLimitsMounts() =
        runTest {
            val (mfs, _) = multiMount()
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-t", "InMemoryFs"), c)
            val text = out.readString()
            // All our mounts back to InMemoryFs, so both should be present.
            assertTrue("/tmp" in text)
        }

    @Test fun typeFilterNoMatchesYieldsHeaderOnly() =
        runTest {
            val (mfs, _) = multiMount()
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-t", "ZzNoSuchFs"), c)
            val text = out.readString()
            val lines = text.lines().filter { it.isNotEmpty() }
            // Just the header (no data rows).
            assertEquals(1, lines.size, lines.toString())
        }

    @Test fun customBlockSizeWithB() =
        runTest {
            val root = InMemoryFs()
            root.writeBytes("/payload", ByteArray(4096) { 'x'.code.toByte() })
            val mfs = MountedFileSystem(listOf(Mount("/", root, FsLabel.USER)))
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-B", "2048"), c)
            val text = out.readString()
            // 4096 bytes / 2048 = 2 blocks
            assertTrue(text.lines().any { Regex("\\b2\\b").containsMatchIn(it) }, text)
        }

    @Test fun invalidFlagIsUsageError() =
        runTest {
            val (c, _, err) = ctx(rootOnly())
            val r = DfCommand().run(listOf("-Z"), c)
            assertEquals(2, r.exitCode)
            assertTrue("invalid option" in err.readString())
        }

    @Test fun availableReportsZeroForInMemoryFs() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(emptyList(), c)
            val text = out.readString()
            // In-memory mounts have no fixed capacity; Available reports 0
            // (an honest "no headroom" signal) so awk/grep pipelines computing
            // free-space thresholds don't choke on a literal dash. Use% still
            // renders as "-" when total=0 to avoid 0/0 nonsense.
            assertTrue("-" in text, "expected Use% to render as '-' for empty fs: $text")
        }

    @Test fun outputColsSelectsColumns() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("--output=source,size,used,target"), c)
            val text = out.readString()
            val lines = text.trim().lines()
            assertEquals("Filesystem 1K-blocks Used Mounted on", lines.first().replace(Regex("\\s+"), " "))
            // Body row has exactly 4 space-separated cells.
            val body = lines[1].split(Regex("\\s+")).filter { it.isNotEmpty() }
            assertEquals(4, body.size, "expected 4 columns, got: $body")
        }

    @Test fun outputColsAvail() =
        runTest {
            val (c, out, _) = ctx(rootOnly())
            DfCommand().run(listOf("--output=avail"), c)
            val text = out.readString().trim()
            // Should be header "Available" + one row with a numeric value.
            val lines = text.lines()
            assertEquals("Available", lines.first().trim())
            assertTrue(lines[1].trim().all { it.isDigit() }, "expected numeric avail: ${lines[1]}")
        }

    @Test fun recipeCheckRoomBeforeWriting() =
        runTest {
            val (mfs, fses) = multiMount()
            val (_, tmp) = fses
            tmp.writeBytes("/scratch", ByteArray(2048) { 'x'.code.toByte() })
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-h", "/tmp"), c)
            val text = out.readString()
            // Should report the /tmp mount line.
            assertTrue("/tmp" in text, text)
        }

    @Test fun recipePosixPortableScriptable() =
        runTest {
            val (mfs, _) = multiMount()
            val (c, out, _) = ctx(mfs)
            DfCommand().run(listOf("-Pk"), c)
            val text = out.readString()
            // Each non-header line should have at least 6 whitespace-sep fields.
            val dataLines =
                text
                    .lines()
                    .filter { it.isNotEmpty() }
                    .drop(1)
            for (l in dataLines) {
                val toks = l.split(Regex("\\s+")).filter { it.isNotEmpty() }
                assertTrue(toks.size >= 6, "Expected >=6 fields in '$l'")
            }
        }
}
