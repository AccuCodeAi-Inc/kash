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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Differential tests for `git add` / `git rm` / `git mv` / `git clean`.
 *
 * Strategy: build the same scenario in both an in-memory kash repo and a
 * real on-disk `/usr/bin/git` repo, run the same command in each, and
 * compare stdout, stderr, exit code, and the resulting worktree/index
 * state. Real git is the oracle; tests skip if it isn't installed.
 */
class AddRmMvCleanDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private val gitEnv =
        mapOf(
            "GIT_AUTHOR_NAME" to "Test User",
            "GIT_AUTHOR_EMAIL" to "test@example.com",
            "GIT_AUTHOR_DATE" to "1700000000 +0000",
            "GIT_COMMITTER_NAME" to "Test User",
            "GIT_COMMITTER_EMAIL" to "test@example.com",
            "GIT_COMMITTER_DATE" to "1700000000 +0000",
        )

    private fun kash(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = gitEnv.toMutableMap(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /** A paired scenario: same fs commands run in kash and on real git. */
    private class Scenario(
        val fs: InMemoryFs,
        val dir: File,
    )

    /** Init both repos with an identical committed baseline. */
    private fun scenario(
        tmp: File,
        setup: suspend (write: suspend (String, String) -> Unit, mkdir: suspend (String) -> Unit) -> Unit,
    ): Scenario {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        kash(fs, "/work", "init", "-q")
        // Real repo lives under tmp/work
        val dir = File(tmp, "work")
        dir.mkdirs()
        probe.run(listOf("init", "-q", "-b", "main"), dir)

        runBlocking {
            val write: suspend (String, String) -> Unit = { rel, content ->
                fs.writeBytes("/work/$rel", content.encodeToByteArray())
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
            val mkdir: suspend (String) -> Unit = { rel ->
                fs.mkdirs("/work/$rel")
                File(dir, rel).mkdirs()
            }
            setup(write, mkdir)
        }
        return Scenario(fs, dir)
    }

    private fun commitBoth(s: Scenario) {
        kash(s.fs, "/work", "add", "-A")
        kash(s.fs, "/work", "commit", "-q", "-m", "base")
        probe.run(listOf("add", "-A"), s.dir, gitEnv)
        probe.run(listOf("commit", "-q", "-m", "base"), s.dir, gitEnv)
    }

    /** Run the same git args in both; assert identical stdout/stderr/rc. */
    private fun assertSame(
        s: Scenario,
        vararg args: String,
    ): Output {
        val k = kash(s.fs, "/work", *args)
        val r = probe.run(args.toList(), s.dir, gitEnv)
        assertEquals(r.stdoutUtf8(), k.stdout, "stdout mismatch for: git ${args.joinToString(" ")}")
        assertEquals(r.stderrUtf8(), k.stderr, "stderr mismatch for: git ${args.joinToString(" ")}")
        assertEquals(r.exitCode, k.rc, "exit code mismatch for: git ${args.joinToString(" ")}")
        return k
    }

    /**
     * Like [assertSame] but for `git rm` refusals, whose stderr wording is
     * deliberately kash's own (not git's verbatim prose — see RmMvClean.kt).
     * We still pin stdout and the exit code to real git, and check kash's
     * stderr structurally: an `error:` header, each refused path indented,
     * and a `(pass ...)` hint. The resulting index/work-tree state is asserted
     * by the caller via [assertSameStatus].
     */
    private fun assertSameRmRefusal(
        s: Scenario,
        vararg args: String,
    ) {
        val k = kash(s.fs, "/work", *args)
        val r = probe.run(args.toList(), s.dir, gitEnv)
        assertEquals(r.stdoutUtf8(), k.stdout, "stdout mismatch for: git ${args.joinToString(" ")}")
        assertEquals(r.exitCode, k.rc, "exit code mismatch for: git ${args.joinToString(" ")}")
        assertTrue(k.stderr.startsWith("error: refusing to remove"), "kash refusal header: ${k.stderr}")
        assertTrue(k.stderr.contains("(pass "), "kash refusal hint: ${k.stderr}")
        // At least one offending path is listed, indented. (Atomic refusals
        // list only the offending file, not every argument.)
        assertTrue(Regex("\n {4}\\S").containsMatchIn(k.stderr), "refused path listed: ${k.stderr}")
    }

    private fun kashStatus(fs: InMemoryFs): String = kash(fs, "/work", "status", "--porcelain").stdout

    private fun realStatus(dir: File): String = probe.run(listOf("status", "--porcelain"), dir, gitEnv).stdoutUtf8()

    /**
     * Compare the resulting **index** state, which is what add/rm/mv/clean
     * actually mutate. We use `ls-files --stage` rather than `status`
     * because kash `git status` does not do rename detection (that lives in
     * Status.kt, out of this command's scope) — so a real rename shows as
     * `R old -> new` there while the index content is identical.
     */
    private fun assertSameIndex(s: Scenario) {
        val k = kash(s.fs, "/work", "ls-files", "--stage").stdout
        val r = probe.run(listOf("ls-files", "--stage"), s.dir, gitEnv).stdoutUtf8()
        assertEquals(r, k, "index (ls-files --stage) mismatch")
    }

    private fun assertSameStatus(s: Scenario) = assertSameIndex(s)

    // ----- add -----

    @Test fun addDryRunPrintsAndChangesNothing(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("a.txt", "a\n") }
        assertSame(s, "add", "-n", "a.txt")
        // Nothing staged.
        assertSameStatus(s)
        assertTrue("?? a.txt" in kashStatus(s.fs))
    }

    @Test fun addVerbosePrintsAddLines(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("a.txt", "a\n") }
        assertSame(s, "add", "-v", "a.txt")
        assertSameStatus(s)
    }

    @Test fun addIgnoredFileRefusedWithoutForce(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write(".gitignore", "*.log\n")
                write("app.log", "l\n")
            }
        commitBoth(s)
        assertSame(s, "add", "app.log")
        assertSameStatus(s)
    }

    @Test fun addIgnoredFileWithForceStages(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write(".gitignore", "*.log\n")
                write("app.log", "l\n")
            }
        commitBoth(s)
        assertSame(s, "add", "-f", "app.log")
        assertSameStatus(s)
    }

    @Test fun addDirectorySkipsIgnoredSilently(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write(".gitignore", "*.log\n")
                write("keep.txt", "k\n")
                write("app.log", "l\n")
            }
        assertSame(s, "add", ".")
        assertSameStatus(s)
    }

    @Test fun addNonexistentPathErrors(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        assertSame(s, "add", "nope")
    }

    @Test fun addIgnoreRemovalDoesNotStageDeletion(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write("x.txt", "x\n")
                write("y.txt", "y\n")
            }
        commitBoth(s)
        runBlocking {
            s.fs.remove("/work/x.txt")
            File(s.dir, "x.txt").delete()
            s.fs.writeBytes("/work/new.txt", "n\n".encodeToByteArray())
            File(s.dir, "new.txt").writeText("n\n")
        }
        assertSame(s, "add", "--ignore-removal", ".")
        assertSameStatus(s)
    }

    @Test fun addAllStagesDeletion(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("x.txt", "x\n") }
        commitBoth(s)
        runBlocking {
            s.fs.remove("/work/x.txt")
            File(s.dir, "x.txt").delete()
        }
        assertSame(s, "add", "-A")
        assertSameStatus(s)
    }

    @Test fun addInteractiveRejected(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        val k = kash(s.fs, "/work", "add", "-p")
        assertEquals(129, k.rc)
    }

    @Test fun addAllThenIgnoreRemovalLastWins(
        @TempDir tmp: File,
    ) {
        // `-A --ignore-removal .`: --ignore-removal wins, so the deletion of
        // x.txt is NOT staged but the new file IS.
        val s = scenario(tmp) { write, _ -> write("x.txt", "x\n") }
        commitBoth(s)
        runBlocking {
            s.fs.remove("/work/x.txt")
            File(s.dir, "x.txt").delete()
            s.fs.writeBytes("/work/new.txt", "n\n".encodeToByteArray())
            File(s.dir, "new.txt").writeText("n\n")
        }
        assertSame(s, "add", "-A", "--ignore-removal", ".")
        assertSameStatus(s)
    }

    @Test fun addAllNoAllWithoutPathspecAddsNothing(
        @TempDir tmp: File,
    ) {
        // `-A --no-all` with no pathspec falls back to "Nothing specified".
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/n.txt", "n\n".encodeToByteArray())
            File(s.dir, "n.txt").writeText("n\n")
        }
        assertSame(s, "add", "-A", "--no-all")
        assertSameStatus(s)
    }

    // ----- rm -----

    @Test fun rmCleanFileRemovesFromIndexAndWorktree(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        assertSame(s, "rm", "f.txt")
        assertFalse(s.fs.exists("/work/f.txt"))
        assertSameStatus(s)
    }

    @Test fun rmModifiedRefusedWithoutForce(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "changed\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("changed\n")
        }
        assertSameRmRefusal(s, "rm", "f.txt")
        // File still present (refused).
        assertTrue(s.fs.exists("/work/f.txt"))
        assertSameStatus(s)
    }

    @Test fun rmModifiedWithForceRemoves(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "changed\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("changed\n")
        }
        assertSame(s, "rm", "-f", "f.txt")
        assertFalse(s.fs.exists("/work/f.txt"))
        assertSameStatus(s)
    }

    @Test fun rmStagedChangeRefused(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "staged\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("staged\n")
        }
        kash(s.fs, "/work", "add", "f.txt")
        probe.run(listOf("add", "f.txt"), s.dir, gitEnv)
        // Now worktree == index but index != HEAD: changes staged.
        assertSameRmRefusal(s, "rm", "f.txt")
        assertSameStatus(s)
    }

    @Test fun rmStagedDifferentFromBothRefused(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "staged\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("staged\n")
        }
        kash(s.fs, "/work", "add", "f.txt")
        probe.run(listOf("add", "f.txt"), s.dir, gitEnv)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "worktree\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("worktree\n")
        }
        assertSameRmRefusal(s, "rm", "f.txt")
        assertSameStatus(s)
    }

    @Test fun rmNewlyAddedRefused(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/n.txt", "n\n".encodeToByteArray())
            File(s.dir, "n.txt").writeText("n\n")
        }
        kash(s.fs, "/work", "add", "n.txt")
        probe.run(listOf("add", "n.txt"), s.dir, gitEnv)
        assertSameRmRefusal(s, "rm", "n.txt")
    }

    @Test fun rmCachedKeepsWorktree(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        assertSame(s, "rm", "--cached", "f.txt")
        assertTrue(s.fs.exists("/work/f.txt"))
        assertSameStatus(s)
    }

    @Test fun rmCachedModifiedWorktreeAllowed(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/f.txt", "changed\n".encodeToByteArray())
            File(s.dir, "f.txt").writeText("changed\n")
        }
        // --cached: index==HEAD, worktree differs -> allowed.
        assertSame(s, "rm", "--cached", "f.txt")
        assertSameStatus(s)
    }

    @Test fun rmDirWithoutRecurseErrors(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("d/a.txt", "a\n") }
        commitBoth(s)
        assertSame(s, "rm", "d")
    }

    @Test fun rmDirRecurse(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write("d/a.txt", "a\n")
                write("d/b.txt", "b\n")
            }
        commitBoth(s)
        assertSame(s, "rm", "-r", "d")
        assertSameStatus(s)
    }

    @Test fun rmUntrackedErrors(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        assertSame(s, "rm", "ghost")
    }

    @Test fun rmIgnoreUnmatch(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        assertSame(s, "rm", "--ignore-unmatch", "ghost")
    }

    @Test fun rmDryRun(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        assertSame(s, "rm", "-n", "f.txt")
        // File still present after dry run.
        assertTrue(s.fs.exists("/work/f.txt"))
        assertSameStatus(s)
    }

    @Test fun rmQuietSuppressesOutput(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("f.txt", "hi\n") }
        commitBoth(s)
        assertSame(s, "rm", "-q", "f.txt")
        assertSameStatus(s)
    }

    @Test fun rmMultipleMixedRefusesAtomically(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write("a.txt", "a\n")
                write("b.txt", "b\n")
            }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/a.txt", "AA\n".encodeToByteArray())
            File(s.dir, "a.txt").writeText("AA\n")
        }
        assertSameRmRefusal(s, "rm", "a.txt", "b.txt")
        // Neither removed.
        assertTrue(s.fs.exists("/work/a.txt"))
        assertTrue(s.fs.exists("/work/b.txt"))
        assertSameStatus(s)
    }

    // ----- mv -----

    @Test fun mvRenamesFile(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("old", "hi\n") }
        commitBoth(s)
        assertSame(s, "mv", "old", "new")
        assertFalse(s.fs.exists("/work/old"))
        assertTrue(s.fs.exists("/work/new"))
        assertSameStatus(s)
    }

    @Test fun mvIntoDirectory(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, mkdir ->
                write("a.txt", "a\n")
                write("b.txt", "b\n")
                mkdir("sub")
            }
        commitBoth(s)
        assertSame(s, "mv", "a.txt", "b.txt", "sub")
        assertTrue(s.fs.exists("/work/sub/a.txt"))
        assertTrue(s.fs.exists("/work/sub/b.txt"))
        assertSameStatus(s)
    }

    @Test fun mvDestinationExistsErrors(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write("a.txt", "a\n")
                write("b.txt", "b\n")
            }
        commitBoth(s)
        assertSame(s, "mv", "a.txt", "b.txt")
        assertSameStatus(s)
    }

    @Test fun mvForceOverwrites(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write("a.txt", "a\n")
                write("b.txt", "b\n")
            }
        commitBoth(s)
        assertSame(s, "mv", "-f", "a.txt", "b.txt")
        assertSameStatus(s)
    }

    @Test fun mvNotUnderVersionControl(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("a.txt", "a\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/untracked", "u\n".encodeToByteArray())
            File(s.dir, "untracked").writeText("u\n")
        }
        assertSame(s, "mv", "untracked", "dest")
    }

    @Test fun mvBadSource(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("a.txt", "a\n") }
        commitBoth(s)
        assertSame(s, "mv", "ghost", "dest")
    }

    @Test fun mvDryRun(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write("old", "hi\n") }
        commitBoth(s)
        assertSame(s, "mv", "-n", "old", "new")
        // No move happened.
        assertTrue(s.fs.exists("/work/old"))
        assertSameStatus(s)
    }

    // ----- clean -----

    @Test fun cleanDefaultDryRun(
        @TempDir tmp: File,
    ) {
        val s =
            scenario(tmp) { write, _ ->
                write(".gitignore", "*.log\n")
            }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/untracked.txt", "u\n".encodeToByteArray())
            File(s.dir, "untracked.txt").writeText("u\n")
            s.fs.writeBytes("/work/app.log", "l\n".encodeToByteArray())
            File(s.dir, "app.log").writeText("l\n")
        }
        assertSame(s, "clean", "-n")
    }

    @Test fun cleanWithDirs(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/untracked.txt", "u\n".encodeToByteArray())
            File(s.dir, "untracked.txt").writeText("u\n")
            s.fs.writeBytes("/work/ud/f.txt", "d\n".encodeToByteArray())
            File(s.dir, "ud").mkdirs()
            File(s.dir, "ud/f.txt").writeText("d\n")
        }
        assertSame(s, "clean", "-nd")
    }

    @Test fun cleanIncludeIgnored(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write(".gitignore", "*.log\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/untracked.txt", "u\n".encodeToByteArray())
            File(s.dir, "untracked.txt").writeText("u\n")
            s.fs.writeBytes("/work/app.log", "l\n".encodeToByteArray())
            File(s.dir, "app.log").writeText("l\n")
        }
        assertSame(s, "clean", "-nx")
    }

    @Test fun cleanOnlyIgnored(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { write, _ -> write(".gitignore", "*.log\n") }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/untracked.txt", "u\n".encodeToByteArray())
            File(s.dir, "untracked.txt").writeText("u\n")
            s.fs.writeBytes("/work/app.log", "l\n".encodeToByteArray())
            File(s.dir, "app.log").writeText("l\n")
        }
        assertSame(s, "clean", "-nX")
    }

    @Test fun cleanExtraExclude(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/keep.tmp", "k\n".encodeToByteArray())
            File(s.dir, "keep.tmp").writeText("k\n")
            s.fs.writeBytes("/work/drop.txt", "d\n".encodeToByteArray())
            File(s.dir, "drop.txt").writeText("d\n")
        }
        assertSame(s, "clean", "-n", "-e", "keep.tmp")
    }

    @Test fun cleanRequireForceRefusal(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/u.txt", "u\n".encodeToByteArray())
            File(s.dir, "u.txt").writeText("u\n")
        }
        assertSame(s, "clean")
    }

    @Test fun cleanForceRemoves(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/u.txt", "u\n".encodeToByteArray())
            File(s.dir, "u.txt").writeText("u\n")
        }
        assertSame(s, "clean", "-f")
        assertFalse(s.fs.exists("/work/u.txt"))
        assertSameStatus(s)
    }

    @Test fun cleanForceWithDirsRemovesDir(
        @TempDir tmp: File,
    ) {
        val s = scenario(tmp) { _, _ -> }
        commitBoth(s)
        runBlocking {
            s.fs.writeBytes("/work/ud/f.txt", "d\n".encodeToByteArray())
            File(s.dir, "ud").mkdirs()
            File(s.dir, "ud/f.txt").writeText("d\n")
        }
        assertSame(s, "clean", "-fd")
        assertFalse(s.fs.exists("/work/ud"))
        assertSameStatus(s)
    }
}
