package com.accucodeai.kash.tools.git.seed

import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.SyntheticCommit
import com.accucodeai.kash.api.git.TreeChange
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end check: a synthetic seed produces a `.git/` that real git
 * accepts. We extract the VFS to a temp dir and run `git fsck` (no
 * tolerance for malformed objects), then verify `git log` returns the
 * commits we asked for in the right order.
 */
class SyntheticMaterializerTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    private val identity = GitIdentity("Test User", "test@example.com")

    @Test fun singleSnapshotPassesFsck(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        val layout = RepoLayout("/home/user/repo")
        runTest {
            SyntheticMaterializer(layout, fs, identity).materialize(
                GitRepoSeed.Synthetic(
                    workTree =
                        mapOf(
                            "README.md" to "# hello\n".encodeToByteArray(),
                            "src/main.kt" to "fun main() {}\n".encodeToByteArray(),
                        ),
                    executable = setOf("run.sh"),
                ),
            )
        }

        extractToDisk(fs, layout.workTree, tmp)
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, "fsck failed:\n${fsck.stderrUtf8()}\n${fsck.stdoutUtf8()}")
        val log = probe.run(listOf("log", "--oneline"), tmp)
        assertEquals(0, log.exitCode, log.stderrUtf8())
        assertTrue(log.stdoutUtf8().contains("Initial commit"), "log output: ${log.stdoutUtf8()}")

        // README.md content readable.
        val readme = probe.run(listOf("show", "HEAD:README.md"), tmp)
        assertEquals("# hello\n", readme.stdoutUtf8())
    }

    @Test fun seededRepoStatusIsCleanOnUntouchedWorkTree(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        val layout = RepoLayout("/home/user/repo")
        runTest {
            SyntheticMaterializer(layout, fs, identity).materialize(
                GitRepoSeed.Synthetic(
                    workTree =
                        mapOf(
                            "README.md" to "# hello\n".encodeToByteArray(),
                            "src/config.json" to "{\n  \"name\": \"test\"\n}".encodeToByteArray(),
                        ),
                ),
            )
        }

        // The materializer must write a `.git/index` matching HEAD — otherwise
        // every tracked file reads as staged-for-deletion (`D `).
        assertTrue(fs.exists("${layout.gitDir}/index"), "seed must write .git/index")

        extractToDisk(fs, layout.workTree, tmp)
        // Real git, on an untouched checkout, reports a clean tree.
        val status = probe.run(listOf("status", "--porcelain"), tmp)
        assertEquals(0, status.exitCode, status.stderrUtf8())
        assertEquals("", status.stdoutUtf8(), "expected clean status, got:\n${status.stdoutUtf8()}")
        // And nothing staged: the index equals HEAD.
        val diffCached = probe.run(listOf("diff", "--cached", "--name-status"), tmp)
        assertEquals("", diffCached.stdoutUtf8(), "expected nothing staged, got:\n${diffCached.stdoutUtf8()}")
    }

    @Test fun linearHistoryProducesExpectedLog(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        val layout = RepoLayout("/home/user/repo")
        runTest {
            SyntheticMaterializer(layout, fs, identity).materialize(
                GitRepoSeed.Synthetic(
                    workTree = mapOf("a.txt" to "v3\n".encodeToByteArray()),
                    history =
                        listOf(
                            SyntheticCommit(
                                tag = "c1",
                                message = "first",
                                changes = listOf(TreeChange.Write("a.txt", "v1\n".encodeToByteArray())),
                            ),
                            SyntheticCommit(
                                tag = "c2",
                                parents = listOf("c1"),
                                message = "second",
                                changes = listOf(TreeChange.Write("a.txt", "v2\n".encodeToByteArray())),
                            ),
                            SyntheticCommit(
                                tag = "c3",
                                parents = listOf("c2"),
                                message = "third",
                                changes = listOf(TreeChange.Write("a.txt", "v3\n".encodeToByteArray())),
                            ),
                        ),
                ),
            )
        }

        extractToDisk(fs, layout.workTree, tmp)
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, "fsck failed:\n${fsck.stderrUtf8()}")
        val log = probe.run(listOf("log", "--pretty=%s"), tmp)
        assertEquals("third\nsecond\nfirst\n", log.stdoutUtf8())
        val show = probe.run(listOf("show", "HEAD:a.txt"), tmp)
        assertEquals("v3\n", show.stdoutUtf8())
    }

    @Test fun branchedHistoryWithExtraRefs(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        val layout = RepoLayout("/home/user/repo")
        runTest {
            SyntheticMaterializer(layout, fs, identity).materialize(
                GitRepoSeed.Synthetic(
                    workTree = mapOf("a.txt" to "main-edit\n".encodeToByteArray()),
                    history =
                        listOf(
                            SyntheticCommit(
                                tag = "base",
                                message = "base",
                                changes = listOf(TreeChange.Write("a.txt", "common\n".encodeToByteArray())),
                            ),
                            SyntheticCommit(
                                tag = "feature-tip",
                                parents = listOf("base"),
                                message = "feature edit",
                                changes = listOf(TreeChange.Write("a.txt", "feature-edit\n".encodeToByteArray())),
                            ),
                            SyntheticCommit(
                                tag = "main-tip",
                                parents = listOf("base"),
                                message = "main edit",
                                changes = listOf(TreeChange.Write("a.txt", "main-edit\n".encodeToByteArray())),
                            ),
                        ),
                    extraRefs =
                        mapOf(
                            "refs/heads/main" to "main-tip",
                            "refs/heads/feature" to "feature-tip",
                        ),
                    head = "refs/heads/main",
                ),
            )
        }

        extractToDisk(fs, layout.workTree, tmp)
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())

        val branches = probe.run(listOf("branch", "--list"), tmp).stdoutUtf8()
        assertTrue(branches.contains("feature"), "branches: $branches")
        assertTrue(branches.contains("main"), "branches: $branches")

        assertEquals("feature-edit\n", probe.run(listOf("show", "feature:a.txt"), tmp).stdoutUtf8())
        assertEquals("main-edit\n", probe.run(listOf("show", "main:a.txt"), tmp).stdoutUtf8())
    }

    @Test fun headMismatchDetected() {
        val fs = InMemoryFs()
        val layout = RepoLayout("/home/user/repo")
        val ex =
            runCatching {
                runTest {
                    SyntheticMaterializer(layout, fs, identity).materialize(
                        GitRepoSeed.Synthetic(
                            workTree = mapOf("a" to "FINAL".encodeToByteArray()),
                            history =
                                listOf(
                                    SyntheticCommit(
                                        tag = "c1",
                                        message = "one",
                                        changes = listOf(TreeChange.Write("a", "DIFFERENT".encodeToByteArray())),
                                    ),
                                ),
                        ),
                    )
                }
            }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException, "expected IAE, got $ex")
        assertTrue(ex.message!!.contains("does not match workTree"))
    }

    /**
     * Copy the VFS at [vfsRoot] (and the `.git/` under it) into [dest].
     * After this, [dest] is a real on-disk git working directory the
     * `RealGitProbe` can drive.
     */
    private fun extractToDisk(
        fs: InMemoryFs,
        vfsRoot: String,
        dest: File,
    ) {
        runTest { copyDir(fs, vfsRoot, dest) }
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
