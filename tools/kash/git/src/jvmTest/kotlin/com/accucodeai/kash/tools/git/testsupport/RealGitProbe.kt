package com.accucodeai.kash.tools.git.testsupport

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Driver for the real `git` CLI on the test host. Differential tests
 * (`kash git X` vs `/usr/bin/git X` against the same input) lean on
 * this. The probe is **skip-if-missing** — a CI runner without git
 * installed should not fail; tests using `RealGitProbe.assumeAvailable()`
 * will be marked skipped instead.
 *
 * Determinism levers worth setting in every invocation:
 *  - `GIT_AUTHOR_NAME` / `GIT_AUTHOR_EMAIL` / `GIT_AUTHOR_DATE`
 *  - `GIT_COMMITTER_NAME` / `GIT_COMMITTER_EMAIL` / `GIT_COMMITTER_DATE`
 *  - `GIT_CONFIG_GLOBAL=/dev/null` `GIT_CONFIG_SYSTEM=/dev/null`
 *    — keep the developer's `~/.gitconfig` (gpgsign, signing key,
 *    `init.defaultBranch`, …) from leaking in.
 *
 * [run] sets all of those for you unless you override via [env].
 */
public class RealGitProbe(
    private val gitExecutable: String = locate(),
) {
    public data class Result(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    ) {
        public fun stdoutUtf8(): String = stdout.decodeToString()

        public fun stderrUtf8(): String = stderr.decodeToString()
    }

    public fun version(): String = run(listOf("--version"), cwd = File("."), env = emptyMap()).stdoutUtf8().trim()

    public fun run(
        args: List<String>,
        cwd: File,
        env: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
        timeoutSeconds: Long = 30,
    ): Result {
        val cmd = listOf(gitExecutable) + args
        val pb = ProcessBuilder(cmd).directory(cwd)
        val merged = pb.environment()
        merged.clear()
        // Minimal hermetic environment — anything the host needs must be
        // passed explicitly.
        merged["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
        merged["HOME"] = System.getProperty("java.io.tmpdir")
        merged["LC_ALL"] = "C"
        merged["TZ"] = "UTC"
        merged["GIT_CONFIG_GLOBAL"] = "/dev/null"
        merged["GIT_CONFIG_SYSTEM"] = "/dev/null"
        merged["GIT_TERMINAL_PROMPT"] = "0"
        // Sensible identity + dates default. Override via [env] to test
        // time-/identity-sensitive scenarios.
        merged["GIT_AUTHOR_NAME"] = "Test User"
        merged["GIT_AUTHOR_EMAIL"] = "test@example.com"
        merged["GIT_AUTHOR_DATE"] = "1700000000 +0000"
        merged["GIT_COMMITTER_NAME"] = "Test User"
        merged["GIT_COMMITTER_EMAIL"] = "test@example.com"
        merged["GIT_COMMITTER_DATE"] = "1700000000 +0000"
        merged.putAll(env)

        pb.redirectErrorStream(false)
        val proc = pb.start()
        if (stdin != null) {
            proc.outputStream.use { it.write(stdin) }
        } else {
            proc.outputStream.close()
        }
        val out = proc.inputStream.readAllBytes()
        val err = proc.errorStream.readAllBytes()
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            error("real-git probe timed out: $cmd")
        }
        return Result(proc.exitValue(), out, err)
    }

    /**
     * Create a fresh temp directory and `git init` it with the supplied
     * default-branch name. Returns the directory; the caller is
     * responsible for cleanup.
     */
    public fun freshRepo(defaultBranch: String = "main"): File {
        val dir = Files.createTempDirectory("kash-git-probe-").toFile()
        run(listOf("init", "-q", "-b", defaultBranch), cwd = dir, env = emptyMap())
            .also { check(it.exitCode == 0) { "git init failed: ${it.stderrUtf8()}" } }
        return dir
    }

    public companion object {
        /** Cached availability check — `null` until [available] runs. */
        private var cachedAvailable: Boolean? = null

        /**
         * `true` iff `git` is on `PATH` and runs. Probes only once per
         * process — tests that need real git should call this in their
         * assume-block.
         */
        public fun available(): Boolean {
            cachedAvailable?.let { return it }
            val result =
                try {
                    val p = ProcessBuilder("git", "--version").redirectErrorStream(true).start()
                    p.inputStream.readAllBytes()
                    p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
                } catch (e: Throwable) {
                    false
                }
            cachedAvailable = result
            return result
        }

        /** Use in tests that require git; throws `TestAbortedException` if missing. */
        public fun assumeAvailable() {
            org.junit.jupiter.api.Assumptions
                .assumeTrue(available(), "real `git` not on PATH")
        }

        private fun locate(): String {
            // Honor an explicit override; otherwise rely on PATH.
            return System.getenv("KASH_REAL_GIT") ?: "git"
        }
    }
}
