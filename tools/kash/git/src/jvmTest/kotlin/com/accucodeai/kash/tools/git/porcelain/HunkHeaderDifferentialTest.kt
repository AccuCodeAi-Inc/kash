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
 * Locks the unified-diff hunk-header style to real git's: a unit range
 * collapses to a bare start (`@@ -1 +1,2 @@`) while a zero range keeps its
 * count (`@@ -0,0 +1 @@`). This regressed once when the git wrapper forced
 * `collapseUnitRanges = false`; we now match git, so compare the `@@` lines
 * byte-for-byte across the three interesting shapes.
 */
class HunkHeaderDifferentialTest {
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
    ): String {
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
        runBlocking { GitCommand().run(args.toList(), ctx) }
        err.readString()
        return out.readString()
    }

    private fun hunkLines(diff: String): List<String> = diff.lineSequence().filter { it.startsWith("@@") }.toList()

    @Test fun hunkHeadersMatchRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        // Single-line file committed, then modified three ways.
        runBlocking { fs.writeBytes("/r/f", "one line\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")

        // Extract to disk and mirror the same commit with real git so both
        // see identical blob content.
        runBlocking { copyDir(fs, "/r", tmp) }

        // (a) append a line: old range is unit -> `-1`, new is `+1,2`.
        runBlocking { fs.writeBytes("/r/f", "one line\ntwo line\n".encodeToByteArray()) }
        File(tmp, "f").writeBytes("one line\ntwo line\n".encodeToByteArray())
        assertEquals(
            probe
                .run(listOf("diff", "f"), tmp)
                .stdoutUtf8()
                .lineSequence()
                .filter { it.startsWith("@@") }
                .toList(),
            hunkLines(runGit(fs, "/r", "diff", "f")),
            "append-a-line hunk header",
        )

        // (b) replace the single line: both ranges unit -> `@@ -1 +1 @@`.
        runBlocking { fs.writeBytes("/r/f", "changed\n".encodeToByteArray()) }
        File(tmp, "f").writeBytes("changed\n".encodeToByteArray())
        assertEquals(
            probe
                .run(listOf("diff", "f"), tmp)
                .stdoutUtf8()
                .lineSequence()
                .filter { it.startsWith("@@") }
                .toList(),
            hunkLines(runGit(fs, "/r", "diff", "f")),
            "replace-single-line hunk header",
        )

        // (c) brand-new single-line file: zero range keeps `,0` -> `@@ -0,0 +1 @@`.
        runBlocking { fs.writeBytes("/r/new", "x\n".encodeToByteArray()) }
        File(tmp, "new").writeBytes("x\n".encodeToByteArray())
        runGit(fs, "/r", "add", "new")
        probe.run(listOf("add", "new"), tmp)
        assertEquals(
            probe
                .run(listOf("diff", "--cached", "new"), tmp)
                .stdoutUtf8()
                .lineSequence()
                .filter { it.startsWith("@@") }
                .toList(),
            hunkLines(runGit(fs, "/r", "diff", "--cached", "new")),
            "new-single-line-file hunk header",
        )
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
