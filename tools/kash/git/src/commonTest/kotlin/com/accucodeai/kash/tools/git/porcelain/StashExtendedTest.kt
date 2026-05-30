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
 * Unit coverage for the extended `git stash` surface: clear, drop by
 * index, stash@{N} addressing, list formatting, -u/-a, --index,
 * show [-p], and branch.
 */
class StashExtendedTest {
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
        fs.writeBytes("/r/a", "base\n".encodeToByteArray())
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "init")
    }

    @Test fun listFormatIsWipOnBranchShortShaSubject() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "wip\n".encodeToByteArray())
            run(fs, "/r", "stash")
            val list = run(fs, "/r", "stash", "list").stdout.trimEnd('\n')
            // stash@{0}: WIP on <branch>: <sha7> <subject>
            assertTrue(
                Regex("""^stash@\{0}: WIP on [^:]+: [0-9a-f]{7} init$""").matches(list),
                "unexpected list line: <$list>",
            )
        }

    @Test fun customMessageListsAsOnBranch() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "wip\n".encodeToByteArray())
            run(fs, "/r", "stash", "push", "-m", "my work")
            val list = run(fs, "/r", "stash", "list").stdout.trimEnd('\n')
            assertTrue(
                Regex("""^stash@\{0}: On [^:]+: my work$""").matches(list),
                "unexpected list line: <$list>",
            )
        }

    @Test fun clearDropsAllStashes() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "w1\n".encodeToByteArray())
            run(fs, "/r", "stash")
            fs.writeBytes("/r/a", "w2\n".encodeToByteArray())
            run(fs, "/r", "stash")
            assertEquals(2, run(fs, "/r", "stash", "list").stdout.lines().count { it.isNotEmpty() })
            run(fs, "/r", "stash", "clear")
            assertEquals("", run(fs, "/r", "stash", "list").stdout)
        }

    @Test fun dropByIndexRenumbers() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "first\n".encodeToByteArray())
            run(fs, "/r", "stash")
            fs.writeBytes("/r/a", "second\n".encodeToByteArray())
            run(fs, "/r", "stash")
            // stash@{0}=second, stash@{1}=first. Drop the older.
            val d = run(fs, "/r", "stash", "drop", "stash@{1}")
            assertEquals(0, d.rc, d.stderr)
            assertTrue(d.stdout.startsWith("Dropped stash@{1} ("), d.stdout)
            val list = run(fs, "/r", "stash", "list").stdout.lines().filter { it.isNotEmpty() }
            assertEquals(1, list.size, list.toString())
            assertTrue(list[0].startsWith("stash@{0}"), list[0])
            // Remaining stash is "second".
            run(fs, "/r", "stash", "pop")
            assertEquals("second\n", fs.readBytes("/r/a").decodeToString())
        }

    @Test fun applyKeepsStashPopDrops() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "wip\n".encodeToByteArray())
            run(fs, "/r", "stash")
            run(fs, "/r", "stash", "apply")
            assertEquals("wip\n", fs.readBytes("/r/a").decodeToString())
            assertEquals(1, run(fs, "/r", "stash", "list").stdout.lines().count { it.isNotEmpty() })
            run(fs, "/r", "stash", "pop")
            assertEquals("", run(fs, "/r", "stash", "list").stdout)
        }

    @Test fun applyAddressesByIndex() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "first\n".encodeToByteArray())
            run(fs, "/r", "stash")
            fs.writeBytes("/r/a", "second\n".encodeToByteArray())
            run(fs, "/r", "stash")
            // Apply the older one explicitly.
            val ap = run(fs, "/r", "stash", "apply", "stash@{1}")
            assertEquals(0, ap.rc, ap.stderr)
            assertEquals("first\n", fs.readBytes("/r/a").decodeToString())
        }

    @Test fun includeUntrackedStashesAndRestores() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/new.txt", "hello\n".encodeToByteArray())
            run(fs, "/r", "stash", "-u")
            // Untracked file is gone after stash.
            assertFalse(fs.exists("/r/new.txt"))
            run(fs, "/r", "stash", "pop")
            assertTrue(fs.exists("/r/new.txt"))
            assertEquals("hello\n", fs.readBytes("/r/new.txt").decodeToString())
        }

    @Test fun untrackedNotStashedWithoutFlag() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "wip\n".encodeToByteArray())
            fs.writeBytes("/r/new.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "stash")
            // Untracked file is untouched without -u.
            assertTrue(fs.exists("/r/new.txt"))
            assertEquals("base\n", fs.readBytes("/r/a").decodeToString())
        }

    @Test fun popWithIndexRestoresStagedState() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            // Stage a change, then modify worktree further.
            fs.writeBytes("/r/a", "staged\n".encodeToByteArray())
            run(fs, "/r", "add", "a")
            run(fs, "/r", "stash")
            // After pop --index, staged content should be in the index.
            run(fs, "/r", "stash", "pop", "--index")
            assertEquals("staged\n", fs.readBytes("/r/a").decodeToString())
            val staged = run(fs, "/r", "diff", "--cached", "--name-only").stdout
            assertTrue("a" in staged, "expected 'a' staged after pop --index: <$staged>")
        }

    @Test fun showDefaultDiffstat() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "base\nmore\n".encodeToByteArray())
            run(fs, "/r", "stash")
            val show = run(fs, "/r", "stash", "show").stdout
            assertTrue("a" in show && "1 file changed" in show, show)
            assertFalse("diff --git" in show, show)
        }

    @Test fun showPatch() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "base\nmore\n".encodeToByteArray())
            run(fs, "/r", "stash")
            val show = run(fs, "/r", "stash", "show", "-p").stdout
            assertTrue("diff --git a/a b/a" in show, show)
            assertTrue("+more" in show, show)
        }

    @Test fun branchCreatesAndAppliesAndDrops() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            fs.writeBytes("/r/a", "wip\n".encodeToByteArray())
            run(fs, "/r", "stash")
            val b = run(fs, "/r", "stash", "branch", "feature")
            assertEquals(0, b.rc, b.stderr)
            assertTrue("Switched to a new branch 'feature'" in b.stdout, b.stdout)
            // On the new branch with stash applied.
            assertEquals("wip\n", fs.readBytes("/r/a").decodeToString())
            // Stash was dropped.
            assertEquals("", run(fs, "/r", "stash", "list").stdout)
            val branchLine = run(fs, "/r", "branch").stdout
            assertTrue("feature" in branchLine, branchLine)
        }

    @Test fun dropOnEmptyErrors() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val d = run(fs, "/r", "stash", "drop")
            assertEquals(1, d.rc)
            assertTrue("No stash entries found." in d.stderr, d.stderr)
        }

    @Test fun popOnEmptyErrors() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val p = run(fs, "/r", "stash", "pop")
            assertEquals(1, p.rc)
            assertTrue("No stash entries found." in p.stderr, p.stderr)
        }

    @Test fun noLocalChangesMessage() =
        runTest {
            val fs = InMemoryFs()
            initRepo(fs)
            val s = run(fs, "/r", "stash")
            assertEquals("No local changes to save\n", s.stdout)
        }
}
