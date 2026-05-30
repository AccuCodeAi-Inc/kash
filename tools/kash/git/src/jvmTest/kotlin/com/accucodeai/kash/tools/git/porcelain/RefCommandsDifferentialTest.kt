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
 * Differential parity for the ref-inspection plumbing (for-each-ref,
 * show-ref, symbolic-ref, update-ref). We build a repo through `kash
 * git`, copy it to disk, and assert that `kash git <ref-cmd>` produces
 * byte-identical stdout to `/usr/bin/git <ref-cmd>` on the same repo.
 */
class RefCommandsDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

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
        env: Map<String, String> =
            mapOf(
                "GIT_AUTHOR_DATE" to "1700000000 +0000",
                "GIT_COMMITTER_DATE" to "1700000000 +0000",
            ),
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env.toMutableMap(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /** Build the shared scenario in the kash VFS; return (fs, headSha). */
    private fun buildScenario(): Pair<InMemoryFs, String> {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q", "-b", "main")
        runBlocking {
            fs.writeBytes("/work/a.txt", "a\n".encodeToByteArray())
        }
        runGit(fs, "/work", "add", "a.txt")
        runGit(fs, "/work", "commit", "-m", "c1")
        runGit(fs, "/work", "branch", "feature")
        runGit(fs, "/work", "tag", "light")
        val head = runGit(fs, "/work", "rev-parse", "HEAD").stdout.trim()
        return fs to head
    }

    private fun realInit(
        tmp: File,
        fs: InMemoryFs,
    ) {
        // git refuses to init over an existing .git copied from VFS that it
        // doesn't fully recognize; instead recreate the scenario natively so
        // both sides observe an identical-by-construction ref set.
        runBlocking { copyDir(fs, "/work", tmp) }
    }

    private val det =
        mapOf(
            "GIT_AUTHOR_DATE" to "1700000000 +0000",
            "GIT_COMMITTER_DATE" to "1700000000 +0000",
        )

    @Test fun forEachRefMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val (fs, _) = buildScenario()
        realInit(tmp, fs)

        for (
        args in
        listOf(
            listOf("for-each-ref"),
            listOf("for-each-ref", "--format=%(refname)"),
            listOf("for-each-ref", "--format=%(refname:short)"),
            listOf("for-each-ref", "--format=%(objectname) %(objecttype) %(refname)"),
            listOf("for-each-ref", "--format=%(objectname:short)"),
            listOf("for-each-ref", "--format=%(HEAD) %(refname:short)"),
            listOf("for-each-ref", "--sort=-refname", "--format=%(refname)"),
            listOf("for-each-ref", "--count=1", "--format=%(refname)"),
            listOf("for-each-ref", "--format=%(refname)", "refs/heads"),
        )
        ) {
            val kash = runGit(fs, "/work", *args.toTypedArray())
            val real = probe.run(args, tmp, env = det)
            assertEquals(real.stdoutUtf8(), kash.stdout, "stdout mismatch for: $args")
            assertEquals(real.exitCode, kash.rc, "rc mismatch for: $args")
        }
    }

    @Test fun showRefMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val (fs, _) = buildScenario()
        realInit(tmp, fs)

        for (
        args in
        listOf(
            listOf("show-ref"),
            listOf("show-ref", "--heads"),
            listOf("show-ref", "--tags"),
            listOf("show-ref", "-s"),
            listOf("show-ref", "main"),
            listOf("show-ref", "heads/main"),
            listOf("show-ref", "--verify", "refs/heads/main"),
        )
        ) {
            val kash = runGit(fs, "/work", *args.toTypedArray())
            val real = probe.run(args, tmp, env = det)
            assertEquals(real.stdoutUtf8(), kash.stdout, "stdout mismatch for: $args")
            assertEquals(real.exitCode, kash.rc, "rc mismatch for: $args")
        }
    }

    @Test fun showRefNoMatchAndVerifyMissExitCodes(
        @TempDir tmp: File,
    ) {
        val (fs, _) = buildScenario()
        realInit(tmp, fs)

        val kashNoMatch = runGit(fs, "/work", "show-ref", "zzz")
        val realNoMatch = probe.run(listOf("show-ref", "zzz"), tmp, env = det)
        assertEquals(realNoMatch.exitCode, kashNoMatch.rc)
        assertEquals(realNoMatch.stdoutUtf8(), kashNoMatch.stdout)

        val kashVerify = runGit(fs, "/work", "show-ref", "--verify", "refs/heads/nope")
        val realVerify = probe.run(listOf("show-ref", "--verify", "refs/heads/nope"), tmp, env = det)
        assertEquals(realVerify.exitCode, kashVerify.rc)
    }

    @Test fun symbolicRefMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val (fs, _) = buildScenario()
        realInit(tmp, fs)

        for (
        args in
        listOf(
            listOf("symbolic-ref", "HEAD"),
            listOf("symbolic-ref", "--short", "HEAD"),
        )
        ) {
            val kash = runGit(fs, "/work", *args.toTypedArray())
            val real = probe.run(args, tmp, env = det)
            assertEquals(real.stdoutUtf8(), kash.stdout, "stdout mismatch for: $args")
            assertEquals(real.exitCode, kash.rc, "rc mismatch for: $args")
        }
    }

    @Test fun updateRefThenRevParseAgreesWithRealGit(
        @TempDir tmp: File,
    ) {
        val (fs, head) = buildScenario()
        realInit(tmp, fs)

        // Create the same new ref on both sides, then compare resolution.
        runGit(fs, "/work", "update-ref", "refs/heads/created", head)
        probe.run(listOf("update-ref", "refs/heads/created", head), tmp, env = det)

        val kashShow = runGit(fs, "/work", "show-ref", "created")
        val realShow = probe.run(listOf("show-ref", "created"), tmp, env = det)
        assertEquals(realShow.stdoutUtf8(), kashShow.stdout)

        // Mismatched oldvalue must fail identically (exit 128).
        val bogus = "1".repeat(40)
        val kashFail = runGit(fs, "/work", "update-ref", "refs/heads/created", head, bogus)
        val realFail = probe.run(listOf("update-ref", "refs/heads/created", head, bogus), tmp, env = det)
        assertEquals(realFail.exitCode, kashFail.rc)
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
