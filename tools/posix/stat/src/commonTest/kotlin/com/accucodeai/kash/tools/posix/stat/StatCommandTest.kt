package com.accucodeai.kash.tools.posix.stat

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private suspend fun runStat(
    fs: InMemoryFs,
    cwd: String = "/",
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = mutableMapOf(),
            cwd = cwd,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = StatCommand().run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class StatCommandTest {
    private suspend fun fixture(): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdirs("/tmp")
        fs.chmod("/tmp", 0b111_101_101) // 0o755
        fs.writeBytes("/tmp/hello.txt", "hello\n".encodeToByteArray())
        fs.chmod("/tmp/hello.txt", 0b110_100_100) // 0o644
        fs.writeBytes("/tmp/empty", ByteArray(0))
        fs.chmod("/tmp/empty", 0b110_100_100)
        fs.setMtime("/tmp/hello.txt", 1_700_000_000L)
        return fs
    }

    @Test fun missingOperand_failsWithUsage() =
        runTest {
            val (rc, _, err) = runStat(fixture())
            assertEquals(1, rc)
            assertTrue("missing operand" in err)
        }

    @Test fun nonexistent_pathPrintsErrorAndExits1() =
        runTest {
            val (rc, out, err) = runStat(fixture(), args = arrayOf("/nope"))
            assertEquals(1, rc)
            assertEquals("", out)
            assertTrue("No such file" in err)
        }

    @Test fun format_name() =
        runTest {
            val (rc, out, _) = runStat(fixture(), args = arrayOf("-c", "%n", "/tmp/hello.txt"))
            assertEquals(0, rc)
            assertEquals("/tmp/hello.txt\n", out)
        }

    @Test fun format_size_file() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%s", "/tmp/hello.txt"))
            assertEquals("6\n", out)
        }

    @Test fun format_size_emptyFile() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%s", "/tmp/empty"))
            assertEquals("0\n", out)
        }

    @Test fun format_fileType_regular() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%F", "/tmp/hello.txt"))
            assertEquals("regular file\n", out)
        }

    @Test fun format_fileType_directory() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%F", "/tmp"))
            assertEquals("directory\n", out)
        }

    @Test fun format_modeOctal_file() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%a", "/tmp/hello.txt"))
            assertEquals("644\n", out)
        }

    @Test fun format_modeOctal_directory() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%a", "/tmp"))
            assertEquals("755\n", out)
        }

    @Test fun format_modeHuman() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%A", "/tmp/hello.txt"))
            assertEquals("-rw-r--r--\n", out)
        }

    @Test fun format_modeHuman_directory() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%A", "/tmp"))
            assertEquals("drwxr-xr-x\n", out)
        }

    @Test fun format_ownerGroup() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%U:%G", "/tmp/hello.txt"))
            assertEquals("user:user\n", out)
        }

    @Test fun format_uidGid_zero() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%u:%g", "/tmp/hello.txt"))
            assertEquals("0:0\n", out)
        }

    @Test fun format_linkCount() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%h", "/tmp/hello.txt"))
            assertEquals("1\n", out)
        }

    @Test fun format_mtimeEpoch() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%Y", "/tmp/hello.txt"))
            assertEquals("1700000000\n", out)
        }

    @Test fun format_mtimeHuman() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%y", "/tmp/hello.txt"))
            // 1_700_000_000 = 2023-11-14 22:13:20 UTC
            assertEquals("2023-11-14 22:13:20\n", out)
        }

    @Test fun format_mtimeHuman_epochZero() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/")
            fs.writeBytes("/f", ByteArray(0))
            val (_, out, _) = runStat(fs, args = arrayOf("-c", "%y", "/f"))
            assertEquals("1970-01-01 00:00:00\n", out)
        }

    @Test fun format_inode_stableForPath() =
        runTest {
            val fs = fixture()
            val (_, a, _) = runStat(fs, args = arrayOf("-c", "%i", "/tmp/hello.txt"))
            val (_, b, _) = runStat(fs, args = arrayOf("-c", "%i", "/tmp/hello.txt"))
            assertEquals(a, b)
            assertTrue(a.trim().toLong() > 0L)
        }

    @Test fun format_literalPercent() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "100%%", "/tmp/hello.txt"))
            assertEquals("100%\n", out)
        }

    @Test fun format_unsupportedStubsToQuestion() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%z", "/tmp/hello.txt"))
            assertEquals("?\n", out)
        }

    @Test fun format_unknownSpec_passesThrough() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c", "%Q", "/tmp/hello.txt"))
            assertEquals("%Q\n", out)
        }

    @Test fun format_escapeSequences_tabAndNewline() =
        runTest {
            val (_, out, _) =
                runStat(fixture(), args = arrayOf("-c", "%n\\t%s\\n%F", "/tmp/hello.txt"))
            assertEquals("/tmp/hello.txt\t6\nregular file\n", out)
        }

    @Test fun format_combined() =
        runTest {
            val (_, out, _) =
                runStat(fixture(), args = arrayOf("-c", "%n %s %F", "/tmp/hello.txt"))
            assertEquals("/tmp/hello.txt 6 regular file\n", out)
        }

    @Test fun format_longOption() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("--format=%s", "/tmp/hello.txt"))
            assertEquals("6\n", out)
        }

    @Test fun format_dashCAttached() =
        runTest {
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c%s", "/tmp/hello.txt"))
            assertEquals("6\n", out)
        }

    @Test fun default_blockMentionsAllFields() =
        runTest {
            val (rc, out, _) = runStat(fixture(), args = arrayOf("/tmp/hello.txt"))
            assertEquals(0, rc)
            assertTrue("File: /tmp/hello.txt" in out, out)
            assertTrue("Size: 6" in out, out)
            assertTrue("regular file" in out, out)
            assertTrue("0644" in out, out)
            assertTrue("-rw-r--r--" in out, out)
            assertTrue("user" in out, out)
            assertTrue("2023-11-14" in out, out)
        }

    @Test fun terse_singleLineLayout() =
        runTest {
            val (rc, out, _) = runStat(fixture(), args = arrayOf("-t", "/tmp/hello.txt"))
            assertEquals(0, rc)
            val line = out.trim()
            // Path size blocks mode uid gid device inode links major minor atime mtime ctime btime blocksize
            val parts = line.split(' ')
            assertEquals(16, parts.size, "got: $line")
            assertEquals("/tmp/hello.txt", parts[0])
            assertEquals("6", parts[1])
            assertEquals("644", parts[3])
            assertEquals("1700000000", parts[12])
        }

    @Test fun multiplePaths_emitsLinePerFileAndContinuesOnError() =
        runTest {
            val (rc, out, err) =
                runStat(
                    fixture(),
                    args = arrayOf("-c", "%n=%s", "/tmp/hello.txt", "/nope", "/tmp/empty"),
                )
            assertEquals(1, rc)
            assertEquals("/tmp/hello.txt=6\n/tmp/empty=0\n", out)
            assertTrue("No such file" in err)
        }

    @Test fun unknownOption_failsWithExit2() =
        runTest {
            val (rc, _, err) = runStat(fixture(), args = arrayOf("-Q", "/tmp/hello.txt"))
            assertEquals(2, rc)
            assertTrue("invalid option" in err || "unknown option" in err, err)
        }

    @Test fun cWithoutValue_failsWithExit2() =
        runTest {
            val (rc, _, err) = runStat(fixture(), args = arrayOf("-c"))
            assertEquals(2, rc)
            assertTrue("requires an argument" in err, err)
        }

    @Test fun doubleDashTerminatesOptions() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/-weird", "abc".encodeToByteArray())
            val (rc, out, _) = runStat(fs, args = arrayOf("-c", "%n %s", "--", "-weird"))
            assertEquals(0, rc)
            assertEquals("-weird 3\n", out)
        }

    @Test fun symlink_dereferenced_byDefault() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d")
            fs.writeBytes("/d/target", "abc".encodeToByteArray())
            fs.createSymlink("/d/link", "target")
            val (_, out, _) = runStat(fs, args = arrayOf("-c", "%F", "/d/link"))
            assertEquals("regular file\n", out)
        }

    @Test fun symlink_noDeref_withDashH() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/d")
            fs.writeBytes("/d/target", "abc".encodeToByteArray())
            fs.createSymlink("/d/link", "target")
            val (_, out, _) = runStat(fs, args = arrayOf("-h", "-c", "%F", "/d/link"))
            assertEquals("symbolic link\n", out)
        }

    @Test fun recipe_extractSizeViaCFormat() =
        runTest {
            // Realistic: a script that checks file size as a number.
            val (_, out, _) = runStat(fixture(), args = arrayOf("-c%s", "/tmp/hello.txt"))
            assertEquals(6, out.trim().toInt())
        }

    @Test fun recipe_buildLsStyleLineViaFormat() =
        runTest {
            // Realistic: assemble a stat-formatted listing.
            val (_, out, _) =
                runStat(
                    fixture(),
                    args = arrayOf("-c", "%A %h %U %G %s %n", "/tmp/hello.txt"),
                )
            assertEquals("-rw-r--r-- 1 user user 6 /tmp/hello.txt\n", out)
        }

    @Test fun recipe_lookupEpochMtimeForComparison() =
        runTest {
            // Realistic: `if [ $(stat -c %Y a) -gt $(stat -c %Y b) ]; then ...`
            val fs = fixture()
            fs.writeBytes("/tmp/newer", ByteArray(0))
            fs.setMtime("/tmp/newer", 2_000_000_000L)
            val (_, a, _) = runStat(fs, args = arrayOf("-c", "%Y", "/tmp/hello.txt"))
            val (_, b, _) = runStat(fs, args = arrayOf("-c", "%Y", "/tmp/newer"))
            assertTrue(b.trim().toLong() > a.trim().toLong())
        }

    @Test fun printf_alsoActsLikeFormat() =
        runTest {
            // GNU --printf doesn't add a trailing newline; we render the same way
            // but writeLine appends one. Just verify the body is right.
            val (_, out, _) =
                runStat(fixture(), args = arrayOf("--printf=%s", "/tmp/hello.txt"))
            assertEquals("6\n", out)
        }
}
