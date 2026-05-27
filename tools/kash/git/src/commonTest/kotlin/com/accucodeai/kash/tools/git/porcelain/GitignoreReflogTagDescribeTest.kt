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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for v1.3 additions: .gitignore parsing, reflog, annotated tags,
 * git describe.
 */
class GitignoreReflogTagDescribeTest {
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

    private fun setupRepo(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
    }

    // ---- gitignore ----

    @Test fun gitignoreHidesUntrackedFiles() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking {
            fs.writeBytes("/r/.gitignore", "*.log\nbuild/\n".encodeToByteArray())
            fs.writeBytes("/r/keep.txt", "k\n".encodeToByteArray())
            fs.writeBytes("/r/skip.log", "junk\n".encodeToByteArray())
            fs.mkdirs("/r/build")
            fs.writeBytes("/r/build/out", "x\n".encodeToByteArray())
        }
        val st = run(fs, "/r", "status", "--porcelain")
        assertTrue("?? .gitignore" in st.stdout, st.stdout)
        assertTrue("?? keep.txt" in st.stdout, st.stdout)
        assertTrue("skip.log" !in st.stdout, "ignored *.log should not appear: ${st.stdout}")
        assertTrue("build" !in st.stdout, "ignored build/ should not appear: ${st.stdout}")
    }

    @Test fun addDashAHonorsGitignore() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking {
            fs.writeBytes("/r/.gitignore", "*.tmp\n".encodeToByteArray())
            fs.writeBytes("/r/keep.txt", "k\n".encodeToByteArray())
            fs.writeBytes("/r/skip.tmp", "junk\n".encodeToByteArray())
        }
        assertEquals(0, run(fs, "/r", "add", "-A").rc)
        val ls = run(fs, "/r", "ls-files").stdout
        assertTrue("keep.txt" in ls, ls)
        assertTrue("skip.tmp" !in ls, "tmp should be ignored by add -A: $ls")
        assertTrue(".gitignore" in ls, ".gitignore itself should be added: $ls")
    }

    @Test fun gitignoreNegationReIncludes() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking {
            fs.writeBytes("/r/.gitignore", "*.log\n!important.log\n".encodeToByteArray())
            fs.writeBytes("/r/other.log", "o\n".encodeToByteArray())
            fs.writeBytes("/r/important.log", "i\n".encodeToByteArray())
        }
        val st = run(fs, "/r", "status", "--porcelain").stdout
        assertTrue("important.log" in st, "negation should re-include: $st")
        assertTrue("other.log" !in st, "other.log still ignored: $st")
    }

    @Test fun trackedFilesIgnoredByGitignoreStillSurface() {
        // Real git: if a file is already tracked, .gitignore can't hide it.
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/tracked.log", "x\n".encodeToByteArray()) }
        run(fs, "/r", "add", "tracked.log")
        run(fs, "/r", "commit", "-m", "add tracked")
        runBlocking {
            fs.writeBytes("/r/.gitignore", "*.log\n".encodeToByteArray())
            fs.writeBytes("/r/tracked.log", "modified\n".encodeToByteArray())
        }
        val st = run(fs, "/r", "status", "--porcelain").stdout
        assertTrue("tracked.log" in st, "tracked .log file must still show: $st")
    }

    // ---- reflog ----

    @Test fun reflogRecordsCommit() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        val log = run(fs, "/r", "reflog").stdout
        assertTrue("commit (initial)" in log, "expected initial commit entry:\n$log")
    }

    @Test fun reflogRecordsAmend() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "commit", "--amend", "-m", "renamed")
        val log = run(fs, "/r", "reflog", "HEAD").stdout
        assertTrue("commit (amend)" in log, "expected amend entry:\n$log")
    }

    @Test fun headAtNResolvesViaReflog() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        val first = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        runBlocking { fs.writeBytes("/r/a.txt", "b\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "two")
        val cur = run(fs, "/r", "rev-parse", "HEAD@{0}").stdout.trim()
        val prev = run(fs, "/r", "rev-parse", "HEAD@{1}").stdout.trim()
        assertNotEquals(cur, prev, "HEAD@{0} should differ from HEAD@{1}")
        assertEquals(first, prev, "HEAD@{1} should be the first commit")
    }

    @Test fun reflogRecordsCheckout() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "switch", "-c", "feature")
        val log = run(fs, "/r", "reflog", "HEAD").stdout
        assertTrue("checkout: moving from main to feature" in log, "expected checkout entry:\n$log")
    }

    // ---- annotated tags ----

    @Test fun annotatedTagWritesTagObject() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        val tagRes = run(fs, "/r", "tag", "-a", "v1.0", "-m", "first release")
        assertEquals(0, tagRes.rc, tagRes.stderr)
        // The ref points at the tag object, whose -t is "tag".
        val tagType = run(fs, "/r", "cat-file", "-t", "v1.0").stdout.trim()
        assertEquals("tag", tagType, "annotated tag ref should resolve to a tag object")
        // Tag's payload contains the message.
        val payload = run(fs, "/r", "cat-file", "-p", "v1.0").stdout
        assertTrue("first release" in payload, "payload:\n$payload")
    }

    @Test fun annotatedTagPeelsForRevSpec() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        val head = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        run(fs, "/r", "tag", "-a", "v1", "-m", "msg")
        // `git rev-parse v1` should yield the *commit* sha, not the tag sha.
        val resolved = run(fs, "/r", "rev-parse", "v1").stdout.trim()
        assertEquals(head, resolved, "annotated tag should peel to the commit on resolve")
    }

    @Test fun lightweightTagStillWorks() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "tag", "light")
        val type = run(fs, "/r", "cat-file", "-t", "light").stdout.trim()
        assertEquals("commit", type, "lightweight tag ref points straight at a commit")
    }

    // ---- describe ----

    @Test fun describeOnExactTag() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "tag", "v1.0")
        assertEquals("v1.0\n", run(fs, "/r", "describe").stdout)
    }

    @Test fun describeWithDistance() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "tag", "v1.0")
        runBlocking { fs.writeBytes("/r/a.txt", "2\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "two")
        runBlocking { fs.writeBytes("/r/a.txt", "3\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "three")
        val out = run(fs, "/r", "describe").stdout
        assertTrue(out.startsWith("v1.0-2-g"), "expected v1.0-2-g... got: $out")
    }

    @Test fun describeAlwaysFallback() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "a\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        // No tags. --always must emit short sha.
        val out = run(fs, "/r", "describe", "--always").stdout.trim()
        assertEquals(7, out.length, "expected 7-char abbrev sha, got '$out'")
        // Without --always: failure.
        val noTag = run(fs, "/r", "describe")
        assertNotEquals(0, noTag.rc)
    }

    @Test fun describeWithAnnotatedTag() {
        val fs = InMemoryFs()
        setupRepo(fs)
        runBlocking { fs.writeBytes("/r/a.txt", "1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "one")
        run(fs, "/r", "tag", "-a", "v2.0", "-m", "rel")
        // One more commit after the annotated tag.
        runBlocking { fs.writeBytes("/r/a.txt", "2\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "two")
        val out = run(fs, "/r", "describe").stdout
        assertTrue(out.startsWith("v2.0-1-g"), "expected v2.0-1-g..., got: $out")
    }
}
