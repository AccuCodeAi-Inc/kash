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

/**
 * `git diff --name-status` and `git status --untracked-files[=mode]` / `-u<mode>`.
 *
 * Both are common, scriptable porcelain flags that were previously hard-rejected
 * with `exit 129`. These cover the supported modes plus the error path.
 */
class NameStatusUntrackedModeTest {
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

    private suspend fun setupRepo(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
    }

    @Test fun diffNameStatusReportsAddModifyDelete() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            fs.writeBytes("/r/keep.txt", "v1\n".encodeToByteArray())
            fs.writeBytes("/r/gone.txt", "bye\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "base")

            // Modify keep, delete gone, add fresh.
            fs.writeBytes("/r/keep.txt", "v2\n".encodeToByteArray())
            fs.remove("/r/gone.txt")
            fs.writeBytes("/r/fresh.txt", "new\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")

            val out = run(fs, "/r", "diff", "--cached", "--name-status")
            assertEquals(0, out.rc, out.stderr)
            val lines =
                out.stdout
                    .trim()
                    .lines()
                    .toSet()
            assertEquals(setOf("M\tkeep.txt", "D\tgone.txt", "A\tfresh.txt"), lines, "got:\n${out.stdout}")
        }

    @Test fun statusUntrackedFilesModeNoSuppressesUntracked() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            fs.writeBytes("/r/tracked.txt", "x\n".encodeToByteArray())
            run(fs, "/r", "add", "-A")
            run(fs, "/r", "commit", "-m", "base")
            fs.writeBytes("/r/loose.txt", "y\n".encodeToByteArray())

            // Default + -uall + --untracked-files=all show the untracked file.
            assertTrue(run(fs, "/r", "status", "--porcelain").stdout.contains("?? loose.txt"))
            assertTrue(run(fs, "/r", "status", "--porcelain", "-uall").stdout.contains("?? loose.txt"))
            assertTrue(run(fs, "/r", "status", "--porcelain", "--untracked-files=all").stdout.contains("?? loose.txt"))

            // -uno / --untracked-files=no hide it (and the tree reads clean).
            val no = run(fs, "/r", "status", "--porcelain", "-uno")
            assertEquals(0, no.rc, no.stderr)
            assertEquals("", no.stdout.trim(), "expected no output with -uno, got:\n${no.stdout}")
            assertEquals("", run(fs, "/r", "status", "--porcelain", "--untracked-files=no").stdout.trim())
        }

    @Test fun statusInvalidUntrackedModeRejected() =
        runTest {
            val fs = InMemoryFs()
            setupRepo(fs)
            val out = run(fs, "/r", "status", "--untracked-files=bogus")
            assertEquals(129, out.rc)
            assertTrue(out.stderr.contains("invalid untracked-files mode"), out.stderr)
        }
}
