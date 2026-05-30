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
 * Differential tests for the richer `git commit` flag surface: `-a`,
 * `--author`, `--date`, `-F`, multi-`-m`, partial commit via pathspec,
 * `-C`/`-c`, `--reset-author`, `--amend --no-edit`,
 * `--allow-empty-message`.
 *
 * Each test builds a repo through `kash git`, extracts it to a temp dir,
 * and confronts `/usr/bin/git` for ground truth. Identity + dates are
 * pinned so commit SHAs are deterministic and directly comparable.
 */
class CommitFlagsDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }

        private val PINNED =
            mapOf(
                "GIT_AUTHOR_NAME" to "Test User",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_AUTHOR_DATE" to "1700000000 +0000",
                "GIT_COMMITTER_NAME" to "Test User",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
                "GIT_COMMITTER_DATE" to "1700000000 +0000",
                "HOME" to "/home",
            )
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
        stdin: String = "",
        env: Map<String, String> = PINNED,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer().also { it.write(stdin.encodeToByteArray()) }
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env.toMutableMap(),
                cwd = cwd,
                stdin = inBuf.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private suspend fun write(
        fs: InMemoryFs,
        path: String,
        content: String,
    ) {
        fs.writeBytes(path, content.encodeToByteArray())
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

    /** Set up `/work` with one committed file `a=a1\n`. */
    private fun freshWithInitialCommit(fs: InMemoryFs) {
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { write(fs, "/work/a", "a1\n") }
        runGit(fs, "/work", "add", "a")
        val c = runGit(fs, "/work", "commit", "-m", "init")
        assertEquals(0, c.rc, c.stderr)
    }

    private fun headSha(
        fs: InMemoryFs,
        cwd: String = "/work",
    ): String = runGit(fs, cwd, "rev-parse", "HEAD").stdout.trim()

    @Test fun dashAStagesModificationsAndDeletionsNotUntracked(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            write(fs, "/work/a", "a1\n")
            write(fs, "/work/b", "b1\n")
        }
        runGit(fs, "/work", "add", "-A")
        runGit(fs, "/work", "commit", "-m", "init")

        runBlocking {
            write(fs, "/work/a", "a2\n")
            fs.remove("/work/b")
            write(fs, "/work/c", "untracked\n")
        }
        val cm = runGit(fs, "/work", "commit", "-a", "-m", "all changes")
        assertEquals(0, cm.rc, cm.stderr)

        copyToDiskAndCompareTree(fs, tmp, "a2\n", deleted = listOf("b"), absent = listOf("c"))
        // c stays untracked.
        val st = runGit(fs, "/work", "status", "--porcelain").stdout
        assertTrue("?? c\n" in st, st)
    }

    private fun copyToDiskAndCompareTree(
        fs: InMemoryFs,
        tmp: File,
        expectedA: String,
        deleted: List<String>,
        absent: List<String>,
    ) {
        runBlocking { copyDir(fs, "/work", tmp) }
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp, PINNED)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())
        assertEquals(expectedA, probe.run(listOf("show", "HEAD:a"), tmp, PINNED).stdoutUtf8())
        for (d in deleted) {
            val r = probe.run(listOf("cat-file", "-e", "HEAD:$d"), tmp, PINNED)
            assertTrue(r.exitCode != 0, "expected $d absent from HEAD tree")
        }
        for (d in absent) {
            val r = probe.run(listOf("cat-file", "-e", "HEAD:$d"), tmp, PINNED)
            assertTrue(r.exitCode != 0, "expected $d absent from HEAD tree")
        }
    }

    @Test fun dashAmCombinedShortFlag(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/a", "a2\n") }
        val cm = runGit(fs, "/work", "commit", "-am", "combined")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals("combined\n", probe.run(listOf("log", "-1", "--format=%s"), tmp, PINNED).stdoutUtf8())
        assertEquals("a2\n", probe.run(listOf("show", "HEAD:a"), tmp, PINNED).stdoutUtf8())
    }

    @Test fun multipleDashMJoinAsParagraphs(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-m", "subject", "-m", "para two", "-m", "para three")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        // git's %B appends a trailing newline to the stored message.
        assertEquals(
            "subject\n\npara two\n\npara three\n\n",
            probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8(),
        )
    }

    @Test fun authorAndDateOverride(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        // Use the raw form for --date so both engines agree exactly.
        val cm =
            runGit(
                fs,
                "/work",
                "commit",
                "-m",
                "authored",
                "--author=Alice <alice@example.com>",
                "--date=1112937193 +0200",
            )
        assertEquals(0, cm.rc, cm.stderr)
        val kashHead = headSha(fs)

        // Build the same with real git.
        val real = probe.freshRepo()
        try {
            File(real, "a").writeText("a1\n")
            probe.run(listOf("add", "a"), real, PINNED)
            probe.run(listOf("commit", "-m", "init"), real, PINNED)
            File(real, "b").writeText("b\n")
            probe.run(listOf("add", "b"), real, PINNED)
            probe.run(
                listOf(
                    "commit",
                    "-m",
                    "authored",
                    "--author=Alice <alice@example.com>",
                    "--date=1112937193 +0200",
                ),
                real,
                PINNED,
            )
            val realHead = probe.run(listOf("rev-parse", "HEAD"), real, PINNED).stdoutUtf8().trim()
            assertEquals(realHead, kashHead, "SHA mismatch for --author/--date commit")
        } finally {
            real.deleteRecursively()
        }
    }

    @Test fun dateIso8601(
        @TempDir tmp: File,
    ) {
        // ISO date with explicit offset must produce the right epoch.
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm =
            runGit(fs, "/work", "commit", "-m", "iso", "--date=2005-04-07 22:13:13 +0200")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals(
            "1112904793 +0200\n",
            probe.run(listOf("log", "-1", "--format=%ad", "--date=raw"), tmp, PINNED).stdoutUtf8(),
        )
    }

    @Test fun messageFromFile(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking {
            write(fs, "/work/b", "b\n")
            write(fs, "/work/MSG.txt", "from file subject\n\nfrom file body\n")
        }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-F", "MSG.txt")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals(
            "from file subject\n\nfrom file body\n\n",
            probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8(),
        )
    }

    @Test fun messageFromStdin(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-F", "-", stdin = "stdin subject\n")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals(
            "stdin subject\n\n",
            probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8(),
        )
    }

    @Test fun partialCommitViaPathspec(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            write(fs, "/work/a", "a1\n")
            write(fs, "/work/b", "b1\n")
        }
        runGit(fs, "/work", "add", "-A")
        runGit(fs, "/work", "commit", "-m", "init")

        // Stage both, then partial-commit only `a`.
        runBlocking {
            write(fs, "/work/a", "a2\n")
            write(fs, "/work/b", "b2\n")
        }
        runGit(fs, "/work", "add", "-A")
        val cm = runGit(fs, "/work", "commit", "-m", "only a", "--", "a")
        assertEquals(0, cm.rc, cm.stderr)

        runBlocking { copyDir(fs, "/work", tmp) }
        // Committed tree: a=a2, b=b1.
        assertEquals("a2\n", probe.run(listOf("show", "HEAD:a"), tmp, PINNED).stdoutUtf8())
        assertEquals("b1\n", probe.run(listOf("show", "HEAD:b"), tmp, PINNED).stdoutUtf8())
        // Index still has b2 staged.
        assertEquals("b2\n", probe.run(listOf("show", ":b"), tmp, PINNED).stdoutUtf8())
        val st = probe.run(listOf("status", "--porcelain"), tmp, PINNED).stdoutUtf8()
        assertEquals("M  b\n", st)
    }

    @Test fun partialCommitMatchesRealGitSha(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            write(fs, "/work/a", "a1\n")
            write(fs, "/work/b", "b1\n")
        }
        runGit(fs, "/work", "add", "-A")
        runGit(fs, "/work", "commit", "-m", "init")
        runBlocking {
            write(fs, "/work/a", "a2\n")
            write(fs, "/work/b", "b2\n")
        }
        runGit(fs, "/work", "add", "-A")
        runGit(fs, "/work", "commit", "-m", "only a", "--", "a")
        val kashHead = headSha(fs)

        val real = probe.freshRepo()
        try {
            File(real, "a").writeText("a1\n")
            File(real, "b").writeText("b1\n")
            probe.run(listOf("add", "-A"), real, PINNED)
            probe.run(listOf("commit", "-m", "init"), real, PINNED)
            File(real, "a").writeText("a2\n")
            File(real, "b").writeText("b2\n")
            probe.run(listOf("add", "-A"), real, PINNED)
            probe.run(listOf("commit", "-m", "only a", "--", "a"), real, PINNED)
            val realHead = probe.run(listOf("rev-parse", "HEAD"), real, PINNED).stdoutUtf8().trim()
            assertEquals(realHead, kashHead)
        } finally {
            real.deleteRecursively()
        }
    }

    @Test fun reuseMessageWithDashC(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { write(fs, "/work/a", "a1\n") }
        runGit(fs, "/work", "add", "a")
        // First commit by a distinct author.
        val firstEnv =
            PINNED +
                mapOf(
                    "GIT_AUTHOR_NAME" to "Orig Auth",
                    "GIT_AUTHOR_EMAIL" to "orig@x.com",
                    "GIT_AUTHOR_DATE" to "1000000000 +0000",
                )
        runGit(fs, "/work", "commit", "-m", "first subject\n\nfirst body", env = firstEnv)
        val src = headSha(fs)

        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-C", src)
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        // Reused message AND author (orig@x.com), but committer is current.
        assertEquals(
            "first subject\n\nfirst body\n\n",
            probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8(),
        )
        assertEquals(
            "Orig Auth <orig@x.com> 1000000000 +0000\n",
            probe.run(listOf("log", "-1", "--format=%an <%ae> %ad", "--date=raw"), tmp, PINNED).stdoutUtf8(),
        )
        assertEquals(
            "Test User <test@example.com> 1700000000 +0000\n",
            probe.run(listOf("log", "-1", "--format=%cn <%ce> %cd", "--date=raw"), tmp, PINNED).stdoutUtf8(),
        )
    }

    @Test fun reuseMessageWithResetAuthor(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { write(fs, "/work/a", "a1\n") }
        runGit(fs, "/work", "add", "a")
        val firstEnv =
            PINNED +
                mapOf(
                    "GIT_AUTHOR_NAME" to "Orig Auth",
                    "GIT_AUTHOR_EMAIL" to "orig@x.com",
                    "GIT_AUTHOR_DATE" to "1000000000 +0000",
                )
        runGit(fs, "/work", "commit", "-m", "subj", env = firstEnv)
        val src = headSha(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-C", src, "--reset-author")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        // Author reset to current identity + committer date.
        assertEquals(
            "Test User <test@example.com> 1700000000 +0000\n",
            probe.run(listOf("log", "-1", "--format=%an <%ae> %ad", "--date=raw"), tmp, PINNED).stdoutUtf8(),
        )
        assertEquals("subj\n\n", probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8())
    }

    @Test fun amendNoEditKeepsMessage(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "--amend", "--no-edit")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals("init\n", probe.run(listOf("log", "-1", "--format=%s"), tmp, PINNED).stdoutUtf8())
        // Both a and b are in the amended commit.
        assertEquals("b\n", probe.run(listOf("show", "HEAD:b"), tmp, PINNED).stdoutUtf8())
    }

    @Test fun allowEmptyMessage(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "--allow-empty-message", "-m", "")
        assertEquals(0, cm.rc, cm.stderr)
        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals("\n", probe.run(listOf("log", "-1", "--format=%B"), tmp, PINNED).stdoutUtf8())
    }

    @Test fun emptyMessageAborts() {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "-m", "")
        assertEquals(1, cm.rc)
        assertTrue("empty commit message" in cm.stderr, cm.stderr)
    }

    @Test fun dashAWithPathspecRejected() {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/a", "a2\n") }
        val cm = runGit(fs, "/work", "commit", "-a", "-m", "x", "--", "a")
        assertEquals(128, cm.rc)
        assertTrue("does not make sense" in cm.stderr, cm.stderr)
    }

    @Test fun resetAuthorWithoutAmendOrReuseRejected() {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/b", "b\n") }
        runGit(fs, "/work", "add", "b")
        val cm = runGit(fs, "/work", "commit", "--reset-author", "-m", "x")
        assertEquals(128, cm.rc)
        assertTrue("--reset-author can be used only with" in cm.stderr, cm.stderr)
    }

    @Test fun mAndFmutuallyExclusive() {
        val fs = InMemoryFs()
        freshWithInitialCommit(fs)
        runBlocking { write(fs, "/work/MSG", "hi\n") }
        val cm = runGit(fs, "/work", "commit", "-m", "x", "-F", "MSG")
        assertEquals(128, cm.rc)
        assertTrue("cannot be used together" in cm.stderr, cm.stderr)
    }
}
