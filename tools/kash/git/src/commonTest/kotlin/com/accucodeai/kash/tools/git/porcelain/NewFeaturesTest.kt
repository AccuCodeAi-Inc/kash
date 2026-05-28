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
import kotlin.test.assertTrue

/**
 * Smoke tests for the v1.1 additions: revspec ancestor suffixes,
 * abbreviated SHA, `log -p / --stat / <path>`, `diff --stat / -U`,
 * `rev-list`, `config`, `remote`.
 */
class NewFeaturesTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        env: MutableMap<String, String> = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env,
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private suspend fun setupRepoWithCommits(fs: InMemoryFs): String {
        fs.mkdirs("/r")
        run(fs, "/r", args = arrayOf("init"))
        fs.writeBytes("/r/a.txt", "one\n".encodeToByteArray())
        run(fs, "/r", args = arrayOf("add", "-A"))
        run(fs, "/r", args = arrayOf("commit", "-m", "c1"))
        fs.writeBytes("/r/a.txt", "one\ntwo\n".encodeToByteArray())
        run(fs, "/r", args = arrayOf("add", "-A"))
        run(fs, "/r", args = arrayOf("commit", "-m", "c2"))
        fs.writeBytes("/r/b.txt", "B\n".encodeToByteArray())
        run(fs, "/r", args = arrayOf("add", "-A"))
        run(fs, "/r", args = arrayOf("commit", "-m", "c3"))
        return run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
    }

    // ---- revspec ----

    @Test fun headParentAndTildeResolve() =
        runTest {
            val fs = InMemoryFs()
            val head = setupRepoWithCommits(fs)
            val parent = run(fs, "/r", args = arrayOf("rev-parse", "HEAD^")).stdout.trim()
            val grand = run(fs, "/r", args = arrayOf("rev-parse", "HEAD~2")).stdout.trim()
            val grandAlt = run(fs, "/r", args = arrayOf("rev-parse", "HEAD^^")).stdout.trim()
            assertTrue(parent.length == 40 && parent != head)
            assertEquals(grand, grandAlt)
            // log HEAD~1 should still emit two commits (the tip and its parent... no, ancestry from tip).
            val log =
                run(
                    fs,
                    "/r",
                    args = arrayOf("log", "--oneline", "HEAD~1"),
                ).stdout.lines().filter { it.isNotBlank() }
            assertEquals(2, log.size)
        }

    @Test fun abbreviatedShaResolves() =
        runTest {
            val fs = InMemoryFs()
            val head = setupRepoWithCommits(fs)
            val ab = head.substring(0, 8)
            val out = run(fs, "/r", args = arrayOf("rev-parse", ab)).stdout.trim()
            assertEquals(head, out)
        }

    // ---- log -p / --stat / <path> ----

    @Test fun logPatchEmitsUnifiedHunks() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out = run(fs, "/r", args = arrayOf("log", "-p", "--oneline")).stdout
            assertTrue("diff --git a/b.txt b/b.txt" in out, "no b.txt diff in log -p:\n$out")
            assertTrue("+two" in out || "@@ -1,1 +1,2 @@" in out, "no a.txt hunk in log -p:\n$out")
        }

    @Test fun logStatPrintsFileCounts() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out = run(fs, "/r", args = arrayOf("log", "--shortstat", "--oneline")).stdout
            assertTrue("1 file changed" in out, "no shortstat in log:\n$out")
        }

    @Test fun logPathFiltersToCommitsThatTouchedIt() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out = run(fs, "/r", args = arrayOf("log", "--oneline", "--", "b.txt")).stdout
            val lines = out.lines().filter { it.isNotBlank() }
            // Only c3 touched b.txt.
            assertEquals(1, lines.size, "expected 1 commit touching b.txt; got:\n$out")
            assertTrue(lines[0].endsWith("c3"))
        }

    // ---- diff --stat / -U ----

    @Test fun diffStatSummary() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            fs.writeBytes("/r/a.txt", "one\ntwo\nthree\n".encodeToByteArray())
            val out = run(fs, "/r", args = arrayOf("diff", "--stat")).stdout
            assertTrue("a.txt" in out, "no a.txt in diff --stat: $out")
            assertTrue("1 file changed" in out, "no summary: $out")
        }

    @Test fun diffWithSmallContext() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            // The default -U3 context, with only 2 lines of file content,
            // would include both lines. With -U0 there should be no context.
            fs.writeBytes("/r/a.txt", "one\ntwo\nthree\n".encodeToByteArray())
            val u0 = run(fs, "/r", args = arrayOf("diff", "-U0")).stdout
            assertTrue("+three" in u0, "no +three in -U0 diff: $u0")
            assertTrue(" one" !in u0, "should not include ' one' context in -U0 diff: $u0")
        }

    // ---- rev-list ----

    @Test fun revListEmitsAllAncestorsByDefault() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out = run(fs, "/r", args = arrayOf("rev-list", "HEAD")).stdout.lines().filter { it.isNotBlank() }
            assertEquals(3, out.size)
        }

    @Test fun revListCount() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out = run(fs, "/r", args = arrayOf("rev-list", "--count", "HEAD")).stdout.trim()
            assertEquals("3", out)
        }

    @Test fun revListRangeExcludesAncestry() =
        runTest {
            val fs = InMemoryFs()
            setupRepoWithCommits(fs)
            val out =
                run(fs, "/r", args = arrayOf("rev-list", "HEAD~2..HEAD"))
                    .stdout
                    .lines()
                    .filter { it.isNotBlank() }
            assertEquals(2, out.size, "HEAD~2..HEAD should yield 2 commits; got $out")
        }

    // ---- config ----

    @Test fun configSetAndGet() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            assertEquals(0, run(fs, "/r", args = arrayOf("config", "user.email", "foo@bar")).rc)
            val got = run(fs, "/r", args = arrayOf("config", "user.email"))
            assertEquals(0, got.rc)
            assertEquals("foo@bar\n", got.stdout)
        }

    @Test fun configGetMissingReturnsOne() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            val r = run(fs, "/r", args = arrayOf("config", "no.such.key"))
            assertEquals(1, r.rc)
        }

    @Test fun configUnsetRemovesKey() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            run(fs, "/r", args = arrayOf("config", "user.name", "alice"))
            assertEquals(0, run(fs, "/r", args = arrayOf("config", "--unset", "user.name")).rc)
            assertEquals(1, run(fs, "/r", args = arrayOf("config", "user.name")).rc)
        }

    @Test fun configListEmitsKnownKeys() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            run(fs, "/r", args = arrayOf("config", "user.email", "x@y"))
            val out = run(fs, "/r", args = arrayOf("config", "--list")).stdout
            assertTrue("user.email=x@y" in out, "missing in list:\n$out")
            assertTrue("core.bare=false" in out, "missing core defaults in list:\n$out")
        }

    // ---- remote ----

    @Test fun remoteAddListShowSetUrlRemove() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            assertEquals(0, run(fs, "/r", args = arrayOf("remote", "add", "origin", "https://example.com/r.git")).rc)
            val list = run(fs, "/r", args = arrayOf("remote"))
            assertEquals("origin\n", list.stdout)
            val v = run(fs, "/r", args = arrayOf("remote", "-v")).stdout
            assertTrue("origin\thttps://example.com/r.git (fetch)" in v, v)
            assertTrue("origin\thttps://example.com/r.git (push)" in v, v)
            val geturl = run(fs, "/r", args = arrayOf("remote", "get-url", "origin")).stdout.trim()
            assertEquals("https://example.com/r.git", geturl)
            assertEquals(0, run(fs, "/r", args = arrayOf("remote", "set-url", "origin", "https://x/y.git")).rc)
            assertEquals(
                "https://x/y.git",
                run(fs, "/r", args = arrayOf("remote", "get-url", "origin")).stdout.trim(),
            )
            assertEquals(0, run(fs, "/r", args = arrayOf("remote", "remove", "origin")).rc)
            assertEquals("", run(fs, "/r", args = arrayOf("remote")).stdout)
        }
}
