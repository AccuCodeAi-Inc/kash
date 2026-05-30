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

/**
 * Real-git-free unit tests for `git add` / `git rm` / `git mv` /
 * `git clean`. These pin exact messages, exit codes, and index/worktree
 * effects without requiring `/usr/bin/git` on the host.
 */
class AddRmMvCleanUnitTest {
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

    private suspend fun initRepo(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
    }

    // ----- add -----

    @Test fun addNothingSpecified() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val o = run(fs, "/r", "add")
            assertEquals(0, o.rc)
            assertTrue("Nothing specified, nothing added." in o.stderr, o.stderr)
        }

    @Test fun addDryRunDoesNotStage() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray())
            val o = run(fs, "/r", "add", "-n", "a.txt")
            assertEquals(0, o.rc)
            assertEquals("add 'a.txt'\n", o.stdout)
            // Index empty — nothing staged.
            assertEquals("", run(fs, "/r", "ls-files").stdout)
        }

    @Test fun addIgnoredExplicitErrors() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            fs.writeBytes("/r/x.log", "l\n".encodeToByteArray())
            val o = run(fs, "/r", "add", "x.log")
            assertEquals(1, o.rc)
            assertTrue("ignored by one of your .gitignore files" in o.stderr, o.stderr)
            assertTrue("x.log" in o.stderr, o.stderr)
        }

    @Test fun addIgnoredWithForceStages() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            fs.writeBytes("/r/x.log", "l\n".encodeToByteArray())
            val o = run(fs, "/r", "add", "-f", "x.log")
            assertEquals(0, o.rc)
            assertTrue("x.log\n" in run(fs, "/r", "ls-files").stdout)
        }

    @Test fun addInteractiveRejected() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            assertEquals(129, run(fs, "/r", "add", "-i").rc)
            assertEquals(129, run(fs, "/r", "add", "-p").rc)
        }

    @Test fun addNonexistentErrors() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val o = run(fs, "/r", "add", "ghost")
            assertEquals(128, o.rc)
            assertTrue("did not match any files" in o.stderr, o.stderr)
        }

    // ----- rm -----

    @Test fun rmNoPathspecErrors() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val o = run(fs, "/r", "rm")
            assertEquals(128, o.rc)
            assertTrue("No pathspec was given" in o.stderr, o.stderr)
        }

    @Test fun rmRefusalMessageLocalModifications() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/f.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "f.txt")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/f.txt", "changed\n".encodeToByteArray())
            val o = run(fs, "/r", "rm", "f.txt")
            assertEquals(1, o.rc)
            assertEquals(
                "error: refusing to remove a file with uncommitted work-tree edits:\n" +
                    "    f.txt\n" +
                    "(pass --cached to drop only the index entry, or -f to remove it anyway)\n",
                o.stderr,
            )
            // Atomic refusal: file still present, still tracked.
            assertTrue(fs.exists("/r/f.txt"))
            assertTrue("f.txt\n" in run(fs, "/r", "ls-files").stdout)
        }

    @Test fun rmRefusalStagedDifferentFromBoth() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/f.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "f.txt")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/f.txt", "staged\n".encodeToByteArray())
            run(fs, "/r", "add", "f.txt")
            fs.writeBytes("/r/f.txt", "worktree\n".encodeToByteArray())
            val o = run(fs, "/r", "rm", "f.txt")
            assertEquals(1, o.rc)
            assertEquals(
                "error: refusing to remove a file whose staged content differs from both the work tree and HEAD:\n" +
                    "    f.txt\n" +
                    "(pass -f to remove it anyway)\n",
                o.stderr,
            )
        }

    @Test fun rmDirNeedsRecurse() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/d/a.txt", "a\n".encodeToByteArray())
            run(fs, "/r", "add", "d/a.txt")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "rm", "d")
            assertEquals(128, o.rc)
            assertEquals("fatal: not removing 'd' recursively without -r\n", o.stderr)
        }

    @Test fun rmIgnoreUnmatchNoError() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val o = run(fs, "/r", "rm", "--ignore-unmatch", "ghost")
            assertEquals(0, o.rc)
            assertEquals("", o.stderr)
        }

    @Test fun rmCachedKeepsWorktree() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/f.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "f.txt")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "rm", "--cached", "f.txt")
            assertEquals(0, o.rc)
            assertEquals("rm 'f.txt'\n", o.stdout)
            assertTrue(fs.exists("/r/f.txt"))
            assertFalse("f.txt\n" in run(fs, "/r", "ls-files").stdout)
        }

    @Test fun rmQuietSuppressesOutput() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/f.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "f.txt")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "rm", "-q", "f.txt")
            assertEquals(0, o.rc)
            assertEquals("", o.stdout)
        }

    // ----- mv -----

    @Test fun mvDestinationExistsMessage() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray())
            fs.writeBytes("/r/b.txt", "b\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "mv", "a.txt", "b.txt")
            assertEquals(128, o.rc)
            assertEquals("fatal: destination exists, source=a.txt, destination=b.txt\n", o.stderr)
        }

    @Test fun mvBadSourceMessage() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val o = run(fs, "/r", "mv", "ghost", "dest")
            assertEquals(128, o.rc)
            assertEquals("fatal: bad source, source=ghost, destination=dest\n", o.stderr)
        }

    @Test fun mvNotUnderVersionControlMessage() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/u", "u\n".encodeToByteArray())
            val o = run(fs, "/r", "mv", "u", "dest")
            assertEquals(128, o.rc)
            assertEquals("fatal: not under version control, source=u, destination=dest\n", o.stderr)
        }

    @Test fun mvDryRunPrintsCheckingAndRenaming() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/old", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "old")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "mv", "-n", "old", "new")
            assertEquals(0, o.rc)
            assertEquals("Checking rename of 'old' to 'new'\nRenaming old to new\n", o.stdout)
            // No actual move.
            assertTrue(fs.exists("/r/old"))
            assertFalse(fs.exists("/r/new"))
        }

    @Test fun mvIntoDirectory() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray())
            fs.writeBytes("/r/b.txt", "b\n".encodeToByteArray())
            fs.mkdirs("/r/sub")
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            val o = run(fs, "/r", "mv", "a.txt", "b.txt", "sub")
            assertEquals(0, o.rc)
            assertTrue(fs.exists("/r/sub/a.txt"))
            assertTrue(fs.exists("/r/sub/b.txt"))
            val ls = run(fs, "/r", "ls-files").stdout
            assertTrue("sub/a.txt\n" in ls, ls)
            assertTrue("sub/b.txt\n" in ls, ls)
        }

    // ----- clean -----

    @Test fun cleanRequireForceRefusal() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/u.txt", "u\n".encodeToByteArray())
            val o = run(fs, "/r", "clean")
            assertEquals(128, o.rc)
            assertEquals(
                "fatal: clean.requireForce is true and -f not given: refusing to clean\n",
                o.stderr,
            )
            assertTrue(fs.exists("/r/u.txt"))
        }

    @Test fun cleanDefaultLeavesTrackedAndIgnored() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            fs.writeBytes("/r/tracked.txt", "t\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/untracked.txt", "u\n".encodeToByteArray())
            fs.writeBytes("/r/app.log", "l\n".encodeToByteArray())
            val o = run(fs, "/r", "clean", "-n")
            assertEquals("Would remove untracked.txt\n", o.stdout)
        }

    @Test fun cleanOnlyIgnored() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/untracked.txt", "u\n".encodeToByteArray())
            fs.writeBytes("/r/app.log", "l\n".encodeToByteArray())
            val o = run(fs, "/r", "clean", "-nX")
            assertEquals("Would remove app.log\n", o.stdout)
        }

    @Test fun cleanIncludeIgnored() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/untracked.txt", "u\n".encodeToByteArray())
            fs.writeBytes("/r/app.log", "l\n".encodeToByteArray())
            val o = run(fs, "/r", "clean", "-nx")
            assertEquals("Would remove app.log\nWould remove untracked.txt\n", o.stdout)
        }

    @Test fun cleanForceRemovesDirsWithD() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/keep.txt", "k\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "init")
            fs.writeBytes("/r/ud/f.txt", "d\n".encodeToByteArray())
            val o = run(fs, "/r", "clean", "-fd")
            assertEquals(0, o.rc)
            assertEquals("Removing ud/\n", o.stdout)
            assertFalse(fs.exists("/r/ud"))
            assertTrue(fs.exists("/r/keep.txt"))
        }

    @Test fun cleanExtraExcludeProtects() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/keep.tmp", "k\n".encodeToByteArray())
            fs.writeBytes("/r/drop.txt", "d\n".encodeToByteArray())
            val o = run(fs, "/r", "clean", "-n", "-e", "keep.tmp")
            assertEquals("Would remove drop.txt\n", o.stdout)
        }
}
