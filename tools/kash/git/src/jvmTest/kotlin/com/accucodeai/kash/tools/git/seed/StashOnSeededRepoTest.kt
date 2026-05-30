package com.accucodeai.kash.tools.git.seed

import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `git stash` push → pop on a [GitRepoSeed.Synthetic]-seeded repo. Regression
 * guard tied to the index fix: stash diffs HEAD vs index vs work tree, so
 * without a seeded `.git/index` it misbehaves.
 */
class StashOnSeededRepoTest {
    private val identity = GitIdentity("Test User", "test@example.com")

    private suspend fun git(
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
        val res = GitCommand().run(args.toList(), ctx)
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    @Test fun stashPushPopRoundTripsOnSyntheticSeed() =
        runTest {
            val fs = InMemoryFs()
            val layout = RepoLayout("/repo")
            SyntheticMaterializer(layout, fs, identity).materialize(
                GitRepoSeed.Synthetic(
                    workTree = mapOf("src/config.json" to "{\n  \"name\": \"v1\"\n}".encodeToByteArray()),
                    head = "refs/heads/feature",
                ),
            )

            // Edit the tracked file, then stash.
            fs.writeBytes("/repo/src/config.json", "{\n  \"name\": \"v2\"\n}".encodeToByteArray())
            val push = git(fs, "/repo", "stash", "push", "-m", "wip")
            assertEquals(0, push.first, "stash push failed: ${push.third}")

            // Work tree is back to HEAD and clean.
            assertEquals("{\n  \"name\": \"v1\"\n}", fs.readBytes("/repo/src/config.json").decodeToString())
            assertEquals(
                "",
                git(fs, "/repo", "status", "--porcelain").second.trim(),
                "tree should be clean after stash",
            )

            // Pop restores the edit.
            val pop = git(fs, "/repo", "stash", "pop")
            assertEquals(0, pop.first, "stash pop failed: ${pop.third}")
            assertEquals("{\n  \"name\": \"v2\"\n}", fs.readBytes("/repo/src/config.json").decodeToString())
            assertTrue(git(fs, "/repo", "status", "--porcelain").second.contains("src/config.json"))
        }
}
