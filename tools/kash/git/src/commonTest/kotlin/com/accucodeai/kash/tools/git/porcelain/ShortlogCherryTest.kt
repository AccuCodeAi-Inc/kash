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
 * Behavioral tests for `git shortlog`, `git cherry`, and
 * `git whatchanged` against an in-memory VFS. Real-git differential
 * coverage lives in `jvmTest/.../porcelain/ShortlogCherryDifferentialTest.kt`.
 */
class ShortlogCherryTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun run(
        fs: InMemoryFs,
        cwd: String,
        env: MutableMap<String, String>,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env,
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private fun envFor(
        name: String,
        email: String,
    ): MutableMap<String, String> =
        mutableMapOf(
            "GIT_AUTHOR_NAME" to name,
            "GIT_AUTHOR_EMAIL" to email,
            "GIT_AUTHOR_DATE" to "1700000000 +0000",
            "GIT_COMMITTER_NAME" to name,
            "GIT_COMMITTER_EMAIL" to email,
            "GIT_COMMITTER_DATE" to "1700000000 +0000",
        )

    private suspend fun commitAs(
        fs: InMemoryFs,
        cwd: String,
        name: String,
        email: String,
        path: String,
        content: String,
        msg: String,
    ) {
        fs.writeBytes("$cwd/$path", content.encodeToByteArray())
        val env = envFor(name, email)
        run(fs, cwd, env, "add", path)
        run(fs, cwd, env, "commit", "-m", msg)
    }

    @Test fun shortlogGroupsByAuthorAlphabetically() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", envFor("X", "x@x"), "init")
            commitAs(fs, "/r", "Alice", "alice@x.com", "f", "a\n", "first commit")
            commitAs(fs, "/r", "Alice", "alice@x.com", "f", "a\nb\n", "second commit")
            commitAs(fs, "/r", "Bob", "bob@y.com", "f", "a\nb\nc\n", "third commit")

            val out = run(fs, "/r", envFor("X", "x@x"), "shortlog").stdout
            assertEquals(
                "Alice (2):\n" +
                    "      first commit\n" +
                    "      second commit\n" +
                    "\n" +
                    "Bob (1):\n" +
                    "      third commit\n" +
                    "\n",
                out,
            )
        }

    @Test fun shortlogSummaryAndNumberedAndEmail() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", envFor("X", "x@x"), "init")
            commitAs(fs, "/r", "Bob", "bob@y.com", "f", "a\n", "c1")
            commitAs(fs, "/r", "Alice", "alice@x.com", "f", "a\nb\n", "c2")
            commitAs(fs, "/r", "Alice", "alice@x.com", "f", "a\nb\nc\n", "c3")

            // -s alphabetical (Alice before Bob), padded count width 6.
            assertEquals(
                "     2\tAlice\n     1\tBob\n",
                run(fs, "/r", envFor("X", "x@x"), "shortlog", "-s").stdout,
            )
            // -sn: descending by count (Alice 2, Bob 1) — same here.
            assertEquals(
                "     2\tAlice\n     1\tBob\n",
                run(fs, "/r", envFor("X", "x@x"), "shortlog", "-sn").stdout,
            )
            // -se appends email.
            assertEquals(
                "     2\tAlice <alice@x.com>\n     1\tBob <bob@y.com>\n",
                run(fs, "/r", envFor("X", "x@x"), "shortlog", "-se").stdout,
            )
        }

    @Test fun shortlogNumberedTieBrokenByName() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            run(fs, "/r", envFor("X", "x@x"), "init")
            commitAs(fs, "/r", "Zoe", "z@x", "f", "a\n", "c1")
            commitAs(fs, "/r", "Amy", "a@x", "f", "a\nb\n", "c2")
            // Tie (1 each): -n breaks ties by case-insensitive name.
            assertEquals(
                "     1\tAmy\n     1\tZoe\n",
                run(fs, "/r", envFor("X", "x@x"), "shortlog", "-sn").stdout,
            )
        }

    @Test fun cherryClassifiesPlusAndMinusByPatchId() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            val baseEnv = envFor("U", "u@x")
            run(fs, "/r", baseEnv, "init")
            // base commit on main
            commitAs(fs, "/r", "U", "u@x", "f", "l1\n", "base")
            // topic branch: add l2, add l3
            run(fs, "/r", baseEnv, "switch", "-c", "topic")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\nl2\n", "add l2")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\nl2\nl3\n", "add l3")
            // back to main, unrelated commit so cherry-pick gets a new sha
            run(fs, "/r", baseEnv, "switch", "main")
            commitAs(fs, "/r", "U", "u@x", "g", "other\n", "main other")
            // cherry-pick the "add l2" patch onto main (different sha, same patch)
            val pick = run(fs, "/r", baseEnv, "cherry-pick", "topic~1")
            assertEquals(0, pick.rc, "cherry-pick stderr: ${pick.stderr}")

            val out = run(fs, "/r", baseEnv, "cherry", "-v", "main", "topic").stdout
            val lines = out.trimEnd('\n').split('\n')
            assertEquals(2, lines.size, "cherry output: $out")
            // add l2 has an equivalent upstream -> '-'; add l3 is new -> '+'.
            assertTrue(lines.any { it.startsWith("- ") && it.endsWith("add l2") }, "expected '- ... add l2' in $out")
            assertTrue(lines.any { it.startsWith("+ ") && it.endsWith("add l3") }, "expected '+ ... add l3' in $out")
        }

    @Test fun patchIdStableAcrossEquivalentDiffs() =
        runTest {
            // Two commits that introduce the identical change at the same
            // position (different parent shas) must share a patch-id.
            val fs = InMemoryFs()
            fs.mkdirs("/a")
            fs.mkdirs("/b")
            val env = envFor("U", "u@x")
            run(fs, "/a", env, "init")
            run(fs, "/b", env, "init")
            commitAs(fs, "/a", "U", "u@x", "f", "x\n", "base a")
            commitAs(fs, "/b", "U", "u@x", "f", "x\n", "base b different message")
            commitAs(fs, "/a", "U", "u@x", "f", "x\ny\n", "change")
            commitAs(fs, "/b", "U", "u@x", "f", "x\ny\n", "change")

            val repoA =
                com.accucodeai.kash.tools.git.GitRepo
                    .open(fs, "/a")
            val repoB =
                com.accucodeai.kash.tools.git.GitRepo
                    .open(fs, "/b")
            val headA = repoA.refs.resolveHead()!!
            val headB = repoB.refs.resolveHead()!!
            assertEquals(
                computePatchId(repoA, headA),
                computePatchId(repoB, headB),
                "equivalent diffs should share a patch-id",
            )
        }

    @Test fun whatchangedDefaultEmitsRawLines() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            val env = envFor("U", "u@x")
            run(fs, "/r", env, "init")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\n", "base")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\nl2\n", "add l2")

            val out = run(fs, "/r", env, "whatchanged", "-n", "1").stdout
            assertTrue(out.contains("commit "), "expected commit header: $out")
            assertTrue(out.contains("    add l2"), "expected message: $out")
            // raw line: ":100644 100644 <7> <7> M\tf"
            assertTrue(
                out.lines().any { it.matches(Regex(":100644 100644 [0-9a-f]{7} [0-9a-f]{7} M\tf")) },
                "expected raw M line: $out",
            )
        }

    @Test fun whatchangedPatchMatchesLogPatch() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/r")
            val env = envFor("U", "u@x")
            run(fs, "/r", env, "init")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\n", "base")
            commitAs(fs, "/r", "U", "u@x", "f", "l1\nl2\n", "add l2")

            val wc = run(fs, "/r", env, "whatchanged", "-p", "-n", "1").stdout
            assertTrue(wc.contains("diff --git a/f b/f"), "expected patch: $wc")
            assertTrue(wc.contains("+l2"), "expected added line: $wc")
        }
}
