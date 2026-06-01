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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `git log -S<string>` / `-G<regex>` pickaxe selection.
 */
class LogPickaxeTest {
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

    private suspend fun commit(
        fs: InMemoryFs,
        content: String,
        msg: String,
    ): String {
        fs.writeBytes("/r/file", content.encodeToByteArray())
        run(fs, "/r", "add", "file")
        run(fs, "/r", "commit", "-m", msg)
        return run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
    }

    /**
     * c0: introduces `needle`.  c1: unrelated edit (needle count steady).
     * c2: removes `needle`.  Only c0 and c2 change its occurrence count.
     */
    private suspend fun setup(fs: InMemoryFs): Triple<String, String, String> {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        val c0 = commit(fs, "alpha\nneedle here\n", "c0 add needle")
        val c1 = commit(fs, "alpha changed\nneedle here\n", "c1 unrelated")
        val c2 = commit(fs, "alpha changed\ngone\n", "c2 drop needle")
        return Triple(c0, c1, c2)
    }

    @Test
    fun pickaxeSSelectsOccurrenceChanges() =
        runTest {
            val fs = InMemoryFs()
            val (c0, c1, c2) = setup(fs)

            val res = run(fs, "/r", "log", "--oneline", "-Sneedle")
            assertEquals(0, res.rc, res.stderr)
            val shas =
                res.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore(' ') }
            // c0 (added) and c2 (removed) flip the count; c1 keeps it at 1.
            assertTrue(shas.any { c0.startsWith(it) }, "c0 should match: ${res.stdout}")
            assertTrue(shas.any { c2.startsWith(it) }, "c2 should match: ${res.stdout}")
            assertFalse(shas.any { c1.startsWith(it) }, "c1 must not match: ${res.stdout}")
        }

    @Test
    fun pickaxeSSeparateArg() =
        runTest {
            val fs = InMemoryFs()
            val (c0, _, c2) = setup(fs)
            val res = run(fs, "/r", "log", "--oneline", "-S", "needle")
            assertEquals(0, res.rc, res.stderr)
            assertEquals(2, res.stdout.lines().count { it.isNotBlank() }, res.stdout)
        }

    @Test
    fun pickaxeGMatchesDiffLines() =
        runTest {
            val fs = InMemoryFs()
            val (c0, c1, c2) = setup(fs)

            // -G greps added/removed diff lines, so c1's "alpha changed"
            // edit (which never touches "needle") is excluded, but every
            // commit that adds or removes a line containing "needle" is in.
            val res = run(fs, "/r", "log", "--oneline", "-Gneedle")
            assertEquals(0, res.rc, res.stderr)
            val shas =
                res.stdout
                    .lines()
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore(' ') }
            assertTrue(shas.any { c0.startsWith(it) }, res.stdout)
            assertTrue(shas.any { c2.startsWith(it) }, res.stdout)
            assertFalse(shas.any { c1.startsWith(it) }, res.stdout)
        }

    @Test
    fun pickaxeSRegex() =
        runTest {
            val fs = InMemoryFs()
            setup(fs)
            val res = run(fs, "/r", "log", "--oneline", "-Sne+dle", "--pickaxe-regex")
            assertEquals(0, res.rc, res.stderr)
            assertEquals(2, res.stdout.lines().count { it.isNotBlank() }, res.stdout)
        }

    @Test
    fun pickaxeIgnoreCase() =
        runTest {
            val fs = InMemoryFs()
            setup(fs)
            // -i makes the -G regex case-insensitive, so "NEEDLE" still hits.
            val res = run(fs, "/r", "log", "--oneline", "-i", "-GNEEDLE")
            assertEquals(0, res.rc, res.stderr)
            assertEquals(2, res.stdout.lines().count { it.isNotBlank() }, res.stdout)
        }

    @Test
    fun pickaxeSAndGConflict() =
        runTest {
            val fs = InMemoryFs()
            setup(fs)
            val res = run(fs, "/r", "log", "-Sx", "-Gy")
            assertEquals(128, res.rc)
            assertTrue(res.stderr.contains("cannot use both -S and -G"), res.stderr)
        }

    @Test
    fun pickaxeRespectsPathFilter() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init")
            // Put the needle in a file we will NOT scan.
            fs.writeBytes("/r/other", "needle\n".encodeToByteArray())
            run(fs, "/r", "add", "other")
            run(fs, "/r", "commit", "-m", "needle in other")
            val res = run(fs, "/r", "log", "--oneline", "-Sneedle", "--", "file")
            assertEquals(0, res.rc, res.stderr)
            assertEquals("", res.stdout.trim(), "path filter should exclude the match: ${res.stdout}")
        }
}
