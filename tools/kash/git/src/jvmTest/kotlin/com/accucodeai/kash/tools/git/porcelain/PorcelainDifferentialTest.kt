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
 * Build a repo through `kash git init/add/commit`, extract it to disk,
 * and have `/usr/bin/git fsck` + `git log` + `git show` validate the
 * result. The bar is "real git treats this repo as 100% valid."
 */
class PorcelainDifferentialTest {
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
        env: Map<String, String> = mapOf("GIT_AUTHOR_DATE" to "1700000000 +0000"),
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

    @Test fun kashCommitYieldsRepoThatPassesFsckAndLogs(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            fs.writeBytes("/work/README.md", "# kash\n".encodeToByteArray())
            fs.mkdirs("/work/src")
            fs.writeBytes("/work/src/main.kt", "fun main() {}\n".encodeToByteArray())
        }
        runGit(fs, "/work", "add", "-A")
        val cm = runGit(fs, "/work", "commit", "-m", "initial import")
        assertEquals(0, cm.rc, cm.stderr)

        runBlocking { copyDir(fs, "/work", tmp) }

        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, "fsck failed:\n${fsck.stderrUtf8()}\n${fsck.stdoutUtf8()}")

        val log = probe.run(listOf("log", "--pretty=%s"), tmp)
        assertEquals(0, log.exitCode, log.stderrUtf8())
        assertEquals("initial import\n", log.stdoutUtf8())

        val showReadme = probe.run(listOf("show", "HEAD:README.md"), tmp)
        assertEquals("# kash\n", showReadme.stdoutUtf8())

        val showSrc = probe.run(listOf("show", "HEAD:src/main.kt"), tmp)
        assertEquals("fun main() {}\n", showSrc.stdoutUtf8())
    }

    @Test fun twoCommitsChainAndRealGitAgreesOnHeadSha(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking { fs.writeBytes("/work/a", "v1\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "a")
        runGit(fs, "/work", "commit", "-m", "v1")

        runBlocking { fs.writeBytes("/work/a", "v2\n".encodeToByteArray()) }
        runGit(fs, "/work", "add", "a")
        runGit(fs, "/work", "commit", "-m", "v2")

        val kashHead = runGit(fs, "/work", "rev-parse", "HEAD").stdout.trim()

        runBlocking { copyDir(fs, "/work", tmp) }
        val realHead = probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim()
        assertEquals(kashHead, realHead)
        val realLog = probe.run(listOf("log", "--pretty=%s"), tmp).stdoutUtf8()
        assertEquals("v2\nv1\n", realLog)
    }

    @Test fun realGitStatusMatchesOursAfterAdd(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runGit(fs, "/work", "init", "-q")
        runBlocking {
            fs.writeBytes("/work/staged", "S".encodeToByteArray())
            fs.writeBytes("/work/untracked", "U".encodeToByteArray())
        }
        runGit(fs, "/work", "add", "staged")

        val kashStatus = runGit(fs, "/work", "status", "--porcelain").stdout
        assertTrue("A  staged\n" in kashStatus, kashStatus)
        assertTrue("?? untracked\n" in kashStatus, kashStatus)

        runBlocking { copyDir(fs, "/work", tmp) }
        val realStatus = probe.run(listOf("status", "--porcelain"), tmp).stdoutUtf8()
        assertEquals(kashStatus, realStatus)
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
