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
 * Differential coverage for the `git diff` flags added in this work:
 * --numstat, --shortstat, --name-status, --diff-filter, -R, --exit-code,
 * --quiet, whitespace ignore flags, and --no-index. Where exact byte
 * parity is robust (numstat, name-status, exit codes, +/- body lines)
 * we assert byte-identical; for unified bodies we compare the +/- lines.
 */
class DiffFlagsDifferentialTest {
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

    private fun seed(fs: InMemoryFs) {
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking {
            fs.writeBytes("/r/f", "line1\nline2\nline3\nline4\nline5\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "init")
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

    private fun changeLines(diff: String): List<String> =
        diff.lines().filter {
            (it.startsWith("+") && !it.startsWith("+++")) ||
                (it.startsWith("-") && !it.startsWith("---"))
        }

    @Test fun numstatMatches(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nLINE2\nline3\nLINE4\nline5\n".encodeToByteArray())
            fs.writeBytes("/r/g", "a\nb\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "g")
        val kash = runGit(fs, "/r", "diff", "--numstat").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--numstat"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun numstatCachedAddAndBinary(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/added", "x\ny\nz\n".encodeToByteArray())
            fs.writeBytes("/r/bin", byteArrayOf(0, 1, 2, 3, 4))
        }
        runGit(fs, "/r", "add", "added", "bin")
        val kash = runGit(fs, "/r", "diff", "--cached", "--numstat").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--cached", "--numstat"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun shortstatMatches(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nLINE2\nline3\nLINE4\nline5\nline6\n".encodeToByteArray())
        }
        val kash = runGit(fs, "/r", "diff", "--shortstat").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--shortstat"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun nameStatusMatches(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nCHANGED\nline3\nline4\nline5\n".encodeToByteArray())
            fs.writeBytes("/r/added", "n\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "added")
        // commit the add so deletion can be tested in worktree
        val kashCached = runGit(fs, "/r", "diff", "--cached", "--name-status").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val realCached = probe.run(listOf("diff", "--cached", "--name-status"), tmp).stdoutUtf8()
        assertEquals(realCached, kashCached)
    }

    @Test fun nameStatusDelete(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        fs.remove("/r/f")
        val kash = runGit(fs, "/r", "diff", "--name-status").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--name-status"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun diffFilterModifiedOnly(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nXX\nline3\nline4\nline5\n".encodeToByteArray())
            fs.writeBytes("/r/added", "n\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "added")
        val kash = runGit(fs, "/r", "diff", "--cached", "--diff-filter=M", "--name-only").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--cached", "--diff-filter=M", "--name-only"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun diffFilterAddedOnly(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nXX\nline3\nline4\nline5\n".encodeToByteArray())
            fs.writeBytes("/r/added", "n\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "added")
        val kash = runGit(fs, "/r", "diff", "--cached", "--diff-filter=A", "--name-only").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--cached", "--diff-filter=A", "--name-only"), tmp).stdoutUtf8()
        assertEquals(real, kash)
    }

