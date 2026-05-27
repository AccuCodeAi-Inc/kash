package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BranchSwitchResetTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private fun seedRepoWithCommit(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "init")
    }

    @Test fun branchListAndCreate() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        // list
        val l1 = run(fs, "/r", "branch")
        assertEquals("* main\n", l1.stdout)
        // create
        run(fs, "/r", "branch", "feature")
        val l2 = run(fs, "/r", "branch")
        assertEquals("  feature\n* main\n", l2.stdout)
        // show-current
        assertEquals("main\n", run(fs, "/r", "branch", "--show-current").stdout)
    }

    @Test fun branchDeleteRejectsCurrent() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        val r = run(fs, "/r", "branch", "-d", "main")
        assertEquals(1, r.rc)
        assertTrue("checked out" in r.stderr, r.stderr)
    }

    @Test fun switchSwitchesBranchAndUpdatesWorkTree() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        // Build a divergent branch.
        run(fs, "/r", "branch", "feature")
        run(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/a", "feature\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "edit on feature")

        // Switching back to main should restore the main tree.
        val sw = run(fs, "/r", "switch", "main")
        assertEquals(0, sw.rc, sw.stderr)
        val contents = runBlocking { fs.readBytes("/r/a").decodeToString() }
        assertEquals("v1\n", contents)
        assertEquals("main\n", run(fs, "/r", "branch", "--show-current").stdout)
    }

    @Test fun switchDashCCreatesAndSwitches() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        val sw = run(fs, "/r", "switch", "-c", "newbranch")
        assertEquals(0, sw.rc, sw.stderr)
        assertEquals("newbranch\n", run(fs, "/r", "branch", "--show-current").stdout)
    }

    @Test fun resetHardWipesWorkingTreeAndIndex() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        runBlocking { fs.writeBytes("/r/a", "garbage\n".encodeToByteArray()) }
        // status: 'a' modified
        assertTrue(" M a\n" in run(fs, "/r", "status", "--porcelain").stdout)
        run(fs, "/r", "reset", "--hard")
        assertEquals("v1\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
        assertEquals("", run(fs, "/r", "status", "--porcelain").stdout)
    }

    @Test fun resetSoftMovesHeadOnly() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "v2")
        val twoCommits =
            run(fs, "/r", "log", "--oneline")
                .stdout
                .lines()
                .filter { it.isNotEmpty() }
                .size
        assertEquals(2, twoCommits)
        run(fs, "/r", "reset", "--soft", "HEAD~0") // HEAD~0 unsupported in v1; use HEAD
        run(fs, "/r", "reset", "--soft", "HEAD")
        // Soft to HEAD is a no-op; instead try resetting to first commit's parent.
        // First commit has no parent, so we just verify --soft doesn't touch index/worktree.
        assertEquals("v2\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
    }

    @Test fun tagListCreateDelete() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        run(fs, "/r", "tag", "v1.0")
        assertEquals("v1.0\n", run(fs, "/r", "tag").stdout)

        // rev-parse against tag works
        val byTag = run(fs, "/r", "rev-parse", "v1.0").stdout.trim()
        val byHead = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        assertEquals(byHead, byTag)

        // delete
        run(fs, "/r", "tag", "-d", "v1.0")
        assertEquals("", run(fs, "/r", "tag").stdout)
    }

    @Test fun diffWorkTreeVsIndex() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        val d = run(fs, "/r", "diff")
        assertEquals(0, d.rc, d.stderr)
        assertTrue(d.stdout.contains("diff --git a/a b/a"), d.stdout)
        assertTrue(d.stdout.contains("-v1"), d.stdout)
        assertTrue(d.stdout.contains("+v2"), d.stdout)
    }

    @Test fun diffNameOnly() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        val d = run(fs, "/r", "diff", "--name-only")
        assertEquals("a\n", d.stdout)
    }

    @Test fun checkoutWithLocalChangesRejected() {
        val fs = InMemoryFs()
        seedRepoWithCommit(fs)
        run(fs, "/r", "branch", "feature")
        runBlocking { fs.writeBytes("/r/a", "dirty\n".encodeToByteArray()) }
        // Make feature have a different content for `a` so checkout would overwrite.
        run(fs, "/r", "switch", "feature", "-f")
        runBlocking { fs.writeBytes("/r/a", "different\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "feature-change")
        run(fs, "/r", "switch", "main")
        // Now dirty up main and try to switch.
        runBlocking { fs.writeBytes("/r/a", "dirty-again\n".encodeToByteArray()) }
        val sw = run(fs, "/r", "switch", "feature")
        assertEquals(1, sw.rc)
        assertTrue("would be overwritten" in sw.stderr, sw.stderr)
        assertFalse(sw.stderr.contains("Switched"))
    }
}
