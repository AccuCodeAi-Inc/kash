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
 * Differential tests vs `/usr/bin/git` for the new plumbing subcommands.
 * The hard contract: kash's `write-tree`/`commit-tree` shas are
 * byte-identical to real git's for identical inputs (identity + dates are
 * pinned via env), and `diff-tree` raw/name-status output matches.
 */
class PlumbingTreeDifferentialTest {
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
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer().also { it.write(stdin.encodeToByteArray()) }
        val ctx =
            bareCommandContext(
                fs = fs,
                env = PINNED.toMutableMap(),
                cwd = cwd,
                stdin = inBuf.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    @Test fun writeTreeShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        // kash side
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            fs.writeBytes("/work/a.txt", "hello\n".encodeToByteArray())
            fs.mkdirs("/work/sub")
            fs.writeBytes("/work/sub/b.txt", "deep\n".encodeToByteArray())
        }
        runGit(fs, "/work", "add", "-A")
        val kashTree = runGit(fs, "/work", "write-tree").stdout.trim()

        // real git side: same content
        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("hello\n")
        File(tmp, "sub").mkdirs()
        File(tmp, "sub/b.txt").writeText("deep\n")
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        val realTree = probe.run(listOf("write-tree"), tmp, env = PINNED).stdoutUtf8().trim()

        assertEquals(realTree, kashTree, "write-tree sha mismatch")
    }

    @Test fun commitTreeShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/a.txt", "hello\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "-A")
        val kashTree = runGit(fs, "/work", "write-tree").stdout.trim()
        val kashCommit =
            runGit(fs, "/work", "commit-tree", kashTree, "-m", "hello plumbing").stdout.trim()

        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("hello\n")
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        val realTree = probe.run(listOf("write-tree"), tmp, env = PINNED).stdoutUtf8().trim()
        assertEquals(realTree, kashTree)
        val realCommit =
            probe
                .run(listOf("commit-tree", realTree, "-m", "hello plumbing"), tmp, env = PINNED)
                .stdoutUtf8()
                .trim()
        assertEquals(realCommit, kashCommit, "commit-tree sha mismatch")
    }

    @Test fun commitTreeWithParentShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/a.txt", "v1\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "-A")
        val t1 = runGit(fs, "/work", "write-tree").stdout.trim()
        val c1 = runGit(fs, "/work", "commit-tree", t1, "-m", "one").stdout.trim()
        runBlocking { fs.writeBytes("/work/a.txt", "v2\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "-A")
        val t2 = runGit(fs, "/work", "write-tree").stdout.trim()
        val c2 = runGit(fs, "/work", "commit-tree", t2, "-p", c1, "-m", "two").stdout.trim()

        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("v1\n")
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        val rt1 = probe.run(listOf("write-tree"), tmp, env = PINNED).stdoutUtf8().trim()
        val rc1 = probe.run(listOf("commit-tree", rt1, "-m", "one"), tmp, env = PINNED).stdoutUtf8().trim()
        File(tmp, "a.txt").writeText("v2\n")
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        val rt2 = probe.run(listOf("write-tree"), tmp, env = PINNED).stdoutUtf8().trim()
        val rc2 =
            probe
                .run(listOf("commit-tree", rt2, "-p", rc1, "-m", "two"), tmp, env = PINNED)
                .stdoutUtf8()
                .trim()

        assertEquals(rc1, c1)
        assertEquals(rc2, c2, "commit-tree-with-parent sha mismatch")
    }

    @Test fun diffTreeRawMatchesRealGit(
        @TempDir tmp: File,
    ) {
        // Build the same two-commit history in real git, then copy to a
        // VFS so kash and real git diff the identical objects.
        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("hello\n")
        File(tmp, "sub").mkdirs()
        File(tmp, "sub/b.txt").writeText("x\n")
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        probe.run(listOf("commit", "-q", "-m", "first"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("HELLO\n")
        File(tmp, "c.txt").writeText("new\n")
        probe.run(listOf("rm", "-q", "sub/b.txt"), tmp, env = PINNED)
        probe.run(listOf("add", "-A"), tmp, env = PINNED)
        probe.run(listOf("commit", "-q", "-m", "second"), tmp, env = PINNED)

        val fs = InMemoryFs()
        runBlocking { copyInto(tmp, fs, "/work") }

        // Default (non-recursive) raw: subtree collapses to a single entry.
        val kashDefault = runGit(fs, "/work", "diff-tree", "--no-commit-id", "HEAD").stdout
        val realDefault =
            probe.run(listOf("diff-tree", "--no-commit-id", "HEAD"), tmp, env = PINNED).stdoutUtf8()
        assertEquals(realDefault, kashDefault, "diff-tree default raw mismatch")

        // -r recursive raw.
        val kashR = runGit(fs, "/work", "diff-tree", "-r", "--no-commit-id", "HEAD").stdout
        val realR =
            probe.run(listOf("diff-tree", "-r", "--no-commit-id", "HEAD"), tmp, env = PINNED).stdoutUtf8()
        assertEquals(realR, kashR, "diff-tree -r raw mismatch")

        // --name-status.
        val kashNs =
            runGit(fs, "/work", "diff-tree", "-r", "--no-commit-id", "--name-status", "HEAD").stdout
        val realNs =
            probe
                .run(listOf("diff-tree", "-r", "--no-commit-id", "--name-status", "HEAD"), tmp, env = PINNED)
                .stdoutUtf8()
        assertEquals(realNs, kashNs, "diff-tree --name-status mismatch")
    }

    @Test fun updateIndexThenWriteTreeMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/f.txt", "data\n".encodeToByteArray()) }
        runGit(fs, "/work", "update-index", "--add", "f.txt")
        val kashTree = runGit(fs, "/work", "write-tree").stdout.trim()

        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "f.txt").writeText("data\n")
        probe.run(listOf("update-index", "--add", "f.txt"), tmp, env = PINNED)
        val realTree = probe.run(listOf("write-tree"), tmp, env = PINNED).stdoutUtf8().trim()

        assertEquals(realTree, kashTree, "update-index --add → write-tree mismatch")
    }

    @Test fun mktreeShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        // Hash a blob with real git so both sides reference an identical sha.
        probe.run(listOf("init", "-q", "-b", "main"), tmp, env = PINNED)
        File(tmp, "a.txt").writeText("hello\n")
        val blobSha =
            probe.run(listOf("hash-object", "-w", "a.txt"), tmp, env = PINNED).stdoutUtf8().trim()
        val input = "100644 blob $blobSha\ta.txt\n"
        val realTree = probe.run(listOf("mktree"), tmp, env = PINNED, stdin = input.toByteArray()).stdoutUtf8().trim()

        // kash side: write the same blob into a VFS repo, then mktree.
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/a.txt", "hello\n".encodeToByteArray()) }
        val kashBlob = runGit(fs, "/work", "hash-object", "-w", "a.txt").stdout.trim()
        assertEquals(blobSha, kashBlob)
        val kashTree = runGit(fs, "/work", "mktree", stdin = input).stdout.trim()

        assertEquals(realTree, kashTree, "mktree sha mismatch")
    }

    private suspend fun copyInto(
        src: File,
        fs: InMemoryFs,
        dest: String,
    ) {
        fs.mkdirs(dest)
        for (child in src.listFiles() ?: emptyArray()) {
            val full = "$dest/${child.name}"
            if (child.isDirectory) {
                copyInto(child, fs, full)
            } else {
                fs.writeBytes(full, child.readBytes())
            }
        }
    }
}
