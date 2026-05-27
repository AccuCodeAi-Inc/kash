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

class BranchSwitchDifferentialTest {
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

    @Test fun divergentBranchesAndSwitch(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "init")

        runGit(fs, "/r", "branch", "feature")
        runGit(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/a", "feature\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "feature-edit")
        runGit(fs, "/r", "switch", "main")
        runBlocking { fs.writeBytes("/r/a", "main\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "main-edit")

        runBlocking { copyDir(fs, "/r", tmp) }

        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), tmp)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())

        // Real git agrees on branch contents.
        assertEquals("main\n", probe.run(listOf("show", "main:a"), tmp).stdoutUtf8())
        assertEquals("feature\n", probe.run(listOf("show", "feature:a"), tmp).stdoutUtf8())
        val branches = probe.run(listOf("branch", "--list"), tmp).stdoutUtf8()
        assertTrue("main" in branches, branches)
        assertTrue("feature" in branches, branches)
    }

    @Test fun resetHardMatchesRealGitView(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/a", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "first")
        val firstSha = runGit(fs, "/r", "rev-parse", "HEAD").second.trim()

        runBlocking { fs.writeBytes("/r/a", "v2\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "a")
        runGit(fs, "/r", "commit", "-m", "second")

        runGit(fs, "/r", "reset", "--hard", firstSha)
        assertEquals("v1\n", runBlocking { fs.readBytes("/r/a").decodeToString() })

        runBlocking { copyDir(fs, "/r", tmp) }
        assertEquals(firstSha, probe.run(listOf("rev-parse", "HEAD"), tmp).stdoutUtf8().trim())
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
