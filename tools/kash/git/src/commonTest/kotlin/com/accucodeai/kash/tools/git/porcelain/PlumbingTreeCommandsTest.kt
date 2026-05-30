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
 * Unit tests for the new plumbing subcommands (write-tree, commit-tree,
 * diff-tree, update-index, mktree) against an in-memory VFS. Real-git
 * sha-parity is asserted in the jvmTest differential suite.
 */
class PlumbingTreeCommandsTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
        stdin: String = "",
        env: Map<String, String> =
            mapOf(
                "GIT_AUTHOR_DATE" to "1700000000 +0000",
                "GIT_COMMITTER_DATE" to "1700000000 +0000",
                "GIT_AUTHOR_NAME" to "Test User",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_COMMITTER_NAME" to "Test User",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
            ),
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
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    @Test fun writeTreeMatchesCommitTree() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
            fs.mkdirs("/r/sub")
            fs.writeBytes("/r/sub/b.txt", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")

            val wt = run(fs, "/r", "write-tree")
            assertEquals(0, wt.rc, wt.stderr)
            val treeSha = wt.stdout.trim()
            assertTrue(treeSha.matches(Regex("[0-9a-f]{40}")), "write-tree sha: $treeSha")

            // The tree should list a.txt and sub/.
            val ls = run(fs, "/r", "ls-tree", treeSha)
            assertTrue("a.txt" in ls.stdout, ls.stdout)
            assertTrue("sub" in ls.stdout, ls.stdout)
        }

    @Test fun commitTreeProducesCommitWeCanReadBack() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
            run(fs, "/r", "add", "a.txt")
            val treeSha = run(fs, "/r", "write-tree").stdout.trim()

            val ct = run(fs, "/r", "commit-tree", treeSha, "-m", "my message")
            assertEquals(0, ct.rc, ct.stderr)
            val commitSha = ct.stdout.trim()
            assertTrue(commitSha.matches(Regex("[0-9a-f]{40}")))

            val cat = run(fs, "/r", "cat-file", "-p", commitSha)
            assertTrue(cat.stdout.startsWith("tree $treeSha\n"), cat.stdout)
            assertTrue("author Test User <test@example.com> 1700000000 +0000" in cat.stdout, cat.stdout)
            assertTrue(cat.stdout.endsWith("\nmy message\n"), cat.stdout)
        }

    @Test fun commitTreeReadsMessageFromStdin() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "a.txt")
            val treeSha = run(fs, "/r", "write-tree").stdout.trim()

            val ct = run(fs, "/r", "commit-tree", treeSha, stdin = "from stdin\n")
            assertEquals(0, ct.rc, ct.stderr)
            val cat = run(fs, "/r", "cat-file", "-p", ct.stdout.trim())
            assertTrue(cat.stdout.endsWith("\nfrom stdin\n"), cat.stdout)
        }

    @Test fun commitTreeChainsParents() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "a.txt")
            val tree = run(fs, "/r", "write-tree").stdout.trim()
            val c1 = run(fs, "/r", "commit-tree", tree, "-m", "one").stdout.trim()
            val c2 = run(fs, "/r", "commit-tree", tree, "-p", c1, "-m", "two").stdout.trim()

            val cat = run(fs, "/r", "cat-file", "-p", c2)
            assertTrue("parent $c1" in cat.stdout, cat.stdout)
        }

    @Test fun updateIndexCacheinfoCommaForm() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/z.txt", "z\n".encodeToByteArray())
            val blobSha = run(fs, "/r", "hash-object", "-w", "z.txt").stdout.trim()

            val ui = run(fs, "/r", "update-index", "--add", "--cacheinfo", "100644,$blobSha,g.txt")
            assertEquals(0, ui.rc, ui.stderr)
            val ls = run(fs, "/r", "ls-files", "--stage")
            assertTrue(ls.stdout.contains("100644 $blobSha 0\tg.txt"), ls.stdout)
        }

    @Test fun updateIndexCacheinfoThreeArgForm() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/z.txt", "z\n".encodeToByteArray())
            val blobSha = run(fs, "/r", "hash-object", "-w", "z.txt").stdout.trim()

            val ui = run(fs, "/r", "update-index", "--add", "--cacheinfo", "100644", blobSha, "h.txt")
            assertEquals(0, ui.rc, ui.stderr)
            val ls = run(fs, "/r", "ls-files", "--stage")
            assertTrue(ls.stdout.contains("100644 $blobSha 0\th.txt"), ls.stdout)
        }

    @Test fun updateIndexAddAndRemove() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/f.txt", "one\n".encodeToByteArray())

            val add = run(fs, "/r", "update-index", "--add", "f.txt")
            assertEquals(0, add.rc, add.stderr)
            assertTrue("f.txt" in run(fs, "/r", "ls-files").stdout)

            // Remove with the file gone from disk.
            fs.remove("/r/f.txt")
            val rm = run(fs, "/r", "update-index", "--remove", "f.txt")
            assertEquals(0, rm.rc, rm.stderr)
            assertEquals("", run(fs, "/r", "ls-files").stdout)
        }

    @Test fun updateIndexAddMissingFileErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            val r = run(fs, "/r", "update-index", "--add", "nope.txt")
            assertEquals(128, r.rc)
            assertTrue("does not exist" in r.stderr, r.stderr)
        }

    @Test fun diffTreeRawAndNameStatus() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "first")
            fs.writeBytes("/r/a.txt", "HELLO\n".encodeToByteArray())
            fs.writeBytes("/r/c.txt", "new\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "second")

            val raw = run(fs, "/r", "diff-tree", "-r", "HEAD")
            assertEquals(0, raw.rc, raw.stderr)
            // First line is the commit id.
            val lines = raw.stdout.trimEnd('\n').split('\n')
            assertTrue(lines[0].matches(Regex("[0-9a-f]{40}")), raw.stdout)
            assertTrue(lines.any { it.startsWith(":100644 100644 ") && it.endsWith("M\ta.txt") }, raw.stdout)
            assertTrue(lines.any { it.contains("A\tc.txt") }, raw.stdout)

            val ns = run(fs, "/r", "diff-tree", "-r", "--no-commit-id", "--name-status", "HEAD")
            assertEquals("M\ta.txt\nA\tc.txt\n", ns.stdout)

            val no = run(fs, "/r", "diff-tree", "-r", "--no-commit-id", "--name-only", "HEAD")
            assertEquals("a.txt\nc.txt\n", no.stdout)
        }

    @Test fun diffTreePatchMode() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "first")
            fs.writeBytes("/r/a.txt", "HELLO\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "second")

            val p = run(fs, "/r", "diff-tree", "-p", "--no-commit-id", "HEAD")
            assertEquals(0, p.rc, p.stderr)
            assertTrue("diff --git a/a.txt b/a.txt" in p.stdout, p.stdout)
            assertTrue("-hello" in p.stdout && "+HELLO" in p.stdout, p.stdout)
        }

    @Test fun diffTreeRootCommitNeedsRootFlag() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hi\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "root")

            // Without --root: empty output.
            val noRoot = run(fs, "/r", "diff-tree", "-r", "HEAD")
            assertEquals("", noRoot.stdout, noRoot.stdout)

            // With --root: commit id + added file.
            val withRoot = run(fs, "/r", "diff-tree", "-r", "--root", "HEAD")
            assertTrue("A\ta.txt" in withRoot.stdout, withRoot.stdout)
        }

    @Test fun mktreeRoundTrips() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
            run(fs, "/r", "add", "a.txt")
            val tree = run(fs, "/r", "write-tree").stdout.trim()
            // Feed ls-tree output back into mktree; sha must match.
            val lsTree = run(fs, "/r", "ls-tree", tree).stdout
            val mk = run(fs, "/r", "mktree", stdin = lsTree)
            assertEquals(0, mk.rc, mk.stderr)
            assertEquals(tree, mk.stdout.trim())
        }
}
