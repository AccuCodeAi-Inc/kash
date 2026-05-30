package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the ref-inspection plumbing: for-each-ref, show-ref,
 * symbolic-ref, update-ref. Differential parity vs real git lives in
 * RefCommandsDifferentialTest (jvmTest).
 */
class RefCommandsTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env =
                    mutableMapOf(
                        "GIT_AUTHOR_DATE" to "1700000000 +0000",
                        "GIT_COMMITTER_DATE" to "1700000000 +0000",
                    ),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /** init + one commit + a feature branch; returns the commit sha. */
    private suspend fun setupRepo(fs: InMemoryFs): String {
        fs.mkdirs("/r")
        run(fs, "/r", "init", "-q", "-b", "main")
        fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray())
        run(fs, "/r", "add", "a.txt")
        run(fs, "/r", "commit", "-m", "c1")
        run(fs, "/r", "branch", "feature")
        return run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
    }

    // ---- for-each-ref ----

    @Test fun forEachRefDefaultFormat() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref")
            assertEquals(0, out.rc, out.stderr)
            assertEquals(
                "$sha commit\trefs/heads/feature\n$sha commit\trefs/heads/main\n",
                out.stdout,
            )
        }

    @Test fun forEachRefRefnameSorted() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--format=%(refname)")
            assertEquals("refs/heads/feature\nrefs/heads/main\n", out.stdout)
        }

    @Test fun forEachRefDescendingSort() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--sort=-refname", "--format=%(refname)")
            assertEquals("refs/heads/main\nrefs/heads/feature\n", out.stdout)
        }

    @Test fun forEachRefCount() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--count=1", "--format=%(refname)")
            assertEquals("refs/heads/feature\n", out.stdout)
        }

    @Test fun forEachRefHeadMarker() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--format=%(HEAD) %(refname:short)")
            assertEquals("  feature\n* main\n", out.stdout)
        }

    @Test fun forEachRefShortObjectName() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--format=%(objectname:short)", "refs/heads/main")
            assertEquals("${sha.take(7)}\n", out.stdout)
        }

    @Test fun forEachRefPatternPrefix() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "--format=%(refname)", "refs/heads")
            assertEquals("refs/heads/feature\nrefs/heads/main\n", out.stdout)
        }

    @Test fun forEachRefPartialComponentMatchesNothing() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "for-each-ref", "refs/heads/ma")
            assertEquals(0, out.rc)
            assertEquals("", out.stdout)
        }

    // ---- show-ref ----

    @Test fun showRefDefault() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "show-ref")
            assertEquals(
                "$sha refs/heads/feature\n$sha refs/heads/main\n",
                out.stdout,
            )
        }

    @Test fun showRefHeadsOnly() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "--heads")
            assertTrue("refs/heads/main" in out.stdout, out.stdout)
            assertTrue("refs/tags" !in out.stdout, out.stdout)
        }

    @Test fun showRefVerifyMissingExits128() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "--verify", "refs/heads/nope")
            assertEquals(128, out.rc)
            assertTrue("not a valid ref" in out.stderr, out.stderr)
        }

    @Test fun showRefVerifyPresent() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "--verify", "refs/heads/main")
            assertEquals(0, out.rc)
            assertEquals("$sha refs/heads/main\n", out.stdout)
        }

    @Test fun showRefNoMatchExits1() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "doesnotexist")
            assertEquals(1, out.rc)
            assertEquals("", out.stdout)
        }

    @Test fun showRefHashOnly() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "-s", "main")
            assertEquals("$sha\n", out.stdout)
        }

    @Test fun showRefTailComponentMatch() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "show-ref", "main")
            assertEquals("$sha refs/heads/main\n", out.stdout)
        }

    // ---- symbolic-ref ----

    @Test fun symbolicRefReadHead() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "symbolic-ref", "HEAD")
            assertEquals("refs/heads/main\n", out.stdout)
        }

    @Test fun symbolicRefShort() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "symbolic-ref", "--short", "HEAD")
            assertEquals("main\n", out.stdout)
        }

    @Test fun symbolicRefSetAndRead() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            run(fs, "/r", "symbolic-ref", "HEAD", "refs/heads/feature")
            val out = run(fs, "/r", "symbolic-ref", "HEAD")
            assertEquals("refs/heads/feature\n", out.stdout)
        }

    @Test fun symbolicRefNonSymbolicQuiet() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "symbolic-ref", "-q", "refs/heads/main")
            assertEquals(128, out.rc)
            assertEquals("", out.stdout)
            assertEquals("", out.stderr)
        }

    @Test fun symbolicRefSetAndDeleteCustom() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            run(fs, "/r", "symbolic-ref", "refs/heads/sym", "refs/heads/main")
            val read = run(fs, "/r", "symbolic-ref", "refs/heads/sym")
            assertEquals("refs/heads/main\n", read.stdout)
            val del = run(fs, "/r", "symbolic-ref", "-d", "refs/heads/sym")
            assertEquals(0, del.rc)
            assertTrue(!fs.exists("/r/.git/refs/heads/sym"))
        }

    // ---- update-ref ----

    @Test fun updateRefCreatesRef() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val out = run(fs, "/r", "update-ref", "refs/heads/newbr", sha)
            assertEquals(0, out.rc, out.stderr)
            assertEquals("$sha\n", run(fs, "/r", "rev-parse", "refs/heads/newbr").stdout)
        }

    @Test fun updateRefDelete() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            run(fs, "/r", "update-ref", "refs/heads/temp", sha)
            val del = run(fs, "/r", "update-ref", "-d", "refs/heads/temp")
            assertEquals(0, del.rc)
            val sr = run(fs, "/r", "show-ref", "temp")
            assertEquals(1, sr.rc)
        }

    @Test fun updateRefOldValueMismatchExits128() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val bogus = "1".repeat(40)
            val out = run(fs, "/r", "update-ref", "refs/heads/main", sha, bogus)
            assertEquals(128, out.rc)
            assertTrue("but expected $bogus" in out.stderr, out.stderr)
        }

    @Test fun updateRefRefuseExistingWithZeroOld() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            val zero = "0".repeat(40)
            val out = run(fs, "/r", "update-ref", "refs/heads/main", sha, zero)
            assertEquals(128, out.rc)
            assertTrue("reference already exists" in out.stderr, out.stderr)
        }

    @Test fun updateRefWritesReflog() =
        runTest {
            val fs = InMemoryFs()
            val sha = setupRepo(fs)
            run(fs, "/r", "update-ref", "refs/heads/logged", sha)
            assertTrue(fs.exists("/r/.git/logs/refs/heads/logged"))
            val log = fs.readBytes("/r/.git/logs/refs/heads/logged").decodeToString()
            assertTrue(sha in log, log)
        }
}
