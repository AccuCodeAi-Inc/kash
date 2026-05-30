package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end behavior of `git grep` against an in-memory VFS. Real-git
 * differential coverage lives in `jvmTest/.../porcelain/GrepDifferentialTest.kt`.
 */
class GrepTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
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
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private suspend fun seed(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        fs.writeBytes("/r/a.txt", "foo bar\nBAR baz\nhello world\n".encodeToByteArray())
        fs.writeBytes("/r/b.txt", "line one\nfoo again\n".encodeToByteArray())
        fs.mkdirs("/r/sub")
        fs.writeBytes("/r/sub/c.txt", "sub foo\nnope\n".encodeToByteArray())
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", "init")
    }

    @Test fun basicMatch() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "foo")
            assertEquals(0, r.rc)
            assertEquals("a.txt:foo bar\nb.txt:foo again\nsub/c.txt:sub foo\n", r.stdout)
        }

    @Test fun noMatchExits1() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "zzz-nope")
            assertEquals(1, r.rc)
            assertEquals("", r.stdout)
        }

    @Test fun lineNumbers() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-n", "foo")
            assertEquals("a.txt:1:foo bar\nb.txt:2:foo again\nsub/c.txt:1:sub foo\n", r.stdout)
        }

    @Test fun ignoreCase() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-i", "bar")
            assertEquals("a.txt:foo bar\na.txt:BAR baz\n", r.stdout)
        }

    @Test fun filesWithMatches() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-l", "foo")
            assertEquals("a.txt\nb.txt\nsub/c.txt\n", r.stdout)
        }

    @Test fun filesWithoutMatch() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            // only b.txt has no "world"
            val r = run(fs, "/r", "grep", "-L", "world")
            assertEquals("b.txt\nsub/c.txt\n", r.stdout)
        }

    @Test fun count() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-c", "foo")
            assertEquals("a.txt:1\nb.txt:1\nsub/c.txt:1\n", r.stdout)
        }

    @Test fun countOmitsZero() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            // case-sensitive "bar" only in a.txt
            val r = run(fs, "/r", "grep", "-c", "bar")
            assertEquals("a.txt:1\n", r.stdout)
        }

    @Test fun wordRegexp() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes("/r/w.txt", "foobar\nfoo bar\n".encodeToByteArray())
            run(fs, "/r", "add", "w.txt")
            val r = run(fs, "/r", "grep", "-w", "foo", "w.txt")
            assertEquals("w.txt:foo bar\n", r.stdout)
        }

    @Test fun invertMatch() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-v", "foo", "a.txt")
            assertEquals("a.txt:BAR baz\na.txt:hello world\n", r.stdout)
        }

    @Test fun suppressFilename() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-h", "foo")
            assertEquals("foo bar\nfoo again\nsub foo\n", r.stdout)
        }

    @Test fun multiplePatternsOr() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-e", "foo", "-e", "world", "a.txt")
            assertEquals("a.txt:foo bar\na.txt:hello world\n", r.stdout)
        }

    @Test fun fixedStrings() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes("/r/d.txt", "a.b\naxb\n".encodeToByteArray())
            run(fs, "/r", "add", "d.txt")
            val r = run(fs, "/r", "grep", "-F", "a.b", "d.txt")
            assertEquals("d.txt:a.b\n", r.stdout)
        }

    @Test fun extendedRegexp() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "-E", "foo|world", "a.txt")
            assertEquals("a.txt:foo bar\na.txt:hello world\n", r.stdout)
        }

    @Test fun basicRegexAlternation() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            // BRE: alternation requires backslash.
            val r = run(fs, "/r", "grep", "foo\\|world", "a.txt")
            assertEquals("a.txt:foo bar\na.txt:hello world\n", r.stdout)
        }

    @Test fun basicRegexPlusLiteral() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes("/r/p.txt", "aaa\na+b\n".encodeToByteArray())
            run(fs, "/r", "add", "p.txt")
            // BRE: bare '+' is literal, so only the a+b line matches.
            val r = run(fs, "/r", "grep", "a+", "p.txt")
            assertEquals("p.txt:a+b\n", r.stdout)
        }

    @Test fun basicRegexPlusQuantifier() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes("/r/p.txt", "aaa\na+b\n".encodeToByteArray())
            run(fs, "/r", "add", "p.txt")
            // BRE: '\+' is one-or-more, matching both lines.
            val r = run(fs, "/r", "grep", "a\\+", "p.txt")
            assertEquals("p.txt:aaa\np.txt:a+b\n", r.stdout)
        }

    @Test fun treeIshPrefix() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "foo", "HEAD")
            assertEquals(
                "HEAD:a.txt:foo bar\nHEAD:b.txt:foo again\nHEAD:sub/c.txt:sub foo\n",
                r.stdout,
            )
        }

    @Test fun cachedSearchesIndex() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            // Mutate the work tree only; --cached should still see committed content.
            fs.writeBytes("/r/a.txt", "totally different\n".encodeToByteArray())
            val r = run(fs, "/r", "grep", "--cached", "foo")
            assertEquals("a.txt:foo bar\nb.txt:foo again\nsub/c.txt:sub foo\n", r.stdout)
        }

    @Test fun pathspecNarrowing() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            val r = run(fs, "/r", "grep", "foo", "--", "sub/")
            assertEquals("sub/c.txt:sub foo\n", r.stdout)
        }

    @Test fun deletedTrackedFileSkipped() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.remove("/r/a.txt")
            val r = run(fs, "/r", "grep", "foo")
            assertEquals("b.txt:foo again\nsub/c.txt:sub foo\n", r.stdout)
        }

    @Test fun binaryFileReportsMatch() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes(
                "/r/bin.dat",
                byteArrayOf('f'.code.toByte(), 'o'.code.toByte(), 'o'.code.toByte(), 0, 'x'.code.toByte()),
            )
            run(fs, "/r", "add", "bin.dat")
            val r = run(fs, "/r", "grep", "foo", "bin.dat")
            assertEquals("Binary file bin.dat matches\n", r.stdout)
        }

    @Test fun untrackedFileNotSearched() =
        runTest {
            val fs = InMemoryFs()
            seed(fs)
            fs.writeBytes("/r/untracked.txt", "foo here\n".encodeToByteArray())
            val r = run(fs, "/r", "grep", "foo")
            // untracked.txt must NOT appear.
            assertEquals("a.txt:foo bar\nb.txt:foo again\nsub/c.txt:sub foo\n", r.stdout)
        }

    @Test fun notARepository() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/nope")
            val r = run(fs, "/nope", "grep", "foo")
            assertEquals(128, r.rc)
        }
}
