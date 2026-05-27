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
 * After kash drives a `merge` / `rebase` / `cherry-pick` / `revert`
 * flow, real git must agree on the resulting graph: same HEAD sha,
 * same parent chain, same tree content. These cover the conflict
 * resolution paths too.
 */
class MergeRebaseDifferentialTest {
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

    @Test fun threeWayMergeMatchesRealGitView(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking {
            fs.writeBytes("/r/a", "common-base\n".encodeToByteArray())
            fs.writeBytes("/r/b", "untouched\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "-A")
        runGit(fs, "/r", "commit", "-m", "base")
        runGit(fs, "/r", "branch", "feature")

        // main edits b
        runBlocking { fs.writeBytes("/r/b", "main-edit\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "b")
        runGit(fs, "/r", "commit", "-m", "main edits b")

        // feature edits a
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/a", "feature-edit\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "feature edits a")

        // Merge feature into main via kash.
        runGit(fs, "/r", "switch", "main")
        val m = runGit(fs, "/r", "merge", "feature")
        assertEquals(0, m.first, m.third)

        val kashHead = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { copyDir(fs, "/r", tmp) }
        // Real git agrees on HEAD.
        assertEquals(kashHead, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
        // Real git's fsck accepts the merge commit.
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())
        // HEAD commit has two parents per real git's parse.
        val parents =
            probe
                .run(listOf("rev-list", "-n1", "--parents", "HEAD"), tmp)
                .stdoutUtf8()
                .trim()
                .split(' ')
        assertEquals(3, parents.size, parents.toString()) // commit + 2 parents
        // Both branch tips are ancestors of HEAD per real git.
        val mainParent = parents[1]
        val featureParent = parents[2]
        val mainEditsB = probe.run(listOf("log", "-n1", "--pretty=%s", mainParent), tmp).stdoutUtf8().trim()
        val featureEditsA = probe.run(listOf("log", "-n1", "--pretty=%s", featureParent), tmp).stdoutUtf8().trim()
        assertEquals(setOf("main edits b", "feature edits a"), setOf(mainEditsB, featureEditsA))
        // Real git's view of the worktree at HEAD shows both edits.
        assertEquals("feature-edit\n", probe.run(listOf("show", "HEAD:a"), tmp).stdoutUtf8())
        assertEquals("main-edit\n", probe.run(listOf("show", "HEAD:b"), tmp).stdoutUtf8())
    }

    @Test fun conflictResolutionLandsCorrectMergeCommit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/x", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "x")
        runGit(fs, "/r", "commit", "-m", "base")
        runGit(fs, "/r", "branch", "feature")
        runBlocking { fs.writeBytes("/r/x", "MAIN\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "x")
        runGit(fs, "/r", "commit", "-m", "main")
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/x", "FEATURE\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "x")
        runGit(fs, "/r", "commit", "-m", "feature")

        runGit(fs, "/r", "switch", "main")
        runGit(fs, "/r", "merge", "feature") // conflicts
        // Resolve.
        runBlocking { fs.writeBytes("/r/x", "RESOLVED\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "x")
        val cm = runGit(fs, "/r", "commit", "-m", "merge: resolved")
        assertEquals(0, cm.first, cm.third)
        val kashHead = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(kashHead, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
        assertEquals(0, probe.run(listOf("fsck", "--strict"), tmp).exitCode)
        assertEquals("RESOLVED\n", probe.run(listOf("show", "HEAD:x"), tmp).stdoutUtf8())
        // HEAD must have 2 parents.
        val parents =
            probe
                .run(listOf("rev-list", "-n1", "--parents", "HEAD"), tmp)
                .stdoutUtf8()
                .trim()
                .split(' ')
        assertEquals(3, parents.size)
    }

    @Test fun rebaseLinearizationMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "base")
        runGit(fs, "/r", "branch", "feature")
        runBlocking { fs.writeBytes("/r/m", "m1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "m")
        runGit(fs, "/r", "commit", "-m", "m1")
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/f", "f1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "f1")
        runBlocking { fs.writeBytes("/r/f", "f2\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "f2")
        runGit(fs, "/r", "rebase", "main")
        val kashHead = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(kashHead, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
        assertEquals(0, probe.run(listOf("fsck", "--strict"), tmp).exitCode)

        // Real git's linear log matches our walk order.
        val realLog = probe.run(listOf("log", "--pretty=%s"), tmp).stdoutUtf8()
        assertEquals("f2\nf1\nm1\nbase\n", realLog)
        // 3 non-root commits each have exactly one parent; root has none.
        // Use rev-list to count parents directly — that's the bytes git
        // emits, not subject to LF-trailer ambiguity.
        val parentCounts =
            probe
                .run(listOf("rev-list", "--all", "--parents"), tmp)
                .stdoutUtf8()
                .lines()
                .filter { it.isNotEmpty() }
                .map { it.split(' ').size - 1 } // first token is the commit, rest are parents
                .sorted()
        assertEquals(listOf(0, 1, 1, 1), parentCounts)
    }

    @Test fun cherryPickResultMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "base")
        runGit(fs, "/r", "branch", "feature")
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/b", "from-feature\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "b")
        runGit(fs, "/r", "commit", "-m", "add b")
        val pickSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()
        runGit(fs, "/r", "switch", "main")
        runGit(fs, "/r", "cherry-pick", pickSha)
        val kashHead = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(kashHead, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
        assertEquals(0, probe.run(listOf("fsck", "--strict"), tmp).exitCode)
        assertEquals("from-feature\n", probe.run(listOf("show", "HEAD:b"), tmp).stdoutUtf8())
        // The picked commit's subject lands.
        assertEquals("add b\n", probe.run(listOf("log", "-n1", "--pretty=%s"), tmp).stdoutUtf8())
    }

    @Test fun revertProducesValidInverseCommit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/a", "before\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "before")
        runBlocking { fs.writeBytes("/r/a", "after\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "after")
        val toRevert = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()
        runGit(fs, "/r", "revert", toRevert)
        val kashHead = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(kashHead, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
        assertEquals(0, probe.run(listOf("fsck", "--strict"), tmp).exitCode)
        assertEquals("before\n", probe.run(listOf("show", "HEAD:a"), tmp).stdoutUtf8())
        val head = probe.run(listOf("log", "-n1", "--pretty=%s"), tmp).stdoutUtf8()
        assertTrue(head.startsWith("Revert \""), head)
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
