package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.ShellInvocation
import com.accucodeai.kash.api.ShellRunner
import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.PreCommitRequest
import com.accucodeai.kash.api.git.PreCommitResult
import com.accucodeai.kash.api.git.PreCommitValidator
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
 * Covers both pre-commit gates:
 *  1. `.git/hooks/pre-commit` — repo-level shell script, run through
 *     the shellRunner. Bypassable with `--no-verify`.
 *  2. `adapter.preCommitValidator` — host-side, run by the runtime.
 *     **Unbypassable** by design — `--no-verify` doesn't skip it.
 *
 * The host gets to gate both layers: it can ship the repo hook inside
 * the seed (via `Synthetic.hooks`) AND wire a host-side validator on
 * the adapter. Both must pass for a commit to land.
 */
class PreCommitHookTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private class CapturingRunner(
        private val exit: Int,
        private val onStderr: String? = null,
    ) : ShellRunner {
        var lastScript: String? = null
        var callCount: Int = 0

        override suspend fun run(invocation: ShellInvocation): Int {
            callCount++
            lastScript = invocation.script
            if (onStderr != null) {
                onStderr.encodeToByteArray().let { bytes ->
                    val buf = kotlinx.io.Buffer()
                    buf.write(bytes)
                    invocation.stderr?.write(buf, buf.size)
                    invocation.stderr?.flush()
                }
            }
            return exit
        }
    }

    private suspend fun runGit(
        adapter: GitHostAdapter?,
        fs: InMemoryFs,
        cwd: String,
        runner: ShellRunner?,
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
                shellRunner = runner,
            )
        val res = GitCommand(adapter).run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private suspend fun seedHookRepo(
        fs: InMemoryFs,
        runner: ShellRunner?,
        hookScript: String,
    ) {
        fs.mkdirs("/r")
        runGit(null, fs, "/r", runner, "init")
        fs.mkdirs("/r/.git/hooks")
        // Mode 0o755 so isExecutable() returns true.
        fs.writeBytes(
            "/r/.git/hooks/pre-commit",
            hookScript.encodeToByteArray(),
            mode = 0b111_101_101,
        )
        fs.writeBytes("/r/f", "x\n".encodeToByteArray())
        runGit(null, fs, "/r", runner, "add", "f")
    }

    @Test fun passingHookAllowsCommit() =
        runTest {
            val runner = CapturingRunner(exit = 0)
            val fs = InMemoryFs()
            seedHookRepo(fs, runner, "echo ok\n")
            val out = runGit(null, fs, "/r", runner, "commit", "-m", "first")
            assertEquals(0, out.rc, out.stderr)
            assertEquals(1, runner.callCount)
            assertTrue(runner.lastScript!!.contains("echo ok"))
        }

    @Test fun failingHookAbortsCommit() =
        runTest {
            val runner = CapturingRunner(exit = 1, onStderr = "hook says no\n")
            val fs = InMemoryFs()
            seedHookRepo(fs, runner, "exit 1\n")
            val out = runGit(null, fs, "/r", runner, "commit", "-m", "should fail")
            assertEquals(1, out.rc)
            assertTrue("pre-commit hook failed" in out.stderr, out.stderr)
            assertTrue("hook says no" in out.stderr, out.stderr)
            // No new commit landed: HEAD is still unset.
            val log = runGit(null, fs, "/r", runner, "log")
            assertTrue("no commits" in log.stderr || "does not have any commits" in log.stderr, log.stderr)
        }

    @Test fun noVerifyBypassesRepoHook() =
        runTest {
            val runner = CapturingRunner(exit = 1)
            val fs = InMemoryFs()
            seedHookRepo(fs, runner, "exit 1\n")
            val out = runGit(null, fs, "/r", runner, "commit", "-m", "skip hook", "--no-verify")
            assertEquals(0, out.rc, out.stderr)
            assertEquals(0, runner.callCount, "hook should not have been invoked with --no-verify")
        }

    @Test fun missingShellRunnerWarnsButDoesntCrash() =
        runTest {
            val fs = InMemoryFs()
            seedHookRepo(fs, runner = null, hookScript = "echo whatever\n")
            val out = runGit(null, fs, "/r", null, "commit", "-m", "no runner")
            // Commit succeeds because there's no runner to enforce the hook.
            assertEquals(0, out.rc, out.stderr)
            assertTrue("no shellRunner available" in out.stderr, out.stderr)
        }

    @Test fun hostValidatorRejectionAbortsCommitEvenWithoutHook() =
        runTest {
            val rejecting =
                PreCommitValidator { _: PreCommitRequest ->
                    PreCommitResult.Reject("must include tests", listOf("see CONTRIBUTING.md"))
                }
            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed = GitRepoSeed.Empty
                    override val identity: GitIdentity = GitIdentity("T", "t@e")
                    override val workTreePath: String = "/r"
                    override val preCommitValidator: PreCommitValidator = rejecting
                }
            val fs = InMemoryFs()
            com.accucodeai.kash.tools.git.seed
                .materializeSeed(adapter, fs)
            fs.writeBytes("/r/f", "x\n".encodeToByteArray())
            runGit(adapter, fs, "/r", null, "add", "f")
            val out = runGit(adapter, fs, "/r", null, "commit", "-m", "should reject")
            assertEquals(1, out.rc)
            assertTrue("must include tests" in out.stderr, out.stderr)
            assertTrue("see CONTRIBUTING.md" in out.stderr, out.stderr)
        }

    @Test fun noVerifyDoesNotBypassHostValidator() =
        runTest {
            val rejecting =
                PreCommitValidator { _: PreCommitRequest -> PreCommitResult.Reject("nope") }
            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed = GitRepoSeed.Empty
                    override val identity: GitIdentity = GitIdentity("T", "t@e")
                    override val workTreePath: String = "/r"
                    override val preCommitValidator: PreCommitValidator = rejecting
                }
            val fs = InMemoryFs()
            com.accucodeai.kash.tools.git.seed
                .materializeSeed(adapter, fs)
            fs.writeBytes("/r/f", "x\n".encodeToByteArray())
            runGit(adapter, fs, "/r", null, "add", "f")
            val out = runGit(adapter, fs, "/r", null, "commit", "-m", "try to bypass", "--no-verify")
            // Host validator still runs and rejects.
            assertEquals(1, out.rc)
            assertTrue("nope" in out.stderr, out.stderr)
        }

    @Test fun bothGatesPassedCommitProceeds() =
        runTest {
            val accepting =
                PreCommitValidator { _: PreCommitRequest -> PreCommitResult.Accept }
            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed = GitRepoSeed.Empty
                    override val identity: GitIdentity = GitIdentity("T", "t@e")
                    override val workTreePath: String = "/r"
                    override val preCommitValidator: PreCommitValidator = accepting
                }
            val runner = CapturingRunner(exit = 0)
            val fs = InMemoryFs()
            com.accucodeai.kash.tools.git.seed
                .materializeSeed(adapter, fs)
            fs.mkdirs("/r/.git/hooks")
            fs.writeBytes(
                "/r/.git/hooks/pre-commit",
                "echo allow\n".encodeToByteArray(),
                mode = 0b111_101_101,
            )
            fs.writeBytes("/r/f", "ok\n".encodeToByteArray())
            runGit(adapter, fs, "/r", runner, "add", "f")
            val out = runGit(adapter, fs, "/r", runner, "commit", "-m", "both pass")
            assertEquals(0, out.rc, out.stderr)
            assertEquals(1, runner.callCount, "repo hook should have been invoked")
        }
}
