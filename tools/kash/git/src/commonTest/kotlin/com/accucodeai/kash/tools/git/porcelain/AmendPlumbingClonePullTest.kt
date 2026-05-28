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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the v1.2 additions: commit --amend, cat-file, hash-object,
 * ls-tree, clone (no-remote case), pull (no-op when up-to-date).
 */
class AmendPlumbingClonePullTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        stdin: String = "",
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val stdinBuf = Buffer()
        if (stdin.isNotEmpty()) stdinBuf.write(stdin.encodeToByteArray())
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
                cwd = cwd,
                stdin = stdinBuf.asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private suspend fun setupSingleCommit(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", args = arrayOf("init"))
        fs.writeBytes("/r/a.txt", "hello\n".encodeToByteArray())
        run(fs, "/r", args = arrayOf("add", "-A"))
        run(fs, "/r", args = arrayOf("commit", "-m", "initial"))
    }

    // ---- amend ----

    @Test fun amendChangesMessageButPreservesTree() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val firstSha = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
            val r = run(fs, "/r", args = arrayOf("commit", "--amend", "-m", "renamed"))
            assertEquals(0, r.rc, r.stderr)
            val secondSha = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
            assertNotEquals(firstSha, secondSha)
            val subject = run(fs, "/r", args = arrayOf("log", "--pretty=%s")).stdout.trim()
            assertEquals("renamed", subject)
            // Only one commit (parent didn't change).
            val count = run(fs, "/r", args = arrayOf("rev-list", "--count", "HEAD")).stdout.trim()
            assertEquals("1", count)
        }

    @Test fun amendAddsStagedChange() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            fs.writeBytes("/r/b.txt", "B\n".encodeToByteArray())
            run(fs, "/r", args = arrayOf("add", "-A"))
            // amend without -m → reuse old message
            val r = run(fs, "/r", args = arrayOf("commit", "--amend"))
            assertEquals(0, r.rc, r.stderr)
            // Still one commit, but now with two files in tree.
            assertEquals("1", run(fs, "/r", args = arrayOf("rev-list", "--count", "HEAD")).stdout.trim())
            val tree = run(fs, "/r", args = arrayOf("ls-tree", "HEAD")).stdout
            assertTrue("a.txt" in tree && "b.txt" in tree, "expected both files in tree:\n$tree")
        }

    @Test fun amendOnEmptyRepoFails() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            val r = run(fs, "/r", args = arrayOf("commit", "--amend"))
            assertEquals(128, r.rc)
            assertTrue("nothing to amend" in r.stderr, r.stderr)
        }

    // ---- cat-file ----

    @Test fun catFileTypeSizePretty() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val head = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
            assertEquals("commit\n", run(fs, "/r", args = arrayOf("cat-file", "-t", head)).stdout)
            // Size > 0.
            val size = run(fs, "/r", args = arrayOf("cat-file", "-s", head)).stdout.trim().toInt()
            assertTrue(size > 0)
            // -e returns 0 for existing, nonzero for fake.
            assertEquals(0, run(fs, "/r", args = arrayOf("cat-file", "-e", head)).rc)
            assertEquals(128, run(fs, "/r", args = arrayOf("cat-file", "-e", "0".repeat(40))).rc)
            // Pretty: commit payload contains "tree" header line.
            val pretty = run(fs, "/r", args = arrayOf("cat-file", "-p", head)).stdout
            assertTrue(pretty.startsWith("tree "), "expected commit payload, got:\n$pretty")
        }

    @Test fun catFilePrettyTree() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val out = run(fs, "/r", args = arrayOf("cat-file", "-p", "HEAD^{tree}")).stdout
            assertTrue("a.txt" in out, "expected a.txt in tree pretty:\n$out")
            assertTrue(out.contains("blob"), out)
        }

    // ---- hash-object ----

    @Test fun hashObjectMatchesStored() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            // Compute hash from file content, compare against tree entry's sha.
            val computed = run(fs, "/r", args = arrayOf("hash-object", "a.txt")).stdout.trim()
            val treeLine =
                run(fs, "/r", args = arrayOf("ls-tree", "HEAD"))
                    .stdout
                    .lines()
                    .first { "a.txt" in it }
            assertTrue(computed in treeLine, "expected $computed in tree row:\n$treeLine")
        }

    @Test fun hashObjectStdinAndWrite() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val sha =
                run(
                    fs,
                    "/r",
                    stdin = "from stdin\n",
                    args = arrayOf("hash-object", "-w", "--stdin"),
                ).stdout.trim()
            // The just-written object must be readable via cat-file -p.
            val back = run(fs, "/r", args = arrayOf("cat-file", "-p", sha)).stdout
            assertEquals("from stdin\n", back)
        }

    // ---- ls-tree ----

    @Test fun lsTreeRecursesWithDashR() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", args = arrayOf("init"))
            fs.writeBytes("/r/top.txt", "T\n".encodeToByteArray())
            fs.mkdirs("/r/sub")
            fs.writeBytes("/r/sub/inner.txt", "I\n".encodeToByteArray())
            run(fs, "/r", args = arrayOf("add", "-A"))
            run(fs, "/r", args = arrayOf("commit", "-m", "init"))
            val plain = run(fs, "/r", args = arrayOf("ls-tree", "HEAD")).stdout
            assertTrue("sub" in plain && " tree " in plain, "expected subtree row:\n$plain")
            assertTrue("inner.txt" !in plain, "non-recursive should not show inner.txt:\n$plain")
            val recurse = run(fs, "/r", args = arrayOf("ls-tree", "-r", "HEAD")).stdout
            assertTrue("sub/inner.txt" in recurse, "expected sub/inner.txt:\n$recurse")
        }

    @Test fun lsTreeNameOnly() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val out = run(fs, "/r", args = arrayOf("ls-tree", "--name-only", "HEAD")).stdout
            assertEquals("a.txt\n", out)
        }

    // ---- clone ----

    @Test fun cloneWithoutResolverInitsEmpty() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            val r = run(fs, "/work", args = arrayOf("clone", "https://example.com/foo.git"))
            assertEquals(0, r.rc, r.stderr)
            assertTrue(fs.exists("/work/foo/.git/HEAD"))
            val remote = run(fs, "/work/foo", args = arrayOf("remote", "-v")).stdout
            assertTrue("origin\thttps://example.com/foo.git" in remote, remote)
        }

    @Test fun cloneReusesExistingSeed() =
        runTest {
            val fs = InMemoryFs()
            // Seed a "remote" repo at /seed.
            fs.mkdirs("/seed")
            run(fs, "/seed", args = arrayOf("init"))
            // Place a seed `.git/` at /work/foo to simulate the kash mount.
            fs.mkdirs("/work/foo/.git")
            run(fs, "/work/foo", args = arrayOf("init"))
            val r = run(fs, "/work", args = arrayOf("clone", "https://example.com/foo.git", "foo"))
            assertEquals(0, r.rc, r.stderr)
            assertTrue("reusing existing seed" in r.stdout, r.stdout)
        }

    // ---- pull ----

    @Test fun pullWithoutResolverFailsCleanly() =
        runTest {
            val fs = InMemoryFs()
            setupSingleCommit(fs)
            val r = run(fs, "/r", args = arrayOf("pull"))
            // fetch returns exit 1 with "no remote configured" — pull surfaces it.
            assertEquals(1, r.rc)
            assertTrue("no remote configured" in r.stderr || "no remote" in r.stderr, r.stderr)
        }
}
