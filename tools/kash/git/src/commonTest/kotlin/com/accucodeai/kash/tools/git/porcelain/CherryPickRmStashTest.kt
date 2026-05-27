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

class CherryPickRmStashTest {
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

    @Test fun cherryPickApplyCommit() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "base")
        run(fs, "/r", "branch", "feature")
        // feature adds a file.
        run(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/b", "from-feature\n".encodeToByteArray()) }
        run(fs, "/r", "add", "b")
        run(fs, "/r", "commit", "-m", "add b")
        val pickSha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

        run(fs, "/r", "switch", "main")
        val cp = run(fs, "/r", "cherry-pick", pickSha)
        assertEquals(0, cp.rc, cp.stderr)
        assertEquals("from-feature\n", runBlocking { fs.readBytes("/r/b").decodeToString() })
        val log = run(fs, "/r", "log", "--pretty=%s").stdout.lines().filter { it.isNotEmpty() }
        assertEquals(listOf("add b", "base"), log)
    }

    @Test fun revertProducesInverseCommit() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "before\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "before")
        runBlocking { fs.writeBytes("/r/a", "after\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "after")
        val toRevert = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

        val rv = run(fs, "/r", "revert", toRevert)
        assertEquals(0, rv.rc, rv.stderr)
        // Worktree is back to "before".
        assertEquals("before\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
        val log = run(fs, "/r", "log", "--pretty=%s").stdout.lines().filter { it.isNotEmpty() }
        // 3 commits now: revert, after, before.
        assertEquals(3, log.size, log.toString())
        assertTrue(log[0].startsWith("Revert"), log[0])
    }

    @Test fun rmRemovesFromIndexAndWorkTree() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking {
            fs.writeBytes("/r/a", "A\n".encodeToByteArray())
            fs.writeBytes("/r/b", "B\n".encodeToByteArray())
        }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "two files")

        val rm = run(fs, "/r", "rm", "a")
        assertEquals(0, rm.rc, rm.stderr)
        assertFalse(fs.exists("/r/a"))
        val ls = run(fs, "/r", "ls-files", "--stage").stdout
        assertFalse("\ta\n" in ls, ls)
        assertTrue("\tb\n" in ls, ls)
    }

    @Test fun rmCachedKeepsFileOnDisk() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "A\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "x")
        run(fs, "/r", "rm", "--cached", "a")
        assertTrue(fs.exists("/r/a")) // still on disk
        assertFalse("\ta\n" in run(fs, "/r", "ls-files", "--stage").stdout)
    }

    @Test fun mvRenamesInIndexAndOnDisk() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/old", "hi\n".encodeToByteArray()) }
        run(fs, "/r", "add", "old")
        run(fs, "/r", "commit", "-m", "init")

        run(fs, "/r", "mv", "old", "new")
        assertFalse(fs.exists("/r/old"))
        assertTrue(fs.exists("/r/new"))
        val ls = run(fs, "/r", "ls-files", "--stage").stdout
        assertTrue("\tnew\n" in ls, ls)
        assertFalse("\told\n" in ls, ls)
    }

    @Test fun cleanRemovesUntracked() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/tracked", "T\n".encodeToByteArray()) }
        run(fs, "/r", "add", "tracked")
        run(fs, "/r", "commit", "-m", "init")
        runBlocking {
            fs.writeBytes("/r/untracked", "U\n".encodeToByteArray())
            fs.writeBytes("/r/also", "A\n".encodeToByteArray())
        }
        // refuses without -f
        assertEquals(1, run(fs, "/r", "clean").rc)
        // dry-run lists
        val dr = run(fs, "/r", "clean", "-n")
        assertTrue("Would remove also" in dr.stdout, dr.stdout)
        assertTrue("Would remove untracked" in dr.stdout, dr.stdout)
        // Files still present after dry-run.
        assertTrue(fs.exists("/r/untracked"))
        // -f actually deletes them.
        run(fs, "/r", "clean", "-f")
        assertFalse(fs.exists("/r/untracked"))
        assertFalse(fs.exists("/r/also"))
        assertTrue(fs.exists("/r/tracked"))
    }

    @Test fun stashPushPopRestoresWork() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/a", "v2-wip\n".encodeToByteArray()) }
        run(fs, "/r", "stash")
        // Work tree back to v1.
        assertEquals("v1\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
        val list = run(fs, "/r", "stash", "list").stdout
        assertTrue("stash@{0}" in list, list)
        run(fs, "/r", "stash", "pop")
        assertEquals("v2-wip\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
        // Pop drops the stash.
        assertEquals("", run(fs, "/r", "stash", "list").stdout)
    }

    @Test fun stashDropRemovesWithoutApplying() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        run(fs, "/r", "stash")
        run(fs, "/r", "stash", "drop")
        assertEquals("", run(fs, "/r", "stash", "list").stdout)
        assertEquals("v1\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
    }
}
