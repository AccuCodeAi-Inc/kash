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

/**
 * Differential coverage for `git log` / `git rev-list` filtering, range,
 * and output flags. We build a fixed-history repo through `kash git`,
 * extract it to disk, and assert kash stdout is byte-identical to
 * `/usr/bin/git`'s for the same args. All commit dates are pinned so the
 * default commit-date ordering is deterministic.
 */
class LogRevListDifferentialTest {
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

    private fun runGit(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
        env: Map<String, String>,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env.toMutableMap(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private fun env(epoch: Long): Map<String, String> =
        mapOf(
            "GIT_AUTHOR_DATE" to "$epoch +0000",
            "GIT_COMMITTER_DATE" to "$epoch +0000",
            "GIT_AUTHOR_NAME" to "Alice Dev",
            "GIT_AUTHOR_EMAIL" to "alice@example.com",
            "GIT_COMMITTER_NAME" to "Alice Dev",
            "GIT_COMMITTER_EMAIL" to "alice@example.com",
        )

    /** Build a branchy history in [fs] and mirror it on [tmp] for real git. */
    private fun buildRepo(
        fs: InMemoryFs,
        tmp: File,
    ) {
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q", "-b", "main", env = env(1700000000))
        commit(fs, "f1", "one\n", "first commit", 1700000000)
        commit(fs, "f2", "two\n", "second feature", 1700000100)
        commit(fs, "f3", "three\n", "third fix", 1700000200)
        runBlocking { copyDir(fs, "/work", tmp) }
    }

    private fun commit(
        fs: InMemoryFs,
        path: String,
        content: String,
        msg: String,
        epoch: Long,
    ) {
        runBlocking { fs.writeBytes("/work/$path", content.encodeToByteArray()) }
        runGit(fs, "/work", "add", path, env = env(epoch))
        val r = runGit(fs, "/work", "commit", "-m", msg, env = env(epoch))
        assertEquals(0, r.rc, r.stderr)
    }

    private fun assertMatches(
        fs: InMemoryFs,
        tmp: File,
        vararg args: String,
    ) {
        val kash = runGit(fs, "/work", *args, env = env(1700000000))
        val real = probe.run(args.toList(), tmp, env = mapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"))
        assertEquals(real.stdoutUtf8(), kash.stdout, "stdout mismatch for `git ${args.joinToString(" ")}`")
        assertEquals(real.exitCode, kash.rc, "exit code mismatch for `git ${args.joinToString(" ")}`")
    }

    @Test fun grepAuthorCommitterAndCaseFlags(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        assertMatches(fs, tmp, "log", "--grep=feature", "--oneline")
        assertMatches(fs, tmp, "log", "--grep=FEATURE", "-i", "--oneline")
        assertMatches(fs, tmp, "log", "--author=alice", "--oneline")
        assertMatches(fs, tmp, "log", "--author=nobody", "--oneline")
        assertMatches(fs, tmp, "log", "--committer=Alice", "--oneline")
        assertMatches(fs, tmp, "log", "--invert-grep", "--grep=fix", "--oneline")
        assertMatches(fs, tmp, "log", "--all-match", "--grep=feature", "--grep=second", "--oneline")
        assertMatches(fs, tmp, "log", "--all-match", "--grep=feature", "--grep=zzz", "--oneline")
    }

    @Test fun dateFiltering(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        // commits at 1700000000, 100, 200 -> 2023-11-14T22:13:20/15:00/16:40
        assertMatches(fs, tmp, "log", "--since=2023-11-14T22:14:00", "--oneline")
        assertMatches(fs, tmp, "log", "--until=2023-11-14T22:14:00", "--oneline")
        assertMatches(fs, tmp, "log", "--after=2023-11-14T22:15:30", "--oneline")
        assertMatches(fs, tmp, "log", "--before=2023-11-14T22:13:30", "--oneline")
        assertMatches(fs, tmp, "log", "--since=@1700000150", "--oneline")
    }

    @Test fun historySelectionRangesAndExcludes(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        assertMatches(fs, tmp, "log", "--oneline", "HEAD~2..HEAD")
        assertMatches(fs, tmp, "log", "--oneline", "HEAD~1..HEAD")
        assertMatches(fs, tmp, "rev-list", "HEAD~2..HEAD")
        assertMatches(fs, tmp, "rev-list", "HEAD", "^HEAD~1")
        assertMatches(fs, tmp, "log", "--oneline", "--skip=1")
        assertMatches(fs, tmp, "log", "--oneline", "--reverse")
        assertMatches(fs, tmp, "rev-list", "--reverse", "HEAD")
    }

    @Test fun outputFlags(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        assertMatches(fs, tmp, "log", "--name-only", "-1")
        assertMatches(fs, tmp, "log", "--name-status", "-1")
        assertMatches(fs, tmp, "log", "--name-status")
        assertMatches(fs, tmp, "log", "--abbrev-commit", "--format=%h %s")
        assertMatches(fs, tmp, "log", "--format=%H %s")
        assertMatches(fs, tmp, "log", "--oneline", "--name-status", "-2")
    }

    @Test fun decorateFlags(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        // Add a tag and a second branch for decoration variety.
        runGit(fs, "/work", "tag", "v1.0", env = env(1700000200))
        runGit(fs, "/work", "branch", "develop", env = env(1700000200))
        runBlocking {
            // re-copy with the new refs
            tmp.deleteRecursively()
            copyDir(fs, "/work", tmp)
        }
        assertMatches(fs, tmp, "log", "--decorate=short", "--oneline")
        assertMatches(fs, tmp, "log", "--decorate=full", "--oneline")
        assertMatches(fs, tmp, "log", "--decorate=no", "--oneline")
        assertMatches(fs, tmp, "log", "--format=%H%d")
        assertMatches(fs, tmp, "log", "--format=%H%D")
    }

    @Test fun mergeNoMergeFirstParent(
        @TempDir tmp: File,
    ) {
        // Build a real merge history with /usr/bin/git, then mirror it into
        // the in-memory FS so both sides read identical objects/refs.
        fun rg(
            vararg a: String,
            epoch: Long,
        ) {
            val r =
                probe.run(
                    a.toList(),
                    tmp,
                    env =
                        mapOf(
                            "GIT_AUTHOR_DATE" to "$epoch +0000",
                            "GIT_COMMITTER_DATE" to "$epoch +0000",
                        ),
                )
            check(r.exitCode == 0) { "git ${a.joinToString(" ")}: ${r.stderrUtf8()}" }
        }
        rg("init", "-q", "-b", "main", epoch = 1700000000)
        File(tmp, "f1").writeText("1\n")
        rg("add", "f1", epoch = 1700000000)
        rg("commit", "-m", "base", epoch = 1700000000)
        rg("checkout", "-q", "-b", "feature", epoch = 1700000100)
        File(tmp, "f2").writeText("2\n")
        rg("add", "f2", epoch = 1700000100)
        rg("commit", "-m", "feature work", epoch = 1700000100)
        rg("checkout", "-q", "main", epoch = 1700000200)
        File(tmp, "f3").writeText("3\n")
        rg("add", "f3", epoch = 1700000200)
        rg("commit", "-m", "main work", epoch = 1700000200)
        rg("merge", "--no-ff", "feature", "-m", "merge feature", epoch = 1700000300)

        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking { copyDirToFs(tmp, fs, "/work") }

        assertMatches(fs, tmp, "log", "--no-merges", "--oneline")
        assertMatches(fs, tmp, "log", "--merges", "--oneline")
        assertMatches(fs, tmp, "log", "--first-parent", "--oneline")
        assertMatches(fs, tmp, "rev-list", "--count", "HEAD")
        assertMatches(fs, tmp, "rev-list", "--no-merges", "--count", "HEAD")
    }

    @Test fun defaultFormatNoTrailingBlankBetweenAndAfter(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        // Default medium format: blank line between commits, none after last.
        assertMatches(fs, tmp, "log")
        assertMatches(fs, tmp, "log", "-2")
        assertMatches(fs, tmp, "log", "--pretty=medium")
        assertMatches(fs, tmp, "log", "--format=%s")
        assertMatches(fs, tmp, "log", "--format=%s", "--name-only")
        assertMatches(fs, tmp, "log", "--format=%s", "--name-status")
    }

    @Test fun multipleRevsAndSymmetricRange(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q", "-b", "main", env = env(1700000000))
        commit(fs, "f1", "1\n", "base", 1700000000)
        runGit(fs, "/work", "checkout", "-q", "-b", "b2", env = env(1700000100))
        commit(fs, "f2", "2\n", "b2 work", 1700000100)
        runGit(fs, "/work", "checkout", "-q", "main", env = env(1700000200))
        commit(fs, "f3", "3\n", "main work", 1700000200)
        runBlocking { copyDir(fs, "/work", tmp) }

        assertMatches(fs, tmp, "log", "--oneline", "main", "b2")
        assertMatches(fs, tmp, "log", "--oneline", "main...b2")
        assertMatches(fs, tmp, "log", "--abbrev-commit", "--format=%h %s")
        assertMatches(fs, tmp, "rev-list", "main", "b2")
        assertMatches(fs, tmp, "rev-list", "main...b2")
    }

    @Test fun abbrevCommitDefaultFormat(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        buildRepo(fs, tmp)
        assertMatches(fs, tmp, "log", "--abbrev-commit")
    }

    @Test fun allBranchesTagsScope(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q", "-b", "main", env = env(1700000000))
        commit(fs, "f1", "1\n", "base", 1700000000)
        runGit(fs, "/work", "checkout", "-q", "-b", "side", env = env(1700000100))
        commit(fs, "f2", "2\n", "side work", 1700000100)
        runGit(fs, "/work", "checkout", "-q", "main", env = env(1700000200))
        commit(fs, "f3", "3\n", "main work", 1700000200)
        runGit(fs, "/work", "tag", "rel1", env = env(1700000200))
        runBlocking { copyDir(fs, "/work", tmp) }

        assertMatches(fs, tmp, "log", "--all", "--oneline")
        assertMatches(fs, tmp, "log", "--branches", "--oneline")
        assertMatches(fs, tmp, "rev-list", "--all")
        assertMatches(fs, tmp, "rev-list", "--branches")
    }

    private suspend fun copyDirToFs(
        src: File,
        fs: InMemoryFs,
        dest: String,
    ) {
        fs.mkdirs(dest)
        for (child in src.listFiles().orEmpty()) {
            val out = "$dest/${child.name}"
            if (child.isDirectory) {
                copyDirToFs(child, fs, out)
            } else {
                fs.writeBytes(out, child.readBytes())
            }
        }
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
}
