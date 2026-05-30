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
 * Differential coverage: `kash git grep` vs `/usr/bin/git grep` over the
 * same tracked content. We compare stdout byte-for-byte AND exit codes —
 * grep output has no decoration to normalize away (no color in our
 * output; real git here runs with `--no-color` implicitly via no TTY).
 */
class GrepDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    private fun runKash(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Pair<Int, String> {
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
        return res.exitCode to out.readString()
    }

    private fun seed(fs: InMemoryFs) {
        fs.mkdirs("/r")
        runKash(fs, "/r", "init", "-q")
        runBlocking {
            fs.writeBytes("/r/a.txt", "foo bar\nBAR baz\nhello world\n".encodeToByteArray())
            fs.writeBytes("/r/b.txt", "line one\nfoo again\n".encodeToByteArray())
            fs.mkdirs("/r/sub")
            fs.writeBytes("/r/sub/c.txt", "sub foo\nnope\n".encodeToByteArray())
            fs.writeBytes("/r/p.txt", "aaa\na+b\ncolor\ncolour\n".encodeToByteArray())
        }
        runKash(fs, "/r", "add", "-A")
        runKash(fs, "/r", "commit", "-m", "init")
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

    private fun assertMatchesRealGit(
        tmp: File,
        vararg args: String,
    ) {
        val fs = InMemoryFs()
        seed(fs)
        val (kashRc, kashOut) = runKash(fs, "/r", *(arrayOf("grep") + args))
        runBlocking { copyDir(fs, "/r", tmp) }
        val real = probe.run(listOf("grep") + args, tmp)
        assertEquals(real.stdoutUtf8(), kashOut, "stdout for: grep ${args.joinToString(" ")}")
        assertEquals(real.exitCode, kashRc, "exit code for: grep ${args.joinToString(" ")}")
    }

    @Test fun basic(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "foo")

    @Test fun lineNumber(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-n", "foo")

    @Test fun ignoreCase(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-i", "bar")

    @Test fun filesWithMatches(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-l", "foo")

    @Test fun filesWithoutMatch(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-L", "world")

    @Test fun count(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-c", "foo")

    @Test fun countCaseSensitiveSkipsZero(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-c", "bar")

    @Test fun invert(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-v", "foo", "a.txt")

    @Test fun suppressFilename(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-h", "foo")

    @Test fun multiPatternOr(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-e", "foo", "-e", "world", "a.txt")

    @Test fun extendedRegexp(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-E", "foo|world", "a.txt")

    @Test fun basicRegexAlternation(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "foo\\|world", "a.txt")

    @Test fun basicRegexPlusLiteral(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "a+", "p.txt")

    @Test fun basicRegexPlusQuantifier(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "a\\+", "p.txt")

    @Test fun basicRegexOptional(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "colou\\?r", "p.txt")

    @Test fun fixedStrings(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-F", "a+b", "p.txt")

    @Test fun wordRegexp(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-w", "foo")

    @Test fun treeIsh(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "foo", "HEAD")

    @Test fun treeIshLineNumber(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "-n", "foo", "HEAD")

    @Test fun cached(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "--cached", "foo")

    @Test fun pathspec(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "foo", "--", "sub/")

    @Test fun pathspecExactFile(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "foo", "a.txt")

    @Test fun noMatch(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "zzz-no-such-token")

    @Test fun anchors(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "^foo", "a.txt")

    @Test fun charClass(
        @TempDir tmp: File,
    ) = assertMatchesRealGit(tmp, "[fb]oo", "a.txt")
}
