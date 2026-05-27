package com.accucodeai.kash.tools.posix.mktemp

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private suspend fun runMktemp(
    fs: InMemoryFs,
    env: MutableMap<String, String> = mutableMapOf(),
    cwd: String = "/",
    random: Random = Random(0xC0FFEE),
    vararg args: String,
): Triple<Int, String, String> {
    val out = Buffer()
    val err = Buffer()
    val ctx =
        bareCommandContext(
            fs = fs,
            env = env,
            cwd = cwd,
            stdin = Buffer().asSuspendSource(),
            stdout = out.asSuspendSink(),
            stderr = err.asSuspendSink(),
        )
    val res = MktempCommand(random).run(args.toList(), ctx)
    return Triple(res.exitCode, out.readString(), err.readString())
}

class MktempCommandTest {
    private fun fsWithTmp(): InMemoryFs {
        val fs = InMemoryFs()
        fs.mkdirs("/tmp")
        return fs
    }

    @Test fun defaultTemplate_createsFileUnderTmp() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, err) = runMktemp(fs)
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/tmp."), "got '$path'")
            assertEquals("/tmp/tmp.".length + 10, path.length) // tmp. + 10 random
            assertTrue(fs.exists(path))
            assertFalse(fs.isDirectory(path))
        }

    @Test fun dashD_createsDirectory() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, _) = runMktemp(fs, args = arrayOf("-d"))
            assertEquals(0, rc)
            val path = out.trim()
            assertTrue(fs.exists(path))
            assertTrue(fs.isDirectory(path))
        }

    @Test fun customTemplate_inCwd() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, err) = runMktemp(fs, cwd = "/tmp", args = arrayOf("foo.XXXXXX"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/foo."), "got '$path'")
            assertEquals("/tmp/foo.".length + 6, path.length)
            assertTrue(fs.exists(path))
        }

    @Test fun customTemplate_absolute() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, _) = runMktemp(fs, args = arrayOf("/tmp/abs.XXXXXX"))
            assertEquals(0, rc)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/abs."), path)
            assertTrue(fs.exists(path))
        }

    @Test fun templateWithoutX_errors() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("/tmp/no-x-here"))
            assertEquals(1, rc)
            assertTrue("too few X" in err, err)
        }

    @Test fun templateWithTooFewX_errors() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("/tmp/foo.XX"))
            assertEquals(1, rc)
            assertTrue("too few X" in err, err)
        }

    @Test fun pFlag_honored() =
        runTest {
            val fs = fsWithTmp()
            fs.mkdirs("/var/scratch")
            val (rc, out, err) = runMktemp(fs, args = arrayOf("-p", "/var/scratch"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/var/scratch/tmp."), path)
            assertTrue(fs.exists(path))
        }

    @Test fun tmpdirEnv_honoredByDefaultTemplate() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/scratch")
            val (rc, out, err) = runMktemp(fs, env = mutableMapOf("TMPDIR" to "/scratch"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/scratch/tmp."), path)
            assertTrue(fs.exists(path))
        }

    @Test fun dashU_dryRun_doesNotCreate() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, _) = runMktemp(fs, args = arrayOf("-u"))
            assertEquals(0, rc)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/tmp."), path)
            assertFalse(fs.exists(path))
        }

    @Test fun suffix_appended() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, err) = runMktemp(fs, args = arrayOf("--suffix=.log", "foo.XXXXXX"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.endsWith(".log"), path)
            assertTrue(fs.exists(path))
        }

    @Test fun suffix_appendsAfterRandomPart() =
        runTest {
            // Suffix goes between the random part and end-of-name.
            val fs = fsWithTmp()
            val (rc, out, err) =
                runMktemp(fs, args = arrayOf("--suffix=.log", "/tmp/foo.XXXXXX"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/foo."))
            assertTrue(path.endsWith(".log"))
        }

    @Test fun fileMode_is600() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, _) = runMktemp(fs)
            assertEquals(0, rc)
            val path = out.trim()
            assertEquals(0b110_000_000, fs.stat(path).mode and 0xFFF)
        }

    @Test fun dirMode_is700() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, _) = runMktemp(fs, args = arrayOf("-d"))
            assertEquals(0, rc)
            val path = out.trim()
            assertEquals(0b111_000_000, fs.stat(path).mode and 0xFFF)
        }

    @Test fun missingParentDir_errors() =
        runTest {
            val fs = InMemoryFs() // /tmp exists by default; target a missing parent.
            val (rc, _, err) = runMktemp(fs, args = arrayOf("/nope/here/file.XXXXXX"))
            assertEquals(1, rc)
            assertTrue("No such file" in err || "no such file" in err, err)
        }

    @Test fun parentIsFile_errors() =
        runTest {
            val fs = InMemoryFs()
            fs.writeBytes("/notdir", ByteArray(0))
            val (rc, _, err) = runMktemp(fs, args = arrayOf("-p", "/notdir"))
            assertEquals(1, rc)
            assertTrue("Not a directory" in err || "not a directory" in err, err)
        }

    @Test fun tooManyTemplates_errors() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("a.XXXX", "b.XXXX"))
            assertEquals(1, rc)
            assertTrue("too many" in err, err)
        }

    @Test fun quietSuppressesStderr_butKeepsExitCode() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("-q", "/tmp/no-x-here"))
            assertEquals(1, rc)
            assertEquals("", err)
        }

    @Test fun collisionRetry_succeeds() =
        runTest {
            // Random(0) generates deterministic sequence; first name collides, retry works.
            val fs = fsWithTmp()
            // Pre-compute what Random(42) would produce for the first attempt and pre-create it.
            val r = Random(42)
            val first =
                buildString {
                    repeat(6) {
                        val idx = r.nextInt(62)
                        append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"[idx])
                    }
                }
            fs.writeBytes("/tmp/x.$first", ByteArray(0))
            // Now run with same seed — first attempt should collide; second creates.
            val (rc, out, err) =
                runMktemp(fs, random = Random(42), args = arrayOf("/tmp/x.XXXXXX"))
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/tmp/x."), path)
            assertTrue(path != "/tmp/x.$first", "got same path as pre-created: $path")
            assertTrue(fs.exists(path))
        }

    @Test fun legacyT_placesInTmpdir() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/scratch")
            val (rc, out, err) =
                runMktemp(
                    fs,
                    env = mutableMapOf("TMPDIR" to "/scratch"),
                    args = arrayOf("-t", "foo.XXXXXX"),
                )
            assertEquals(0, rc, err)
            val path = out.trim()
            assertTrue(path.startsWith("/scratch/foo."), path)
        }

    @Test fun legacyT_rejectsPathInTemplate() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("-t", "sub/foo.XXXXXX"))
            assertEquals(1, rc)
            assertTrue("directory separator" in err, err)
        }

    @Test fun unknownOption_errors() =
        runTest {
            val fs = fsWithTmp()
            val (rc, _, err) = runMktemp(fs, args = arrayOf("-Z"))
            assertEquals(1, rc)
            assertTrue("invalid option" in err, err)
        }

    @Test fun doubleDashEndsOptions() =
        runTest {
            val fs = fsWithTmp()
            val (rc, out, err) = runMktemp(fs, args = arrayOf("--", "/tmp/dd.XXXXXX"))
            assertEquals(0, rc, err)
            assertTrue(out.trim().startsWith("/tmp/dd."))
        }

    @Test fun longTmpdirNoArg_usesEnv() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/scratch")
            val (rc, out, _) =
                runMktemp(
                    fs,
                    env = mutableMapOf("TMPDIR" to "/scratch"),
                    args = arrayOf("--tmpdir", "foo.XXXXXX"),
                )
            assertEquals(0, rc)
            assertTrue(out.trim().startsWith("/scratch/foo."))
        }

    @Test fun longTmpdirWithArg() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/elsewhere")
            val (rc, out, _) =
                runMktemp(fs, args = arrayOf("--tmpdir=/elsewhere", "f.XXXXXX"))
            assertEquals(0, rc)
            assertTrue(out.trim().startsWith("/elsewhere/f."))
        }

    @Test fun recipe_createScratchDirThenFile() =
        runTest {
            // Recipe: classic "make a scratch dir, put a file inside".
            val fs = fsWithTmp()
            val (rc1, out1, _) = runMktemp(fs, args = arrayOf("-d", "/tmp/scratch.XXXXXX"))
            assertEquals(0, rc1)
            val dir = out1.trim()
            assertTrue(fs.isDirectory(dir))

            // Now create a file inside it.
            val (rc2, out2, _) = runMktemp(fs, args = arrayOf("$dir/file.XXXXXX"))
            assertEquals(0, rc2)
            val f = out2.trim()
            assertTrue(f.startsWith("$dir/file."))
            assertTrue(fs.exists(f))
        }

    @Test fun recipe_uniquePathsAcrossRuns() =
        runTest {
            val fs = fsWithTmp()
            // Use distinct random seeds — each call must produce a fresh path.
            val (_, out1, _) = runMktemp(fs, random = Random(1))
            val (_, out2, _) = runMktemp(fs, random = Random(2))
            val p1 = out1.trim()
            val p2 = out2.trim()
            assertTrue(p1 != p2, "got duplicate paths $p1 / $p2")
            assertTrue(fs.exists(p1) && fs.exists(p2))
        }
}
