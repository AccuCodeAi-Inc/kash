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
 * Differential coverage for the read-side porcelain — blame, diff,
 * status. These don't write to `.git/` so the test pattern is:
 *  1. Build state via kash commands.
 *  2. Extract VFS.
 *  3. Run both kash and real-git versions of the read command.
 *  4. Compare the parts of the output that should agree.
 *
 * Decoration bytes (color, exact whitespace, file-mode lines) are
 * not byte-identical to real git — we don't try to match those. We
 * compare *semantically meaningful* output: per-line SHAs for blame,
 * `+`/`-` lines for diff, XY/path tuples for status.
 */
class BlameDiffStatusDifferentialTest {
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

    @Test fun blameShasMatchRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "a\nb\nc\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "c1")
        runBlocking { fs.writeBytes("/r/f", "a\nB\nc\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "c2")
        runBlocking { fs.writeBytes("/r/f", "a\nB\nc\nd\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "c3")

        // kash blame
        val kashBlame = runGit(fs, "/r", "blame", "f").second
        // Extract the 8-char prefix at the start of each line.
        val kashShortShas =
            kashBlame.lines().filter { it.isNotEmpty() }.map { it.substring(0, 8) }

        runBlocking { copyDir(fs, "/r", tmp) }
        // Real git's --line-porcelain emits a header line per output line
        // beginning with the 40-char sha. Compare against our short-sha
        // prefixes.
        val realBlame = probe.run(listOf("blame", "--line-porcelain", "f"), tmp).stdoutUtf8()
        val realShortShas =
            realBlame
                .lines()
                .filter { it.length == 40 + 1 + 1 + 1 + 1 + 1 || it.matches(Regex("^[0-9a-f]{40} .*")) }
                .map { it.substring(0, 8) }

        // Both lists should be size 4 and identical (modulo formatting).
        // Real git's --line-porcelain has *one* sha header per result line.
        assertEquals(4, kashShortShas.size, "kash: $kashBlame")
        assertEquals(4, realShortShas.size, "real: $realBlame")
        assertEquals(realShortShas, kashShortShas, "blame SHA divergence")
    }

    @Test fun diffHunksMatchRealGitForWorktreeVsIndex(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking {
            fs.writeBytes(
                "/r/f",
                """
                line1
                line2
                line3
                line4
                line5
                """.trimIndent().encodeToByteArray(),
            )
        }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")
        // Modify lines 2 and 4 in the work tree only.
        runBlocking {
            fs.writeBytes(
                "/r/f",
                """
                line1
                LINE2
                line3
                LINE4
                line5
                """.trimIndent().encodeToByteArray(),
            )
        }

        val kashDiff = runGit(fs, "/r", "diff").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realDiff = probe.run(listOf("diff", "--no-color"), tmp).stdoutUtf8()

        val kashChangeLines = extractChangeLines(kashDiff)
        val realChangeLines = extractChangeLines(realDiff)
        assertEquals(realChangeLines, kashChangeLines, "diff `+/-` body lines must match")
    }

    @Test fun diffCachedMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "old\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")
        runBlocking { fs.writeBytes("/r/f", "new\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f") // staged change

        val kashDiff = runGit(fs, "/r", "diff", "--cached").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realDiff = probe.run(listOf("diff", "--cached", "--no-color"), tmp).stdoutUtf8()

        assertEquals(extractChangeLines(realDiff), extractChangeLines(kashDiff))
    }

    @Test fun statusPorcelainMatchesRealGitAcrossStates(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking {
            fs.writeBytes("/r/tracked-modify", "v1\n".encodeToByteArray())
            fs.writeBytes("/r/tracked-delete", "del\n".encodeToByteArray())
            fs.writeBytes("/r/tracked-unchanged", "ok\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "-A")
        runGit(fs, "/r", "commit", "-m", "base")

        // Now set up several states:
        runBlocking {
            // modify a tracked file in worktree (unstaged)
            fs.writeBytes("/r/tracked-modify", "v2\n".encodeToByteArray())
            // create a new untracked file
            fs.writeBytes("/r/new-untracked", "n\n".encodeToByteArray())
            // stage a new file
            fs.writeBytes("/r/staged-add", "s\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "staged-add")
        // delete a tracked file in worktree
        fs.remove("/r/tracked-delete")

        val kashStatus = runGit(fs, "/r", "status", "--porcelain").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realStatus = probe.run(listOf("status", "--porcelain"), tmp).stdoutUtf8()
        assertEquals(realStatus, kashStatus, "porcelain status must be byte-identical")
    }

    @Test fun statusOfFreshlyAddedFile(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "x\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")

        val kashStatus = runGit(fs, "/r", "status", "--porcelain").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realStatus = probe.run(listOf("status", "--porcelain"), tmp).stdoutUtf8()
        assertEquals(realStatus, kashStatus)
    }

    @Test fun statusAcrossCommits(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "v1\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "v1")
        // Now mutate v1 → v2 in worktree; stage v3.
        runBlocking { fs.writeBytes("/r/f", "v2\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runBlocking { fs.writeBytes("/r/f", "v3\n".encodeToByteArray()) }
        // status should show MM (staged differs from HEAD AND worktree differs from index).
        val kashStatus = runGit(fs, "/r", "status", "--porcelain").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realStatus = probe.run(listOf("status", "--porcelain"), tmp).stdoutUtf8()
        assertEquals(realStatus, kashStatus, "MM-state status must match")
    }

    /** Extract just the `+ ` and `- ` body lines from a unified diff (skip headers). */
    private fun extractChangeLines(diff: String): List<String> =
        diff.lines().filter {
            (it.startsWith("+") && !it.startsWith("+++")) ||
                (it.startsWith("-") && !it.startsWith("---"))
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

    @Suppress("unused")
    private val ignoreUnused: String? = null
}
