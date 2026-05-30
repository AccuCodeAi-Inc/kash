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
 * Kash now mirrors real git's stash shape: each stash is a `w`
 * (worktree) commit with parents `[HEAD, i, (u)]` where `i` is the
 * index-snapshot commit and `u` (optional) holds untracked files.
 * Enumeration of `stash@{N}` is driven by the `refs/stash` reflog
 * exactly as real git does, so `git stash list` against the resulting
 * repo enumerates the same entries.
 *
 * These tests verify the object DB is real-git-loadable and that the
 * reflog-driven enumeration matches.
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

        // refs/stash (stash@{0}) carries the most recent stash's content.
        val tip = probe.run(listOf("rev-parse", "refs/stash"), tmp).stdoutUtf8().trim()
        val tipBody = probe.run(listOf("cat-file", "-p", tip), tmp).stdoutUtf8()
        assertEquals(
            "second-wip\n",
            probe.run(listOf("show", "refs/stash:f"), tmp).stdoutUtf8(),
        )
        // Real-git shape: parent[0] = HEAD-at-stash, parent[1] = index commit.
        val parentLines = tipBody.lines().filter { it.startsWith("parent ") }
        assertEquals(2, parentLines.size, "stash tip should have 2 parents:\n$tipBody")

        // Real git enumerates BOTH stashes from our reflog, newest first.
        val list = probe.run(listOf("stash", "list"), tmp).stdoutUtf8()
        assertTrue("stash@{0}" in list && "stash@{1}" in list, "real git should list two stashes:\n$list")
        // stash@{1}'s worktree tree carries the first stash's content.
        assertEquals(
            "first-wip\n",
            probe.run(listOf("show", "stash@{1}:f"), tmp).stdoutUtf8(),
        )
    }

    /**
     * Drive the same scenario through real git and assert kash's
     * user-visible `stash list` line matches modulo the short-sha and
     * branch name (both of which differ because object SHAs and the
     * default branch may differ between the two repos). We normalize the
     * sha7 and branch to placeholders and compare the resulting shapes.
     */
    @Test fun listFormatMatchesRealGit(
        @TempDir tmp: File,
    ) {
        fun normalize(line: String): String =
            line
                .replace(Regex(" [0-9a-f]{7} "), " <sha7> ")

        // Real git.
        val rg = probe.freshRepo("main")
        File(rg, "a").writeText("base\n")
        probe.run(listOf("add", "a"), rg)
        probe.run(listOf("commit", "-q", "-m", "init"), rg)
        File(rg, "a").writeText("wip\n")
        probe.run(listOf("stash"), rg)
        val rgList = probe.run(listOf("stash", "list"), rg).stdoutUtf8().trimEnd('\n')

        // kash.
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q", "-b", "main")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/a", "wip\n".encodeToByteArray()) }
        runGit(fs, "/r", "stash")
        val kashList = runGit(fs, "/r", "stash", "list").second.trimEnd('\n')

        assertEquals(normalize(rgList), normalize(kashList))
    }

    @Test fun dropMessageMatchesRealGitShape(
        @TempDir tmp: File,
    ) {
        // Real git's drop message: "Dropped stash@{0} (<full-sha>)".
        val rg = probe.freshRepo("main")
        File(rg, "a").writeText("base\n")
        probe.run(listOf("add", "a"), rg)
        probe.run(listOf("commit", "-q", "-m", "init"), rg)
        File(rg, "a").writeText("wip\n")
        probe.run(listOf("stash"), rg)
        val rgDrop = probe.run(listOf("stash", "drop"), rg).stdoutUtf8().trimEnd('\n')
        // git 2.50 echoes the resolved ref ("refs/stash@{0}"); older
        // versions print "stash@{0}". Accept either shape.
        assertTrue(
            Regex("""^Dropped (refs/)?stash@\{0} \([0-9a-f]{40}\)$""").matches(rgDrop),
            "real git drop line: <$rgDrop>",
        )

        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q", "-b", "main")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/a", "wip\n".encodeToByteArray()) }
        runGit(fs, "/r", "stash")
        val kashDrop = runGit(fs, "/r", "stash", "drop").second.trimEnd('\n')
        // kash uses the documented short spelling "stash@{0}".
        assertTrue(
            Regex("""^Dropped stash@\{0} \([0-9a-f]{40}\)$""").matches(kashDrop),
            "kash drop line: <$kashDrop>",
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
