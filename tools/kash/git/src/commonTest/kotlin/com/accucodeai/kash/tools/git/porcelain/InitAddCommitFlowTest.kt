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
import kotlin.test.assertTrue

/**
 * Drives the full init → add → status → commit → log loop against an
 * in-memory VFS. No real-git probe needed — these check our own
 * behavior end-to-end. Real-git differential tests live in
 * `jvmTest/.../porcelain/PorcelainDifferentialTest.kt`.
 */
class InitAddCommitFlowTest {
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

    @Test fun fullCommitFlow() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")

        // init
        assertEquals(0, run(fs, "/r", "init").rc)

        // create a file
        runBlocking { fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray()) }

        // status → untracked
        val st1 = run(fs, "/r", "status", "--porcelain")
        assertEquals("?? a.txt\n", st1.stdout)

        // add → status now shows staged
        assertEquals(0, run(fs, "/r", "add", "a.txt").rc)
        val st2 = run(fs, "/r", "status", "--porcelain")
        assertEquals("A  a.txt\n", st2.stdout)

        // ls-files --stage
        val ls = run(fs, "/r", "ls-files", "--stage")
        assertTrue(
            ls.stdout.matches(Regex("100644 [0-9a-f]{40} 0\ta\\.txt\n")),
            "ls-files output: ${ls.stdout}",
        )

        // commit
        val cm = run(fs, "/r", "commit", "-m", "first commit")
        assertEquals(0, cm.rc, "stderr: ${cm.stderr}")
        assertTrue(cm.stdout.startsWith("[main "), "stdout: ${cm.stdout}")
        assertTrue(cm.stdout.contains("] first commit\n"))

        // status → clean
        val st3 = run(fs, "/r", "status", "--porcelain")
        assertEquals("", st3.stdout)

        // log --oneline
        val lg = run(fs, "/r", "log", "--oneline")
        assertEquals(0, lg.rc)
        assertTrue(lg.stdout.endsWith(" first commit\n"))

        // rev-parse HEAD returns a sha
        val rp = run(fs, "/r", "rev-parse", "HEAD")
        assertEquals(0, rp.rc)
        assertEquals(41, rp.stdout.length)
        assertTrue(rp.stdout.matches(Regex("[0-9a-f]{40}\n")))

        // show HEAD:a.txt
        val sh = run(fs, "/r", "show", "HEAD:a.txt")
        assertEquals(0, sh.rc)
        assertEquals("hello\n", sh.stdout)
    }

    @Test fun modifiedAndDeletedAfterCommit() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking {
            fs.writeBytes("/r/a", "one\n".encodeToByteArray())
            fs.writeBytes("/r/b", "two\n".encodeToByteArray())
        }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "init")

        // modify a, delete b
        runBlocking { fs.writeBytes("/r/a", "ONE\n".encodeToByteArray()) }
        fs.remove("/r/b")

        val st = run(fs, "/r", "status", "--porcelain")
        // a is modified in worktree (Y=M), b is deleted in worktree (Y=D)
        assertTrue(" M a\n" in st.stdout, "stdout: ${st.stdout}")
        assertTrue(" D b\n" in st.stdout, "stdout: ${st.stdout}")
    }

    @Test fun emptyCommitRejectedByDefault() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "x".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "init")

        val cm = run(fs, "/r", "commit", "-m", "again")
        assertEquals(1, cm.rc)
        assertTrue(cm.stderr.contains("nothing to commit"), "stderr: ${cm.stderr}")
    }

    @Test fun secondCommitChainsParent() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "first")
        val sha1 = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "second")
        val sha2 = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

        val lg = run(fs, "/r", "log", "--pretty=%s")
        assertEquals("second\nfirst\n", lg.stdout)
        assertTrue(sha1 != sha2)
    }

    @Test fun unknownRevReportsFatal() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        val r = run(fs, "/r", "rev-parse", "--verify", "no-such-branch")
        assertEquals(128, r.rc)
        assertTrue(r.stderr.contains("ambiguous"), "stderr: ${r.stderr}")
    }
}
