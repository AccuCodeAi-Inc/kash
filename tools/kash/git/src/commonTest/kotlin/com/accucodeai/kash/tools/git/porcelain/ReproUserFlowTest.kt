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
import kotlin.test.assertTrue

/**
 * Reproduces the exact interactive flow a user reported broken:
 *  - cd to a fresh dir
 *  - `git init .` (positional `.`)
 *  - touch + echo > file
 *  - `git add -A`
 *  - `git commit -m "..."` — was producing no output and not landing
 *  - `git status` — still showed file as untracked
 */
class ReproUserFlowTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runGit(
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

    @Test fun initWithDotPositionalThenAddAndCommit() {
        val fs = InMemoryFs()
        fs.mkdirs("/home/user/the")

        // `git init .` from /home/user/the
        val initOut = runGit(fs, "/home/user/the", "init", ".")
        assertEquals(0, initOut.rc, initOut.stderr)
        // The repo materializes at /home/user/the/.git
        assertTrue(fs.exists("/home/user/the/.git/HEAD"))

        // Create a file
        runBlocking { fs.writeBytes("/home/user/the/poop", "eh\n".encodeToByteArray()) }

        // git add -A
        val addOut = runGit(fs, "/home/user/the", "add", "-A")
        assertEquals(0, addOut.rc, addOut.stderr)

        // ls-files should now show poop staged
        val ls = runGit(fs, "/home/user/the", "ls-files", "--stage")
        assertTrue("\tpoop\n" in ls.stdout, "poop not staged after add -A; ls=${ls.stdout}, err=${ls.stderr}")

        // git commit -m "eh"
        val cmOut = runGit(fs, "/home/user/the", "commit", "-m", "eh")
        assertEquals(0, cmOut.rc, "commit failed: ${cmOut.stderr}")
        assertTrue(
            cmOut.stdout.startsWith("[main "),
            "commit gave no output; stdout=${cmOut.stdout}, stderr=${cmOut.stderr}",
        )

        // git status — should be clean now
        val st = runGit(fs, "/home/user/the", "status", "--porcelain")
        assertEquals("", st.stdout, "status should be clean after commit; got '${st.stdout}'")
    }

    @Test fun nestedInitFindsInnerRepoNotParent() {
        val fs = InMemoryFs()
        // Simulate a stale .git/ at /home/user (the user's session had one)
        fs.mkdirs("/home/user/the")
        runGit(fs, "/home/user", "init")
        // Now init in the nested dir
        val initOut = runGit(fs, "/home/user/the", "init", ".")
        assertEquals(0, initOut.rc)
        assertTrue(fs.exists("/home/user/the/.git/HEAD"))

        // create + add + commit in the inner repo
        runBlocking { fs.writeBytes("/home/user/the/poop", "eh\n".encodeToByteArray()) }
        runGit(fs, "/home/user/the", "add", "-A")
        val cm = runGit(fs, "/home/user/the", "commit", "-m", "eh")
        assertEquals(0, cm.rc, "commit failed: ${cm.stderr}")
        assertTrue(cm.stdout.startsWith("[main "), "no commit confirmation: '${cm.stdout}', err='${cm.stderr}'")
        // Status clean.
        val st = runGit(fs, "/home/user/the", "status", "--porcelain")
        assertEquals("", st.stdout)
        // The outer repo at /home/user is untouched.
        val outerSt = runGit(fs, "/home/user", "status", "--porcelain").stdout
        // Nested-repo boundary: real git lists `the/` as a single
        // untracked entry, never the inner working files or .git/.
        assertTrue("poop" !in outerSt, "outer repo leaks into inner: $outerSt")
        assertTrue(".git" !in outerSt, "outer repo leaks .git: $outerSt")
        assertTrue("?? the/\n" in outerSt, "expected `?? the/` in outer status: $outerSt")
    }

    @Test fun initWithDotPositionalNormalizesPath() {
        val fs = InMemoryFs()
        fs.mkdirs("/home/user/the")
        val initOut = runGit(fs, "/home/user/the", "init", ".")
        // The "Initialized" message should NOT contain `/./` — it should be a clean path.
        assertTrue(
            "/home/user/the/.git/" in initOut.stdout && "/./" !in initOut.stdout,
            "init output has unnormalized path: ${initOut.stdout}",
        )
    }
}
