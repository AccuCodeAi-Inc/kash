package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitPushApplier
import com.accucodeai.kash.api.git.GitPushOutcome
import com.accucodeai.kash.api.git.GitPushRequest
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.seed.materializeSeed
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PushTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun runGit(
        adapter: GitHostAdapter?,
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
        val res = GitCommand(adapter).run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private class CapturingApplier : GitPushApplier {
        var lastRequest: GitPushRequest? = null
        var outcome: GitPushOutcome = GitPushOutcome.Accepted("")

        override suspend fun apply(req: GitPushRequest): GitPushOutcome {
            lastRequest = req
            // Default to "host accepts the supplied tip as-is."
            return if (outcome is GitPushOutcome.Accepted && (outcome as GitPushOutcome.Accepted).newTipSha.isEmpty()) {
                GitPushOutcome.Accepted(req.tipSha)
            } else {
                outcome
            }
        }
    }

    private suspend fun adapterWithApplier(
        applier: GitPushApplier,
        resolver: GitObjectResolver = GitObjectResolver { null },
    ): GitHostAdapter =
        object : GitHostAdapter {
            override val repoSeed: GitRepoSeed =
                GitRepoSeed.OnDemand(
                    horizon =
                        GitRepoSeed.Synthetic(
                            workTree = mapOf("README" to "start\n".encodeToByteArray()),
                        ),
                    resolver = resolver,
                    pushApplier = applier,
                )
            override val identity: GitIdentity = GitIdentity("T", "t@e")
            override val workTreePath: String = "/work"
        }

    @Test fun pushSendsNewCommitsToApplier() =
        runTest {
            val applier = CapturingApplier()
            val adapter = adapterWithApplier(applier)
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            // Make a local commit on main.
            fs.writeBytes("/work/README", "edited\n".encodeToByteArray())
            runGit(adapter, fs, "/work", "add", "README")
            runGit(adapter, fs, "/work", "commit", "-m", "local edit")

            val localTip = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()
            val push = runGit(adapter, fs, "/work", "push")
            assertEquals(0, push.rc, push.stderr)
            assertTrue(push.stdout.contains("main -> main"), "stdout: ${push.stdout}")

            // Applier saw a single commit, oldest-first, with all required objects.
            val req = assertNotNull(applier.lastRequest, "applier never called")
            assertEquals("main", req.branch)
            assertEquals(localTip, req.tipSha)
            assertEquals(1, req.newCommits.size)
            val bundle = req.newCommits[0]
            assertEquals(localTip, bundle.commitSha)
            assertEquals(1, bundle.trees.size)
            assertEquals(1, bundle.blobs.size)

            // Tracking ref advanced.
            val tracking = runGit(adapter, fs, "/work", "rev-parse", "refs/remotes/origin/main").stdout.trim()
            assertEquals(localTip, tracking)
        }

    @Test fun pushUpToDateSkipsApplier() =
        runTest {
            val applier = CapturingApplier()
            val adapter = adapterWithApplier(applier)
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            val out = runGit(adapter, fs, "/work", "push")
            assertEquals(0, out.rc)
            assertEquals("Everything up-to-date\n", out.stdout)
            assertEquals(null, applier.lastRequest)
        }

    @Test fun pushTwoCommitsBundlesBothOldestFirst() =
        runTest {
            val applier = CapturingApplier()
            val adapter = adapterWithApplier(applier)
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            fs.writeBytes("/work/README", "v2\n".encodeToByteArray())
            runGit(adapter, fs, "/work", "add", "README")
            runGit(adapter, fs, "/work", "commit", "-m", "v2")
            val firstSha = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()

            fs.writeBytes("/work/README", "v3\n".encodeToByteArray())
            runGit(adapter, fs, "/work", "add", "README")
            runGit(adapter, fs, "/work", "commit", "-m", "v3")
            val secondSha = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()

            runGit(adapter, fs, "/work", "push")
            val req = assertNotNull(applier.lastRequest)
            assertEquals(2, req.newCommits.size)
            // Oldest first: v2 then v3.
            assertEquals(firstSha, req.newCommits[0].commitSha)
            assertEquals(secondSha, req.newCommits[1].commitSha)
        }

    @Test fun pushRejectedSurfacesError() =
        runTest {
            val applier = CapturingApplier()
            applier.outcome = GitPushOutcome.Rejected("non-fast-forward")
            val adapter = adapterWithApplier(applier)
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            fs.writeBytes("/work/README", "edit\n".encodeToByteArray())
            runGit(adapter, fs, "/work", "add", "README")
            runGit(adapter, fs, "/work", "commit", "-m", "edit")
            val out = runGit(adapter, fs, "/work", "push")
            assertEquals(1, out.rc)
            assertTrue("[rejected]" in out.stderr, out.stderr)
            assertTrue("non-fast-forward" in out.stderr, out.stderr)
        }

    @Test fun pushWithoutApplierFailsWithNoRemote() =
        runTest {
            // Local-mode session (no adapter at all).
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            runGit(null, fs, "/work", "init")
            fs.writeBytes("/work/a", "x".encodeToByteArray())
            runGit(null, fs, "/work", "add", "a")
            runGit(null, fs, "/work", "commit", "-m", "x")
            val out = runGit(null, fs, "/work", "push")
            assertEquals(128, out.rc)
            assertTrue("no remote configured" in out.stderr, out.stderr)
        }
}
