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
 * Real-git differential for `git stash`.
 *
 * Important divergence from real git: real git stash uses a 2- or
 * 3-parent merge commit (parent[0] = HEAD, parent[1] = index tree,
 * parent[2] = untracked) and walks `refs/stash`'s reflog for
 * `git stash list`. Kash uses single-parent commits chained via
 * parent[1] (previous stash) so `list` walks parent links instead of
 * a reflog. That means:
 *
 *  - Our `refs/stash` blob is still a valid commit object that real
 *    git's `cat-file`/`show`/`fsck` accept.
 *  - Real git's `git stash list` will NOT enumerate our stashes (it
 *    requires `logs/refs/stash` reflog entries, which we don't write).
 *
 * These tests verify the *parts that should be byte-compatible* — the
 * object DB is real-git-loadable — while documenting the `stash list`
 * divergence so it doesn't surprise future readers.
 */
class StashDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
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
                env = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Triple(res.exitCode, out.readString(), err.readString())
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

    @Test fun stashCommitIsValidObjectAndReadableByRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")
        val headSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        // Stash a modification.
        runBlocking { fs.writeBytes("/r/f", "v2-wip\n".encodeToByteArray()) }
        val st = runGit(fs, "/r", "stash")
        assertEquals(0, st.first, st.third)
        // Worktree is back to v1.
        assertEquals("v1\n", runBlocking { fs.readBytes("/r/f").decodeToString() })

        runBlocking { copyDir(fs, "/r", tmp) }

        // Real git's fsck accepts the resulting repo (including the stash commit).
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())

        // refs/stash exists and is a real commit object.
        val stashSha = probe.run(listOf("rev-parse", "refs/stash"), tmp)
        assertEquals(0, stashSha.exitCode, stashSha.stderrUtf8())
        // Real git's cat-file -t says it's a commit.
        assertEquals(
            "commit\n",
            probe.run(listOf("cat-file", "-t", stashSha.stdoutUtf8().trim()), tmp).stdoutUtf8(),
        )
        // The first parent of refs/stash is HEAD at stash time.
        val raw = probe.run(listOf("cat-file", "-p", "refs/stash"), tmp).stdoutUtf8()
        assertTrue(raw.contains("parent $headSha"), "stash's first parent should be HEAD-at-stash:\n$raw")
        // `git show refs/stash:f` returns the stashed worktree content.
        assertEquals(
            "v2-wip\n",
            probe.run(listOf("show", "refs/stash:f"), tmp).stdoutUtf8(),
        )
    }

    @Test fun multipleKashStashesAllRealGitLoadable(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")

        // Stash 1.
        runBlocking { fs.writeBytes("/r/f", "first-wip\n".encodeToByteArray()) }
        runGit(fs, "/r", "stash")
        // Stash 2.
        runBlocking { fs.writeBytes("/r/f", "second-wip\n".encodeToByteArray()) }
        runGit(fs, "/r", "stash")

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(0, probe.run(listOf("fsck", "--strict"), tmp).exitCode)

        // Walk our stash chain via cat-file. The kash chain is
        // newest→oldest via parent[1].
        val tip = probe.run(listOf("rev-parse", "refs/stash"), tmp).stdoutUtf8().trim()
        val tipBody = probe.run(listOf("cat-file", "-p", tip), tmp).stdoutUtf8()
        // Tip carries the second stash's content.
        assertEquals(
            "second-wip\n",
            probe.run(listOf("show", "refs/stash:f"), tmp).stdoutUtf8(),
        )
        // Tip has two parents: HEAD-at-stash + previous stash.
        val parentLines = tipBody.lines().filter { it.startsWith("parent ") }
        assertEquals(2, parentLines.size, "stash tip should have 2 parents:\n$tipBody")
        // The second parent's tree has the first stash's content.
        val prevStash = parentLines[1].removePrefix("parent ")
        assertEquals(
            "first-wip\n",
            probe.run(listOf("show", "$prevStash:f"), tmp).stdoutUtf8(),
        )
    }

    @Test fun stashAfterPopHasRefsStashGone(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/f", "v2\n".encodeToByteArray()) }
        runGit(fs, "/r", "stash")
        runGit(fs, "/r", "stash", "pop")

        runBlocking { copyDir(fs, "/r", tmp) }
        // After pop with no remaining stashes, refs/stash should be gone.
        val rev = probe.run(listOf("rev-parse", "--verify", "refs/stash"), tmp)
        assertTrue(rev.exitCode != 0, "refs/stash should not exist after final pop")
    }
}
