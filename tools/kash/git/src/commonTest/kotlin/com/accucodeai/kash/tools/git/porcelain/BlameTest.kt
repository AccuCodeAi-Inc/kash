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
import kotlin.test.assertTrue

class BlameTest {
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

    @Test fun blameAttributesEachLineToTheCommitThatTouchedIt() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            // Commit 1: a, b, c
            fs.writeBytes("/r/f", "a\nb\nc\n".encodeToByteArray())
            run(fs, "/r", "add", "f")
            run(fs, "/r", "commit", "-m", "c1")
            val c1 = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

            // Commit 2: modify line 2 from b → B
            fs.writeBytes("/r/f", "a\nB\nc\n".encodeToByteArray())
            run(fs, "/r", "add", "f")
            run(fs, "/r", "commit", "-m", "c2")
            val c2 = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

            // Commit 3: add a new line "d" at the end
            fs.writeBytes("/r/f", "a\nB\nc\nd\n".encodeToByteArray())
            run(fs, "/r", "add", "f")
            run(fs, "/r", "commit", "-m", "c3")
            val c3 = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

            val out = run(fs, "/r", "blame", "f")
            assertEquals(0, out.rc, out.stderr)
            val lines = out.stdout.lines().filter { it.isNotEmpty() }
            assertEquals(4, lines.size, lines.toString())
            // Line 1 "a" → c1; line 2 "B" → c2; line 3 "c" → c1; line 4 "d" → c3.
            assertTrue(lines[0].startsWith(c1.substring(0, 8)), lines[0])
            assertTrue(lines[1].startsWith(c2.substring(0, 8)), lines[1])
            assertTrue(lines[2].startsWith(c1.substring(0, 8)), lines[2])
            assertTrue(lines[3].startsWith(c3.substring(0, 8)), lines[3])
            // Line 1 ends with " a"
            assertTrue(lines[0].endsWith(" a"), lines[0])
            // Line 4 ends with " d"
            assertTrue(lines[3].endsWith(" d"), lines[3])
        }

    @Test fun blameOfMissingFileErrors() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/f", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "f")
            run(fs, "/r", "commit", "-m", "x")
            val out = run(fs, "/r", "blame", "nonesuch")
            assertEquals(128, out.rc)
            assertTrue("no such path" in out.stderr, out.stderr)
        }

    @Test fun blameOnSingleCommitAttributesAllLinesToIt() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            fs.writeBytes("/r/f", "x\ny\nz\n".encodeToByteArray())
            run(fs, "/r", "add", "f")
            run(fs, "/r", "commit", "-m", "single")
            val sha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
            val out = run(fs, "/r", "blame", "f")
            val lines = out.stdout.lines().filter { it.isNotEmpty() }
            assertEquals(3, lines.size)
            for (l in lines) assertTrue(l.startsWith(sha.substring(0, 8)), l)
        }
}
