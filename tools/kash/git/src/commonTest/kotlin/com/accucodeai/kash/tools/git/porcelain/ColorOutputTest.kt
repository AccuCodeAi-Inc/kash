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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Color is opt-in (kash house rule, docs/TOOLS.md): a bare `git diff` /
 * `git status` emits zero ANSI bytes, while `--color=always` turns on git's
 * default palette. These pin both ends so color can never silently leak into
 * scripted/LLM output.
 */
class ColorOutputTest {
    private val esc = "["

    private suspend fun run(
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
        GitCommand().run(args.toList(), ctx)
        err.readString()
        return out.readString()
    }

    private suspend fun seedModified(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init", "-q")
        fs.writeBytes("/r/f.txt", "one\n".encodeToByteArray())
        run(fs, "/r", "add", "f.txt")
        run(fs, "/r", "commit", "-m", "init")
        fs.writeBytes("/r/f.txt", "two\n".encodeToByteArray())
    }

    @Test fun diffPlainHasNoAnsi() =
        runTest {
            val fs = InMemoryFs()
            seedModified(fs)
            assertFalse(esc in run(fs, "/r", "diff"), "bare git diff must be plain")
            assertFalse(esc in run(fs, "/r", "diff", "--no-color"), "--no-color must be plain")
            assertFalse(esc in run(fs, "/r", "diff", "--color=never"), "--color=never must be plain")
        }

    @Test fun diffColorAlwaysHasGitPalette() =
        runTest {
            val fs = InMemoryFs()
            seedModified(fs)
            val out = run(fs, "/r", "diff", "--color=always")
            assertTrue(esc in out, "--color=always must emit ANSI: $out")
            assertTrue("[36m" in out, "hunk header should be cyan") // @@ frag
            assertTrue("[32m" in out, "added line should be green") // +two
            assertTrue("[31m" in out, "removed line should be red") // -one
        }

    @Test fun dashCColorUiOverrideEnablesColor() =
        runTest {
            val fs = InMemoryFs()
            seedModified(fs)
            // `git -c color.ui=always diff` — the form the kashrc alias uses
            // (with =auto), here forced so it's tty-independent in the test.
            val out = run(fs, "/r", "-c", "color.ui=always", "diff")
            assertTrue("[32m" in out, "-c color.ui=always must colorize: $out")
            // An explicit --no-color still wins over the -c override.
            assertFalse(esc in run(fs, "/r", "-c", "color.ui=always", "diff", "--no-color"))
        }

    @Test fun statusPlainVsColor() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", "init", "-q")
            fs.writeBytes("/r/staged.txt", "s\n".encodeToByteArray())
            run(fs, "/r", "add", "staged.txt")
            fs.writeBytes("/r/loose.txt", "u\n".encodeToByteArray())

            assertFalse(esc in run(fs, "/r", "status"), "bare git status must be plain")
            val colored = run(fs, "/r", "status", "--color=always")
            assertTrue("[32m" in colored, "staged entry should be green: $colored")
            assertTrue("[31m" in colored, "untracked entry should be red: $colored")
        }
}
