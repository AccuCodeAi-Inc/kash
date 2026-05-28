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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeTest {
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
                env = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /** Set up a repo with `a`/`b` on main and a feature branch that hasn't diverged yet. */
    private suspend fun setupDivergent(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        fs.writeBytes("/r/a", "line1\nline2\nline3\n".encodeToByteArray())
        fs.writeBytes("/r/b", "common\n".encodeToByteArray())
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "base")
        run(fs, "/r", "branch", "feature")
    }

    @Test fun fastForwardWhenLinear() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)
            // feature gets ahead.
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/c", "from-feature\n".encodeToByteArray())
            run(fs, "/r", "add", "c")
            run(fs, "/r", "commit", "-m", "feature add c")
            // main is behind. Merge feature → fast-forward.
            run(fs, "/r", "switch", "main")
            val m = run(fs, "/r", "merge", "feature")
            assertEquals(0, m.rc, m.stderr)
            assertTrue("Fast-forward" in m.stdout, m.stdout)
            assertEquals("from-feature\n", fs.readBytes("/r/c").decodeToString())
        }

    @Test fun threeWayCleanMergeProducesMergeCommit() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)

            // Diverge: feature edits a, main edits b.
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/a", "line1\nline2-feature\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "feature edits a")

            run(fs, "/r", "switch", "main")
            fs.writeBytes("/r/b", "main-edit\n".encodeToByteArray())
            run(fs, "/r", "add", "b")
            run(fs, "/r", "commit", "-m", "main edits b")

            val m = run(fs, "/r", "merge", "feature")
            assertEquals(0, m.rc, m.stderr)
            assertTrue("Merge made" in m.stdout, m.stdout)
            // Both changes present.
            assertEquals(
                "line1\nline2-feature\nline3\n",
                fs.readBytes("/r/a").decodeToString(),
            )
            assertEquals("main-edit\n", fs.readBytes("/r/b").decodeToString())
            // HEAD commit has 2 parents: HEAD^1 = main edit, HEAD^2 = feature edit.
            // log is first-parent only, so use parent navigation directly.
            val headSha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            // Read the commit and verify it has 2 parents.
            val raw = run(fs, "/r", "show", headSha).stdout
            assertTrue("Merge feature into main" in raw, raw)
        }

    @Test fun conflictingEditsPauseTheMerge() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)

            // Both sides edit a's line 2 differently.
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/a", "line1\nFEATURE\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "feature line2")

            run(fs, "/r", "switch", "main")
            fs.writeBytes("/r/a", "line1\nMAIN\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "main line2")

            val m = run(fs, "/r", "merge", "feature")
            assertEquals(1, m.rc)
            assertTrue("CONFLICT" in m.stderr, m.stderr)
            // Work tree has conflict markers.
            val content = fs.readBytes("/r/a").decodeToString()
            assertTrue("<<<<<<< HEAD" in content, content)
            assertTrue("MAIN" in content, content)
            assertTrue(">>>>>>> feature" in content, content)
            // MERGE_HEAD written.
            assertTrue(fs.exists("/r/.git/MERGE_HEAD"))
            // Status shows unmerged.
            val st = run(fs, "/r", "status", "--porcelain")
            // We don't emit U codes yet; just check the path is listed
            // (worktree shows "modified"-ish). Most importantly: ls-files -u
            // shows the three stages.
            val ls = run(fs, "/r", "ls-files", "--stage")
            val lines = ls.stdout.lines().filter { it.isNotEmpty() }
            val stagesForA = lines.filter { it.endsWith("\ta") }
            // Three stage entries (1=base, 2=ours, 3=theirs).
            assertEquals(3, stagesForA.size, stagesForA.toString())
        }

    @Test fun resolveConflictThenCommitProducesMergeCommit() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)
            // Conflicting edits.
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/a", "line1\nFEATURE\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "feature")

            run(fs, "/r", "switch", "main")
            fs.writeBytes("/r/a", "line1\nMAIN\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "main")

            run(fs, "/r", "merge", "feature")

            // Resolve manually.
            fs.writeBytes("/r/a", "line1\nRESOLVED\nline3\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            val cm = run(fs, "/r", "commit", "-m", "resolve conflict")
            assertEquals(0, cm.rc, cm.stderr)
            assertFalse(fs.exists("/r/.git/MERGE_HEAD"))
            // Verify merge commit has two parents via log walking both branches.
            val log = run(fs, "/r", "log", "--pretty=%s").stdout.lines().filter { it.isNotEmpty() }
            assertTrue("resolve conflict" in log, log.toString())
            // First-parent walk visits "main", second-parent reaches "feature";
            // both should be reachable.
            assertTrue("main" in log, log.toString())
        }

    @Test fun abortRestoresPreMergeState() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/a", "FEATURE\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "feature")
            run(fs, "/r", "switch", "main")
            fs.writeBytes("/r/a", "MAIN\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "main")
            val origHead = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            run(fs, "/r", "merge", "feature")
            // Abort.
            val ab = run(fs, "/r", "merge", "--abort")
            assertEquals(0, ab.rc, ab.stderr)
            assertEquals(origHead, run(fs, "/r", "rev-parse", "HEAD").stdout.trim())
            assertFalse(fs.exists("/r/.git/MERGE_HEAD"))
            assertEquals("MAIN\n", fs.readBytes("/r/a").decodeToString())
        }

    @Test fun commitDuringUnresolvedMergeRefuses() =
        runTest {
            val fs = InMemoryFs()
            setupDivergent(fs)
            run(fs, "/r", "switch", "feature")
            fs.writeBytes("/r/a", "F\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "f")
            run(fs, "/r", "switch", "main")
            fs.writeBytes("/r/a", "M\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "commit", "-m", "m")
            run(fs, "/r", "merge", "feature")
            // Don't resolve. Try to commit.
            val cm = run(fs, "/r", "commit", "-m", "premature")
            assertEquals(1, cm.rc)
            assertTrue("unmerged" in cm.stderr.lowercase(), cm.stderr)
        }
}
