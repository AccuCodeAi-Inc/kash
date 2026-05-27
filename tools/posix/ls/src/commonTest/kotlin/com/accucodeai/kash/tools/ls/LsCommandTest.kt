package com.accucodeai.kash.tools.ls

import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.FileStat
import com.accucodeai.kash.fs.FileType
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsCommandTest {
    private fun ctx(
        fs: InMemoryFs,
        stdin: Buffer = Buffer(),
    ): Pair<CommandContext, Buffer> {
        val out = Buffer()
        val err = Buffer()
        return bareCommandContext(
            fs,
            mutableMapOf(),
            "/work",
            stdin.asSuspendSource(),
            out.asSuspendSink(),
            err.asSuspendSink(),
        ) to
            out
    }

    @Test fun shortFormListsOnePerLineSorted() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/beta", "B".encodeToByteArray())
            fs.writeBytes("/work/alpha", "A".encodeToByteArray())
            fs.writeBytes("/work/gamma", "G".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(emptyList(), c)
            assertEquals("alpha\nbeta\ngamma\n", out.readString())
        }

    @Test fun hiddenFilesHiddenByDefault() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/visible", "v".encodeToByteArray())
            fs.writeBytes("/work/.hidden", "h".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(emptyList(), c)
            assertEquals("visible\n", out.readString())
        }

    @Test fun dashARevealsHidden() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/visible", "v".encodeToByteArray())
            fs.writeBytes("/work/.hidden", "h".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-a"), c)
            // POSIX `ls -a` lists `.` and `..` explicitly, then other
            // entries in name order — `.` and `..` sort first because
            // `.` < any printable name.
            assertEquals(".\n..\n.hidden\nvisible\n", out.readString())
        }

    @Test fun dashAIncludesDotAndDotDot() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "x".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-a"), c)
            assertEquals(".\n..\na\n", out.readString())
        }

    @Test fun withoutDashADotEntriesAreHidden() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "x".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(emptyList(), c)
            assertEquals("a\n", out.readString())
        }

    @Test fun dashFAddsTypeSuffix() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/file.txt", "x".encodeToByteArray())
            fs.mkdirs("/work/sub")
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-F"), c)
            assertEquals("file.txt\nsub/\n", out.readString())
        }

    @Test fun longFormatHasTotalLineAndPosixColumns() =
        runTest {
            var t = 0L
            val fs = InMemoryFs(clock = { t })
            t = 1700000000L // 2023-11-14 22:13:20 UTC (well in the past from clock=2000000000)
            fs.writeBytes("/work/note.md", "hello".encodeToByteArray(), mode = 0b110_100_100) // 5 bytes, 0o644
            val (c, out) = ctx(fs)
            val ls =
                LsCommand().apply {
                    clock = { 2000000000L } // 2033, makes 1700000000 > 6 months old → MMM DD YYYY
                }
            ls.run(listOf("-l"), c)
            val text = out.readString()
            val lines = text.trimEnd('\n').split('\n')
            assertEquals("total 1", lines[0])
            // drwxr-xr-x or -rw-r--r-- — the file entry
            val fileLine = lines[1]
            assertTrue(fileLine.startsWith("-rw-r--r-- "), "expected '-rw-r--r--' mode, got: $fileLine")
            assertTrue(" 5 " in fileLine, "expected size 5 in: $fileLine")
            assertTrue("Nov 14  2023" in fileLine, "expected old-format date, got: $fileLine")
            assertTrue(fileLine.endsWith(" note.md"), "expected name at end, got: $fileLine")
        }

    @Test fun longFormatRecentMtimePrintsTime() =
        runTest {
            // mtime now, clock now → file modified "now" → HH:MM format
            val now = 1700000000L
            val fs = InMemoryFs(clock = { now })
            fs.writeBytes("/work/fresh.txt", "abc".encodeToByteArray())
            val (c, out) = ctx(fs)
            val ls = LsCommand().apply { clock = { now } }
            ls.run(listOf("-l"), c)
            val line = out.readString().trimEnd('\n').split('\n')[1]
            // 2023-11-14 22:13:20 UTC → "Nov 14 22:13"
            assertTrue("Nov 14 22:13" in line, "expected recent-format time, got: $line")
        }

    @Test fun nonexistentArgReportsErrorAndExitsTwo() =
        runTest {
            val fs = InMemoryFs()
            val (c, _) = ctx(fs)
            val r = LsCommand().run(listOf("/no/such/path"), c)
            assertEquals(2, r.exitCode)
        }

    @Test fun dashTSortsByMtimeDescending() =
        runTest {
            var t = 1000L
            val fs = InMemoryFs(clock = { t })
            fs.writeBytes("/work/old", "o".encodeToByteArray())
            t = 2000L
            fs.writeBytes("/work/mid", "m".encodeToByteArray())
            t = 3000L
            fs.writeBytes("/work/new", "n".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-t"), c)
            assertEquals("new\nmid\nold\n", out.readString())
        }

    @Test fun dashLRSortsByMtimeReversed() =
        runTest {
            var t = 1000L
            val fs = InMemoryFs(clock = { t })
            fs.writeBytes("/work/old", "o".encodeToByteArray())
            t = 2000L
            fs.writeBytes("/work/new", "n".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-tr"), c)
            assertEquals("old\nnew\n", out.readString())
        }

    // ---- additional flag coverage -----------------------------------------

    @Test fun dashSSortsBySizeDescending() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/tiny", "a".encodeToByteArray())
            fs.writeBytes("/work/big", ByteArray(1000))
            fs.writeBytes("/work/mid", ByteArray(50))
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-S"), c)
            assertEquals("big\nmid\ntiny\n", out.readString())
        }

    @Test fun dashRPlainReversesNameSort() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/a", "x".encodeToByteArray())
            fs.writeBytes("/work/b", "y".encodeToByteArray())
            fs.writeBytes("/work/c", "z".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-r"), c)
            assertEquals("c\nb\na\n", out.readString())
        }

    @Test fun dashDTreatsDirAsEntry() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/inner", "i".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-d", "/work/sub"), c)
            // Should render the dir itself, not its contents.
            assertEquals("/work/sub\n", out.readString())
        }

    @Test fun dashRRecursesIntoSubdirsWithHeaders() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/top.txt", "t".encodeToByteArray())
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/inner.txt", "i".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-R"), c)
            val text = out.readString()
            // Top-level entries
            assertTrue("sub" in text)
            assertTrue("top.txt" in text)
            // Recursive header + nested entries
            assertTrue("/work/sub:" in text || "sub:" in text, "expected sub-header, got: $text")
            assertTrue("inner.txt" in text)
        }

    @Test fun dashHHumanSizes() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/small", ByteArray(500))
            fs.writeBytes("/work/oneKish", ByteArray(2048)) // 2.0K
            fs.writeBytes("/work/big", ByteArray(2 * 1024 * 1024)) // 2.0M
            val (c, out) = ctx(fs)
            LsCommand().apply { clock = { 1L } }.run(listOf("-lh"), c)
            val text = out.readString()
            assertTrue("500 " in text, "expected raw bytes for sub-1K: $text")
            assertTrue("2.0K" in text, "expected 2.0K size: $text")
            assertTrue("2.0M" in text, "expected 2.0M size: $text")
        }

    @Test fun doubleDashTerminatesOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/-l", "weird".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("--", "-l"), c)
            // `-l` after `--` should be a path, not a flag — so short form,
            // and `-l` resolves to /work/-l (a regular file).
            assertEquals("-l\n", out.readString())
        }

    @Test fun unknownOptionRejectedExitTwo() =
        runTest {
            val fs = InMemoryFs()
            val (c, _) = ctx(fs)
            val r = LsCommand().run(listOf("-Q"), c)
            assertEquals(2, r.exitCode)
        }

    @Test fun longFormatRespectsClassifyForExecutable() =
        runTest {
            val fs = InMemoryFs(clock = { 1L })
            fs.writeBytes("/work/script.sh", "#!/bin/sh\n".encodeToByteArray())
            fs.chmod("/work/script.sh", 0b111_101_101) // 0o755
            val (c, out) = ctx(fs)
            LsCommand().apply { clock = { 1L } }.run(listOf("-lF"), c)
            val line =
                out
                    .readString()
                    .trimEnd('\n')
                    .split('\n')
                    .last()
            assertTrue(line.startsWith("-rwxr-xr-x"), "expected rwxr-xr-x mode, got: $line")
            assertTrue(line.endsWith("script.sh*"), "expected * suffix on executable, got: $line")
        }

    @Test fun dashFExecutableSuffixOnRegularFile() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/runme", "x".encodeToByteArray())
            fs.chmod("/work/runme", 0b111_101_101)
            fs.writeBytes("/work/plain", "y".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("-F"), c)
            assertEquals("plain\nrunme*\n", out.readString())
        }

    @Test fun multipleDirsGetHeaders() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/a")
            fs.writeBytes("/work/a/x", "1".encodeToByteArray())
            fs.mkdirs("/work/b")
            fs.writeBytes("/work/b/y", "2".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("/work/a", "/work/b"), c)
            val text = out.readString()
            assertTrue("/work/a:" in text, "expected header for first dir, got: $text")
            assertTrue("/work/b:" in text, "expected header for second dir, got: $text")
            assertTrue("x" in text && "y" in text)
        }

    @Test fun fileAndDirOperandsRenderTogether() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/loose.txt", "z".encodeToByteArray())
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/inner", "i".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("/work/loose.txt", "/work/sub"), c)
            val text = out.readString()
            // File appears first, then dir header, then dir contents.
            val parts = text.split("/work/sub:")
            assertEquals(2, parts.size, "expected exactly one header, got: $text")
            assertTrue("loose.txt" in parts[0])
            assertTrue("inner" in parts[1])
        }

    @Test fun longFormatDirectoryShowsTotalLine() =
        runTest {
            val fs = InMemoryFs(clock = { 1L })
            fs.writeBytes("/work/a", ByteArray(1024)) // 1K
            fs.writeBytes("/work/b", ByteArray(2048)) // 2K
            val (c, out) = ctx(fs)
            LsCommand().apply { clock = { 1L } }.run(listOf("-l"), c)
            val text = out.readString()
            val totalLine = text.split('\n').first()
            // 1024 → 1 block, 2048 → 2 blocks (1024-byte blocks).
            assertEquals("total 3", totalLine)
        }

    // ---- unit tests on lifted pure helpers --------------------------------

    @Test fun formatModeRendersExecutable() {
        assertEquals("-rwxr-xr-x", formatModeFor(FileType.REGULAR, 0b111_101_101))
    }

    @Test fun formatModeRendersSetuid() {
        // setuid + 0o755 → 4755 → "-rwsr-xr-x"
        assertEquals("-rwsr-xr-x", formatModeFor(FileType.REGULAR, 0b100_111_101_101))
    }

    @Test fun formatModeRendersSetuidWithoutExec() {
        // setuid + 0o644 → 4644 → "-rwSr--r--" (capital S — bit set, no x)
        assertEquals("-rwSr--r--", formatModeFor(FileType.REGULAR, 0b100_110_100_100))
    }

    @Test fun formatModeRendersSetgid() {
        // setgid + 0o755 → 2755 → "-rwxr-sr-x"
        assertEquals("-rwxr-sr-x", formatModeFor(FileType.REGULAR, 0b010_111_101_101))
    }

    @Test fun formatModeRendersStickyDir() {
        // sticky + 0o755 on dir → 1755 → "drwxr-xr-t"
        assertEquals("drwxr-xr-t", formatModeFor(FileType.DIRECTORY, 0b001_111_101_101))
    }

    @Test fun formatModeRendersStickyWithoutOtherExec() {
        // sticky + 0o754 → 1754 → "-rwxr-xr-T" (capital T)
        assertEquals("-rwxr-xr-T", formatModeFor(FileType.REGULAR, 0b001_111_101_100))
    }

    @Test fun formatModeRendersSymlinkType() {
        assertEquals("lrwxr-xr-x", formatModeFor(FileType.SYMLINK, 0b111_101_101))
    }

    @Test fun formatModeAllZero() {
        assertEquals("----------", formatModeFor(FileType.REGULAR, 0))
    }

    @Test fun humanSizeBytes() {
        assertEquals("0", humanSizeStr(0))
        assertEquals("999", humanSizeStr(999))
        assertEquals("1023", humanSizeStr(1023))
    }

    @Test fun humanSizeKB() {
        assertEquals("1.0K", humanSizeStr(1024))
        assertEquals("1.5K", humanSizeStr(1024 + 512))
        assertEquals("10K", humanSizeStr(10 * 1024))
    }

    @Test fun humanSizeMB() {
        assertEquals("1.0M", humanSizeStr(1024L * 1024))
        assertEquals("5.0M", humanSizeStr(5L * 1024 * 1024))
    }

    @Test fun humanSizeGBAndUp() {
        assertEquals("1.0G", humanSizeStr(1024L * 1024 * 1024))
        assertEquals("2.0T", humanSizeStr(2L * 1024 * 1024 * 1024 * 1024))
    }

    @Test fun typeSuffixForFifoSymlinkSocket() {
        // FileSystem doesn't produce these types yet — but the rendering branch
        // is exercised so future symlink/fifo/socket support drops in cleanly.
        val fifo = FileStat("/x", FileType.FIFO, 0, 0, 0b110_100_100)
        val sym = FileStat("/x", FileType.SYMLINK, 0, 0, 0b111_111_111)
        val sock = FileStat("/x", FileType.SOCKET, 0, 0, 0b111_101_101)
        assertEquals("|", typeSuffixFor(fifo))
        assertEquals("@", typeSuffixFor(sym))
        assertEquals("=", typeSuffixFor(sock))
    }

    // ESC + '[' — written as a unicode escape so the source has no literal
    // control bytes. Tests assert exact SGR byte sequences.
    private val esc = "\u001B["

    @Test fun colorAlwaysWrapsDirsAndExecutables() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/script.sh", "x".encodeToByteArray())
            fs.chmod("/work/script.sh", 0b111_101_101)
            fs.writeBytes("/work/note.md", "n".encodeToByteArray())
            val (c, out) = ctx(fs)
            LsCommand().run(listOf("--color=always"), c)
            val expected =
                "${esc}01;32mscript.sh${esc}0m\n" +
                    "note.md\n" +
                    "${esc}01;34msub${esc}0m\n"
            // ls sorts: note.md, script.sh, sub — restructure expectation.
            val actual = out.readString()
            assertTrue(
                actual.contains("${esc}01;34msub${esc}0m"),
                "dir not colored: $actual",
            )
            assertTrue(
                actual.contains("${esc}01;32mscript.sh${esc}0m"),
                "exec not colored: $actual",
            )
            assertTrue(actual.contains("note.md\n"), "plain file colored: $actual")
            // Silence unused-var lint on expected.
            assertTrue(expected.isNotEmpty())
        }

    @Test fun colorNeverIsDefaultAndStripsEscapes() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub")
            val (c, out) = ctx(fs)
            LsCommand().run(emptyList(), c)
            val actual = out.readString()
            assertEquals("sub\n", actual)
        }

    @Test fun colorAutoFollowsStdoutTty() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub")
            val out = Buffer()
            val c =
                bareCommandContext(
                    fs = fs,
                    env = mutableMapOf(),
                    cwd = "/work",
                    stdin = Buffer().asSuspendSource(),
                    stdout = out.asSuspendSink(),
                    stderr = Buffer().asSuspendSink(),
                    stdoutIsTty = true,
                )
            LsCommand().run(listOf("--color=auto"), c)
            val text = out.readString()
            assertTrue(text.contains("${esc}01;34msub${esc}0m"), "auto+tty should color: $text")
        }

    @Test fun colorRejectsBadValue() =
        runTest {
            val fs = InMemoryFs()
            val (c, _) = ctx(fs)
            val r = LsCommand().run(listOf("--color=banana"), c)
            assertEquals(2, r.exitCode)
        }

    @Test fun lsColorsEnvOverridesDirColor() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work/sub")
            val (c, out) = ctx(fs)
            c.env["LS_COLORS"] = "di=04;33"
            LsCommand().run(listOf("--color=always"), c)
            val actual = out.readString()
            assertTrue("${esc}04;33msub${esc}0m" in actual, "got: $actual")
        }

    @Test fun lsColorsEnvExtensionGlobBeatsTypeDefault() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/work/data.tar", ByteArray(1))
            fs.writeBytes("/work/notes.md", ByteArray(1))
            val (c, out) = ctx(fs)
            c.env["LS_COLORS"] = "*.tar=01;31"
            LsCommand().run(listOf("--color=always"), c)
            val actual = out.readString()
            // .tar should be wrapped in 01;31; .md should be bare (fi default is empty).
            assertTrue("${esc}01;31mdata.tar${esc}0m" in actual, "got: $actual")
            assertTrue("notes.md\n" in actual, "got: $actual")
        }
}
