package com.accucodeai.kash

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Regression test for the `claude-with-kash` hang.
 *
 * Background: `claude-with-kash` symlinks `bin/kash` as `bash` and
 * prepends that directory to `PATH`. When kash startup calls into
 * JGit (eagerly building a `FileRepository`), JGit's
 * `FS_POSIX.discoverGitExe` shells out to `bash --login -c which git`
 * — which now resolves to kash, which calls JGit again, which spawns
 * another kash, …
 *
 * Fix is two-pronged: (a) the lazy `LazyGitHostAdapter` in
 * [com.accucodeai.kash.app.AppStandardRegistry] defers JGit init
 * until the first git operation; (b)
 * [com.accucodeai.kash.tools.git.jgit.JGitFactory] installs a
 * `SystemReader` that bypasses host-`git` discovery + hands a
 * `NoDiscoveryFS` to the `FileRepositoryBuilder`.
 *
 * This test exercises the (a) seam by running `echo hi` (no git)
 * through kash with the shim on PATH. If JGit init ever leaks
 * back into startup, the recursion times this test out.
 */
class ShimOnPathNoHangTest {
    @Test
    fun bashShimOnPath_kashEchoHi_doesNotRecurse() {
        // Gradle runs tests with cwd = module dir (kash-app/), so walk up
        // until we find the repo-root `bin/kash` launcher script.
        val installedKash =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "bin/kash") }
                .firstOrNull { it.canExecute() }
                ?: throw AssertionError("bin/kash not found — run :kash-app:installJvmDist first")

        val shim = Files.createTempDirectory("kash-shim-test").toFile()
        try {
            val bashLink = File(shim, "bash")
            Files.createSymbolicLink(bashLink.toPath(), installedKash.toPath())
            val pathPrefix = shim.absolutePath
            val parentPath = System.getenv("PATH") ?: ""

            val proc =
                ProcessBuilder(bashLink.absolutePath, "-c", "echo hi")
                    .redirectErrorStream(true)
                    .also { it.environment()["PATH"] = "$pathPrefix:$parentPath" }
                    .start()
            // Close stdin so a hang-on-fd0 bug surfaces immediately
            proc.outputStream.close()

            val completed = proc.waitFor(30, TimeUnit.SECONDS)
            if (!completed) {
                proc.destroyForcibly()
                throw AssertionError(
                    "kash hung when invoked as bash-on-PATH within 30s — " +
                        "claude-with-kash recursion regression (see ShimOnPathNoHangTest doc).",
                )
            }
            assertEquals(0, proc.exitValue(), "kash -c 'echo hi' exited non-zero")
            val stdout = proc.inputStream.bufferedReader().readText()
            assertTrue(stdout.startsWith("hi\n"), "expected stdout to start with 'hi\\n', got: $stdout")
        } finally {
            shim.deleteRecursively()
        }
    }
}
