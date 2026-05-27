package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class InitDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    @Test fun kashInitProducesRepoRealGitAccepts(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = "/work",
                stdin = Buffer().asSuspendSource(),
                stdout = Buffer().asSuspendSink(),
                stderr = Buffer().asSuspendSink(),
            )
        runTest { GitCommand().run(listOf("init", "-q"), ctx) }

        // Extract the VFS to a temp directory.
        runTest { copyDir(fs, "/work", tmp) }

        // Real git should treat this as a valid empty repo.
        val statusResult = probe.run(listOf("status"), tmp)
        assertEquals(0, statusResult.exitCode, statusResult.stderrUtf8())
        val branch = probe.run(listOf("symbolic-ref", "HEAD"), tmp)
        assertEquals(0, branch.exitCode, branch.stderrUtf8())
        assertEquals("refs/heads/main\n", branch.stdoutUtf8())
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
