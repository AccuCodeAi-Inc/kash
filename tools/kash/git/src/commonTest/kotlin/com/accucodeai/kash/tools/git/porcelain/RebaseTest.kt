package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RebaseTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
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
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /**
     * Build a divergent history:
     *   base → m1 (main)
     *        ↘ f1 → f2 (feature)
     */
    private fun setupDivergent(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "base")

        run(fs, "/r", "branch", "feature")
        runBlocking { fs.writeBytes("/r/m", "m1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "m")
        run(fs, "/r", "commit", "-m", "m1")

        run(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/f", "f1\n".encodeToByteArray()) }
        run(fs, "/r", "add", "f")
        run(fs, "/r", "commit", "-m", "f1")
        runBlocking { fs.writeBytes("/r/f", "f2\n".encodeToByteArray()) }
        run(fs, "/r", "add", "f")
        run(fs, "/r", "commit", "-m", "f2")
    }

    @Test fun rebaseFeatureOntoMainNoConflict() {
        val fs = InMemoryFs()
        setupDivergent(fs)
        // Currently on feature.
        val r = run(fs, "/r", "rebase", "main")
        assertEquals(0, r.rc, r.stderr)
        // After rebase: feature contains both m and f.
        assertEquals("m1\n", runBlocking { fs.readBytes("/r/m").decodeToString() })
        assertEquals("f2\n", runBlocking { fs.readBytes("/r/f").decodeToString() })
        // Log should show m1 in history.
        val log = run(fs, "/r", "log", "--pretty=%s").stdout.lines().filter { it.isNotEmpty() }
        assertEquals(listOf("f2", "f1", "m1", "base"), log)
        // No rebase-merge dir left.
        assertFalse(fs.exists("/r/.git/rebase-merge"))
    }

    @Test fun rebaseWithConflictPausesAndContinues() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "base")
        run(fs, "/r", "branch", "feature")
        // main edits a.
        runBlocking { fs.writeBytes("/r/a", "MAIN\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "main")
        // feature also edits a (will conflict).
        run(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/a", "FEATURE\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "feature")

        // Rebase feature onto main → conflict pause.
        val r = run(fs, "/r", "rebase", "main")
        assertEquals(1, r.rc, r.stdout)
        assertTrue("CONFLICT" in r.stderr, r.stderr)
        assertTrue(fs.exists("/r/.git/rebase-merge"))
        assertTrue(fs.exists("/r/.git/MERGE_HEAD"))

        // Conflict markers in work tree.
        val content = runBlocking { fs.readBytes("/r/a").decodeToString() }
        assertTrue("<<<<<<< HEAD" in content, content)
        assertTrue("MAIN" in content, content)

        // Resolve and continue.
        runBlocking { fs.writeBytes("/r/a", "RESOLVED\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        val cont = run(fs, "/r", "rebase", "--continue")
        assertEquals(0, cont.rc, cont.stderr)
        assertFalse(fs.exists("/r/.git/rebase-merge"))
        assertFalse(fs.exists("/r/.git/MERGE_HEAD"))

        // Final history: feature now linear on top of main with the
        // resolved-feature commit.
        val log = run(fs, "/r", "log", "--pretty=%s").stdout.lines().filter { it.isNotEmpty() }
        assertEquals(listOf("feature", "main", "base"), log)
        assertEquals("RESOLVED\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
    }

    @Test fun rebaseAbortRestoresState() {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        run(fs, "/r", "init")
        runBlocking { fs.writeBytes("/r/a", "base\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "base")
        run(fs, "/r", "branch", "feature")
        runBlocking { fs.writeBytes("/r/a", "MAIN\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "main")
        run(fs, "/r", "switch", "feature")
        runBlocking { fs.writeBytes("/r/a", "FEATURE\n".encodeToByteArray()) }
        run(fs, "/r", "add", "a")
        run(fs, "/r", "commit", "-m", "feature")

        val origHead = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        run(fs, "/r", "rebase", "main")
        val ab = run(fs, "/r", "rebase", "--abort")
        assertEquals(0, ab.rc, ab.stderr)
        assertEquals(origHead, run(fs, "/r", "rev-parse", "HEAD").stdout.trim())
        assertEquals("FEATURE\n", runBlocking { fs.readBytes("/r/a").decodeToString() })
    }
}