    @Test fun reverseSwapsSides(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nLINE2\nline3\nline4\nline5\n".encodeToByteArray())
        }
        val kash = runGit(fs, "/r", "diff", "-R").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "-R"), tmp).stdoutUtf8()
        assertEquals(changeLines(real), changeLines(kash))
        // The header labels should also be swapped.
        assert(kash.contains("--- b/f")) { "expected reversed label, got:\n$kash" }
        assert(kash.contains("+++ a/f")) { "expected reversed label, got:\n$kash" }
    }

    @Test fun exitCodeAndQuiet(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        // No change -> exit 0.
        assertEquals(0, runGit(fs, "/r", "diff", "--exit-code").first)
        assertEquals(0, runGit(fs, "/r", "diff", "--quiet").first)
        runBlocking {
            fs.writeBytes("/r/f", "line1\nLINE2\nline3\nline4\nline5\n".encodeToByteArray())
        }
        // Change -> exit 1.
        assertEquals(1, runGit(fs, "/r", "diff", "--exit-code").first)
        // --quiet suppresses output.
        val q = runGit(fs, "/r", "diff", "--quiet")
        assertEquals(1, q.first)
        assertEquals("", q.second)
        // --exit-code still prints the diff.
        assert(runGit(fs, "/r", "diff", "--exit-code").second.isNotEmpty())
    }

    @Test fun ignoreAllSpace(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "hello world\nkeep\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "i")
        runBlocking { fs.writeBytes("/r/f", "hello    world\nkeep\n".encodeToByteArray()) }

        val kash = runGit(fs, "/r", "diff", "-w").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "-w"), tmp).stdoutUtf8()
        assertEquals(changeLines(real), changeLines(kash))
        // -w: only-whitespace change => no body lines.
        assert(changeLines(kash).isEmpty()) { "kash -w should suppress whitespace-only change:\n$kash" }
    }

    @Test fun ignoreSpaceChange(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "a b c\nx\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "i")
        // Collapse runs of spaces; -b ignores, -w too, but plain shows.
        runBlocking { fs.writeBytes("/r/f", "a   b   c\nx\n".encodeToByteArray()) }
        val kash = runGit(fs, "/r", "diff", "-b").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "-b"), tmp).stdoutUtf8()
        assertEquals(changeLines(real), changeLines(kash))
    }

    @Test fun ignoreSpaceAtEol(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", "init", "-q")
        runBlocking { fs.writeBytes("/r/f", "trailing\nkeep\n".encodeToByteArray()) }
        runGit(fs, "/r", "add", "f")
        runGit(fs, "/r", "commit", "-m", "i")
        runBlocking { fs.writeBytes("/r/f", "trailing   \nkeep\n".encodeToByteArray()) }
        val kash = runGit(fs, "/r", "diff", "--ignore-space-at-eol").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--ignore-space-at-eol"), tmp).stdoutUtf8()
        assertEquals(changeLines(real), changeLines(kash))
    }

    @Test fun noIndexTwoFiles(
        @TempDir tmp: File,
    ) {
        // Use the real filesystem for both kash (via OS FS? no — kash uses
        // its VFS). We compare only the +/- body for the no-index headers.
        val a = File(tmp, "x1")
        val b = File(tmp, "x2")
        a.writeText("a\nb\nc\n")
        b.writeText("a\nB\nc\n")
        val real = probe.run(listOf("diff", "--no-index", a.path, b.path), tmp)
        // kash --no-index against its own VFS:
        val fs = InMemoryFs()
        fs.mkdirs("/d")
        runBlocking {
            fs.writeBytes("/d/x1", "a\nb\nc\n".encodeToByteArray())
            fs.writeBytes("/d/x2", "a\nB\nc\n".encodeToByteArray())
        }
        val kash = runGit(fs, "/d", "diff", "--no-index", "x1", "x2")
        assertEquals(changeLines(real.stdoutUtf8()), changeLines(kash.second))
        assertEquals(1, kash.first)
        assertEquals(1, real.exitCode)
    }

    @Test fun statDefaultWidthMatches(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        runBlocking {
            // small change that fits 1:1 in the default 80-col graph
            fs.writeBytes("/r/f", "line1\nLINE2\nline3\nLINE4\nline5\nadded6\n".encodeToByteArray())
            fs.writeBytes("/r/short", "x\n".encodeToByteArray())
        }
        runGit(fs, "/r", "add", "short")
        val kash = runGit(fs, "/r", "diff", "--cached", "--stat").second
        val kashWt = runGit(fs, "/r", "diff", "--stat").second
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("diff", "--cached", "--stat"), tmp).stdoutUtf8()
        val realWt = probe.run(listOf("diff", "--stat"), tmp).stdoutUtf8()
        assertEquals(real, kash, "cached --stat must match real git")
        assertEquals(realWt, kashWt, "worktree --stat must match real git")
    }

    @Test fun noIndexIdenticalExitsZero() {
        val fs = InMemoryFs()
        fs.mkdirs("/d")
        runBlocking {
            fs.writeBytes("/d/x1", "same\n".encodeToByteArray())
            fs.writeBytes("/d/x2", "same\n".encodeToByteArray())
        }
        val kash = runGit(fs, "/d", "diff", "--no-index", "x1", "x2")
        assertEquals(0, kash.first)
        assertEquals("", kash.second)
    }
}
