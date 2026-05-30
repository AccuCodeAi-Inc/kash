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
 * Differential tests for `git tag` against `/usr/bin/git`. The headline
 * assertion is byte-identical tag objects (and therefore matching SHAs)
 * for the same inputs: identity + date are pinned via GIT_COMMITTER_*.
 */
class TagDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        // Pinned committer identity/date so the tagger line (and thus the
        // tag-object SHA) matches between kash and real git.
        private val ENV =
            mapOf(
                "GIT_AUTHOR_NAME" to "Test User",
                "GIT_AUTHOR_EMAIL" to "test@example.com",
                "GIT_AUTHOR_DATE" to "1700000000 +0000",
                "GIT_COMMITTER_NAME" to "Test User",
                "GIT_COMMITTER_EMAIL" to "test@example.com",
                "GIT_COMMITTER_DATE" to "1700000000 +0000",
            )

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
        stdin: ByteArray = ByteArray(0),
        env: Map<String, String> = ENV,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val inBuf = Buffer().also { it.write(stdin) }
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

    /** Init a kash repo with one commit; return the fs. */
    private fun seedRepo(fs: InMemoryFs) {
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/a", "v1\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "a")
        runGit(fs, "/work", "commit", "-m", "c1")
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
            if (fs.isDirectory(full)) copyDir(fs, full, out) else out.writeBytes(fs.readBytes(full))
        }
    }

    /**
     * The tag-object SHA is what `refs/tags/<name>` points at for an
     * annotated tag. We read the ref file directly rather than via
     * `rev-parse` (kash peels annotated tags on rev-parse; real git does
     * not — but that's a rev-parse concern, out of scope here).
     */
    private fun kashTagObjectSha(
        fs: InMemoryFs,
        name: String,
    ): String = runBlocking { fs.readBytes("/work/.git/refs/tags/$name").decodeToString().trim() }

    @Test fun annotatedTagShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        val kashTag = runGit(fs, "/work", "tag", "-a", "v1.0", "-m", "first release")
        assertEquals(0, kashTag.rc, kashTag.stderr)
        val kashSha = kashTagObjectSha(fs, "v1.0")

        // Build the same tag with real git in a fresh repo and compare SHAs.
        val realRepo = makeRealRepo()
        probe.run(listOf("tag", "-a", "v1.0", "-m", "first release"), realRepo, ENV)
        val realRepoSha = probe.run(listOf("rev-parse", "v1.0"), realRepo, ENV).stdoutUtf8().trim()
        assertEquals(realRepoSha, kashSha, "annotated tag object SHA must match a real-git-built tag")

        runBlocking { copyDir(fs, "/work", tmp) }
        assertEquals("tag", probe.run(listOf("cat-file", "-t", "v1.0"), tmp, ENV).stdoutUtf8().trim())
        val kashObj = probe.run(listOf("cat-file", "-p", "v1.0"), tmp, ENV)
        assertTrue("first release" in kashObj.stdoutUtf8(), kashObj.stdoutUtf8())
    }

    /** Real-git repo with an identical single commit, for SHA cross-checks. */
    private fun makeRealRepo(): File {
        val dir = probe.freshRepo("main")
        File(dir, "a").writeText("v1\n")
        probe.run(listOf("add", "a"), dir, ENV)
        probe.run(listOf("commit", "-m", "c1"), dir, ENV)
        return dir
    }

    @Test fun multipleMessagesJoinedByBlankLine(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/work", "tag", "-a", "multi", "-m", "first", "-m", "second")
        val kashSha = kashTagObjectSha(fs, "multi")

        val realRepo = makeRealRepo()
        probe.run(listOf("tag", "-a", "multi", "-m", "first", "-m", "second"), realRepo, ENV)
        val realSha = probe.run(listOf("rev-parse", "multi"), realRepo, ENV).stdoutUtf8().trim()
        assertEquals(realSha, kashSha)
    }

    @Test fun messageFromStdinMatchesRealGit() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val body = "line one\n\n\nline two\ntrailing spaces   \n# a comment\n\n".encodeToByteArray()
        runGit(fs, "/work", "tag", "-a", "fromstdin", "-F", "-", stdin = body)
        val kashSha = kashTagObjectSha(fs, "fromstdin")

        val realRepo = makeRealRepo()
        probe.run(listOf("tag", "-a", "fromstdin", "-F", "-"), realRepo, ENV, stdin = body)
        val realSha = probe.run(listOf("rev-parse", "fromstdin"), realRepo, ENV).stdoutUtf8().trim()
        assertEquals(realSha, kashSha, "cleanup of stdin message must match real git")
    }

    @Test fun signFallsBackToAnnotatedWithStderrNote() {
        val fs = InMemoryFs()
        seedRepo(fs)
        val res = runGit(fs, "/work", "tag", "-s", "signed", "-m", "sig")
        assertEquals(0, res.rc, res.stderr)
        assertTrue("signing is unsupported" in res.stderr, res.stderr)
        // Still produces a (real, unsigned) annotated tag object.
        val type = runGit(fs, "/work", "cat-file", "-t", "signed").stdout.trim()
        assertEquals("tag", type)
    }

    @Test fun forceOverwriteAndUpdatedMessage() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/work", "tag", "lw", "HEAD")
        // Re-tag without force: fatal.
        val dup = runGit(fs, "/work", "tag", "lw", "HEAD")
        assertEquals(128, dup.rc)
        assertTrue("already exists" in dup.stderr, dup.stderr)

        // A second commit, then force-move the tag.
        runBlocking { fs.writeBytes("/work/a", "v2\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "a")
        runGit(fs, "/work", "commit", "-m", "c2")
        val oldSha = runGit(fs, "/work", "rev-parse", "lw").stdout.trim()
        val force = runGit(fs, "/work", "tag", "-f", "lw", "HEAD")
        assertEquals(0, force.rc, force.stderr)
        assertTrue(force.stdout.startsWith("Updated tag 'lw' (was ${oldSha.substring(0, 7)})"), force.stdout)
    }

    @Test fun deletePrintsWasShortSha() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/work", "tag", "-a", "del", "-m", "x")
        val sha = kashTagObjectSha(fs, "del")
        val del = runGit(fs, "/work", "tag", "-d", "del")
        assertEquals(0, del.rc, del.stderr)
        assertEquals("Deleted tag 'del' (was ${sha.substring(0, 7)})\n", del.stdout)

        val missing = runGit(fs, "/work", "tag", "-d", "nope")
        assertEquals(1, missing.rc)
        assertTrue("not found" in missing.stderr, missing.stderr)
    }

    @Test fun listWithAnnotationLines(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/work", "tag", "-a", "v1", "-m", "annotated one")
        runGit(fs, "/work", "tag", "light", "HEAD")

        runBlocking { copyDir(fs, "/work", tmp) }
        val kashN = runGit(fs, "/work", "tag", "-n").stdout
        val realN = probe.run(listOf("tag", "-n"), tmp, ENV).stdoutUtf8()
        assertEquals(realN, kashN, "`git tag -n` output must match real git")
    }

    @Test fun listGlobPatternAndSort() {
        val fs = InMemoryFs()
        seedRepo(fs)
        for (n in listOf("v1.0", "v1.1", "v2.0", "rc1")) runGit(fs, "/work", "tag", n, "HEAD")
        assertEquals("v1.0\nv1.1\nv2.0\n", runGit(fs, "/work", "tag", "-l", "v*").stdout)
        assertEquals("rc1\nv1.0\nv1.1\nv2.0\n", runGit(fs, "/work", "tag").stdout)
        assertEquals("v2.0\nv1.1\nv1.0\nrc1\n", runGit(fs, "/work", "tag", "--sort=-refname").stdout)
    }

    @Test fun pointsAtAndContains() {
        val fs = InMemoryFs()
        seedRepo(fs)
        runGit(fs, "/work", "tag", "t1", "HEAD")
        runBlocking { fs.writeBytes("/work/a", "v2\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "a")
        runGit(fs, "/work", "commit", "-m", "c2")
        runGit(fs, "/work", "tag", "t2", "HEAD")

        assertEquals("t2\n", runGit(fs, "/work", "tag", "--points-at", "HEAD").stdout)
        assertEquals("t1\nt2\n", runGit(fs, "/work", "tag", "--contains", "HEAD~1").stdout)
        assertEquals("t2\n", runGit(fs, "/work", "tag", "--contains", "HEAD").stdout)
        assertEquals("t1\nt2\n", runGit(fs, "/work", "tag", "--merged", "HEAD").stdout)
    }
}
