package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Differential tests for `git branch` against real `/usr/bin/git`. The
 * kash run is mirrored into a temp dir and compared to a real-git repo
 * built with the same sequence of commands, so output and on-disk state
 * agree byte-for-byte where it matters.
 */
class BranchDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }

        private val DATE = "1700000000 +0000"
    }

    private fun runGit(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Triple<Int, String, String> {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env =
                    mutableMapOf(
                        "GIT_AUTHOR_DATE" to DATE,
                        "GIT_COMMITTER_DATE" to DATE,
                        "GIT_AUTHOR_NAME" to "Test User",
                        "GIT_AUTHOR_EMAIL" to "test@example.com",
                        "GIT_COMMITTER_NAME" to "Test User",
                        "GIT_COMMITTER_EMAIL" to "test@example.com",
                    ),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    /** Build a small repo (main + feature commit) in the in-memory FS. */
    private fun seedRepo(fs: InMemoryFs) {
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q", "-b", "main")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "first subject line")
    }

    private suspend fun copyDir(
        fs: InMemoryFs,
        src: String,
        dest: File,
    ) {
        dest.mkdirs()
        for (name in fs.list(src)) {
            val full = "$src/$name"
            val out = File(dest, name)
            if (fs.isDirectory(full)) {
                copyDir(fs, full, out)
            } else {
                out.writeBytes(fs.readBytes(full))
            }
        }
    }

    @Test fun renameCurrentBranchUpdatesHeadAndReflog(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        val (code, _, err) = runGit(fs, "/r", "branch", "-m", "renamed")
        assertEquals(0, code, err)

        // HEAD now follows refs/heads/renamed.
        assertEquals("renamed\n", runGit(fs, "/r", "branch", "--show-current").second)
        // Old ref gone, new ref present.
        assertTrue(runBlocking { fs.exists("/r/.git/refs/heads/renamed") })
        assertTrue(!runBlocking { fs.exists("/r/.git/refs/heads/main") })

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(0, probe.run(listOf("fsck", "--no-progress"), tmp).exitCode)
        assertEquals("refs/heads/renamed\n", probe.run(listOf("symbolic-ref", "HEAD"), tmp).stdoutUtf8())
        // Real git sees the renamed branch.
        assertTrue("renamed" in probe.run(listOf("branch"), tmp).stdoutUtf8())
        // Reflog moved with the branch.
        assertTrue(runBlocking { fs.exists("/r/.git/logs/refs/heads/renamed") })
    }

    @Test fun renameTwoArgNonCurrent(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "feature")
        val (code, _, _) = runGit(fs, "/r", "branch", "-m", "feature", "feat2")
        assertEquals(0, code)
        assertTrue(!runBlocking { fs.exists("/r/.git/refs/heads/feature") })
        assertTrue(runBlocking { fs.exists("/r/.git/refs/heads/feat2") })
        // HEAD unchanged (still main).
        assertEquals("main\n", runGit(fs, "/r", "branch", "--show-current").second)
    }

    @Test fun renameOntoExistingRejectedUnlessForce() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "x")
        runGit(fs, "/r", "branch", "y")
        val (code, _, err) = runGit(fs, "/r", "branch", "-m", "x", "y")
        assertEquals(128, code)
        assertEquals("fatal: a branch named 'y' already exists\n", err)
        // -M force succeeds.
        val (code2, _, _) = runGit(fs, "/r", "branch", "-M", "x", "y")
        assertEquals(0, code2)
        assertTrue(!runBlocking { fs.exists("/r/.git/refs/heads/x") })
    }

    @Test fun deleteUnmergedRejectedThenForced() {
        val fs = InMemoryFs()
        seedRepo(fs)
        // Create an unmerged branch with its own commit.
        runGit(fs, "/r", "branch", "feat2")
        runGit(fs, "/r", "switch", "feat2")
        runBlocking { fs.writeBytes("/r/b", "b\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "b")
        runGit(fs, "/r", "commit", "-m", "second")
        runGit(fs, "/r", "switch", "main")

        val (code, _, err) = runGit(fs, "/r", "branch", "-d", "feat2")
        assertEquals(1, code)
        assertTrue("not fully merged" in err, err)
        assertTrue("git branch -D feat2" in err, err)
        // Branch still there.
        assertTrue(runBlocking { fs.exists("/r/.git/refs/heads/feat2") })

        val (code2, out2, _) = runGit(fs, "/r", "branch", "-D", "feat2")
        assertEquals(0, code2)
        assertTrue(out2.startsWith("Deleted branch feat2 (was "), out2)
        assertTrue(!runBlocking { fs.exists("/r/.git/refs/heads/feat2") })
    }

    @Test fun deleteMergedSucceedsWithWasSha() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "cp1")
        val sha = runGit(fs, "/r", "rev-parse", "cp1").second.trim().substring(0, 7)
        val (code, out, _) = runGit(fs, "/r", "branch", "-d", "cp1")
        assertEquals(0, code)
        assertEquals("Deleted branch cp1 (was $sha).\n", out)
    }

    @Test fun deleteCurrentRefused() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val (code, _, err) = runGit(fs, "/r", "branch", "-d", "main")
        assertEquals(1, code)
        assertTrue("cannot delete branch 'main'" in err, err)
    }

    @Test fun deleteNotFound() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val (code, _, err) = runGit(fs, "/r", "branch", "-d", "nope")
        assertEquals(1, code)
        assertEquals("error: branch 'nope' not found\n", err)
    }

    @Test fun verboseListingShowsShaAndSubject() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "feature")
        val sha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim().substring(0, 7)
        val (code, out, _) = runGit(fs, "/r", "branch", "-v")
        assertEquals(0, code)
        // current main marked, both show sha + subject, aligned columns.
        val lines = out.trimEnd('\n').split('\n')
        assertEquals(2, lines.size, out)
        assertTrue(lines.any { it.startsWith("* main") && it.contains(sha) && it.contains("first subject line") }, out)
        assertTrue(lines.any { it.startsWith("  feature") && it.contains("first subject line") }, out)
    }

    @Test fun setUpstreamAndVvTracking(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        // Fake a remote-tracking ref at HEAD.
        val headSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()
        runBlocking {
            fs.mkdirs("/r/.git/refs/remotes/origin")
            fs.writeBytes("/r/.git/refs/remotes/origin/main", "$headSha\n".encodeToByteArray())
        }
        val (code, out, _) = runGit(fs, "/r", "branch", "-u", "origin/main", "main")
        assertEquals(0, code)
        assertEquals("branch 'main' set up to track 'origin/main'.\n", out)

        // Config written.
        assertEquals("origin\n", runGit(fs, "/r", "config", "branch.main.remote").second)
        assertEquals("refs/heads/main\n", runGit(fs, "/r", "config", "branch.main.merge").second)

        // In-sync -vv shows just [origin/main].
        val vv = runGit(fs, "/r", "branch", "-vv").second
        assertTrue("[origin/main]" in vv, vv)

        // Add a commit so main is ahead 1.
        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "second")
        val vv2 = runGit(fs, "/r", "branch", "-vv").second
        assertTrue("[origin/main: ahead 1]" in vv2, vv2)
        // -v (single) omits the upstream name.
        val v = runGit(fs, "/r", "branch", "-v").second
        assertTrue("[ahead 1]" in v, v)
        assertTrue("origin/main" !in v, v)

        // unset-upstream clears it.
        val (uc, _, _) = runGit(fs, "/r", "branch", "--unset-upstream", "main")
        assertEquals(0, uc)
        assertEquals(1, runGit(fs, "/r", "config", "branch.main.remote").first)
    }

    @Test fun goneUpstreamShownInVv() {
        val fs = InMemoryFs()
        seedRepo(fs)
        // Config points at a remote ref that does not exist.
        runGit(fs, "/r", "config", "branch.main.remote", "origin")
        runGit(fs, "/r", "config", "branch.main.merge", "refs/heads/main")
        val vv = runGit(fs, "/r", "branch", "-vv").second
        assertTrue("[origin/main: gone]" in vv, vv)
    }

    @Test fun remotesAndAllListing() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val headSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()
        runBlocking {
            fs.mkdirs("/r/.git/refs/remotes/origin")
            fs.writeBytes("/r/.git/refs/remotes/origin/main", "$headSha\n".encodeToByteArray())
        }
        val r = runGit(fs, "/r", "branch", "-r").second
        assertEquals("  origin/main\n", r)
        val all = runGit(fs, "/r", "branch", "-a").second
        assertTrue("* main" in all, all)
        assertTrue("remotes/origin/main" in all, all)
    }

    @Test fun mergedAndContainsFilters() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "feature")
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/b", "b\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "b")
        runGit(fs, "/r", "commit", "-m", "second")
        runGit(fs, "/r", "switch", "main")

        // --merged (default HEAD=main): only main is reachable.
        val merged = runGit(fs, "/r", "branch", "--merged").second
        assertTrue("main" in merged, merged)
        assertTrue("feature" !in merged, merged)
        // --no-merged: feature.
        val noMerged = runGit(fs, "/r", "branch", "--no-merged").second
        assertTrue("feature" in noMerged, noMerged)
        assertTrue(noMerged.lines().none { it.trim() == "main" }, noMerged)
    }

    @Test fun copyBranchKeepsOriginal() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "src")
        val (code, _, _) = runGit(fs, "/r", "branch", "-c", "src", "dst")
        assertEquals(0, code)
        assertTrue(runBlocking { fs.exists("/r/.git/refs/heads/src") })
        assertTrue(runBlocking { fs.exists("/r/.git/refs/heads/dst") })
    }

    @Test fun trackOnCreate() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val headSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()
        runBlocking {
            fs.mkdirs("/r/.git/refs/remotes/origin")
            fs.writeBytes("/r/.git/refs/remotes/origin/main", "$headSha\n".encodeToByteArray())
        }
        val (code, out, _) = runGit(fs, "/r", "branch", "-t", "tracker", "origin/main")
        assertEquals(0, code)
        assertTrue("set up to track 'origin/main'" in out, out)
        assertEquals("origin\n", runGit(fs, "/r", "config", "branch.tracker.remote").second)
    }

    @Test fun sortRefnameDescending() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/r", "branch", "aaa")
        runGit(fs, "/r", "branch", "zzz")
        val out = runGit(fs, "/r", "branch", "--sort=-refname").second
        val names = out.lines().filter { it.isNotBlank() }.map { it.removePrefix("* ").removePrefix("  ").trim() }
        assertEquals(listOf("zzz", "main", "aaa"), names)
    }
}
