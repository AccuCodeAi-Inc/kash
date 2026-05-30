package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Differential coverage for shortlog / cherry / whatchanged vs the real
 * `git` CLI. We build state with kash, mirror the VFS into a temp dir,
 * and compare the semantically meaningful slices of output (identity
 * grouping for shortlog, `+`/`-` classification for cherry, raw/patch
 * lines for whatchanged).
 */
class ShortlogCherryDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    private fun runGit(
        fs: InMemoryFs,
        cwd: String,
        env: Map<String, String>,
        vararg args: String,
    ): Triple<Int, String, String> {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = env.toMutableMap(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand().run(args.toList(), ctx) }
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    private fun identityEnv(
        name: String,
        email: String,
    ): Map<String, String> =
        mapOf(
            "GIT_AUTHOR_NAME" to name,
            "GIT_AUTHOR_EMAIL" to email,
            "GIT_AUTHOR_DATE" to "1700000000 +0000",
            "GIT_COMMITTER_NAME" to name,
            "GIT_COMMITTER_EMAIL" to email,
            "GIT_COMMITTER_DATE" to "1700000000 +0000",
        )

    private fun kashCommit(
        fs: InMemoryFs,
        cwd: String,
        name: String,
        email: String,
        path: String,
        content: String,
        msg: String,
    ) {
        runBlocking { fs.writeBytes("$cwd/$path", content.encodeToByteArray()) }
        val env = identityEnv(name, email)
        runGit(fs, cwd, env, "add", path)
        runGit(fs, cwd, env, "commit", "-m", msg)
    }

    @Test fun shortlogMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        runGit(fs, "/r", identityEnv("X", "x@x"), "init", "-q")
        kashCommit(fs, "/r", "Alice", "alice@x.com", "f", "a\n", "first commit")
        kashCommit(fs, "/r", "Alice", "alice@x.com", "f", "a\nb\n", "second commit")
        kashCommit(fs, "/r", "Bob", "bob@y.com", "f", "a\nb\nc\n", "third commit")

        runBlocking { copyDir(fs, "/r", tmp) }

        for (flags in listOf(listOf("HEAD"), listOf("-s", "HEAD"), listOf("-sn", "HEAD"), listOf("-se", "HEAD"))) {
            val kash = runGit(fs, "/r", identityEnv("X", "x@x"), "shortlog", *flags.toTypedArray()).second
            val real = probe.run(listOf("shortlog") + flags, tmp).stdoutUtf8()
            assertEquals(real, kash, "shortlog $flags divergence")
        }
    }

    @Test fun cherryClassificationMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        val env = identityEnv("U", "u@x")
        runGit(fs, "/r", env, "init", "-q")
        kashCommit(fs, "/r", "U", "u@x", "f", "l1\n", "base")
        runGit(fs, "/r", env, "switch", "-c", "topic")
        kashCommit(fs, "/r", "U", "u@x", "f", "l1\nl2\n", "add l2")
        kashCommit(fs, "/r", "U", "u@x", "f", "l1\nl2\nl3\n", "add l3")
        runGit(fs, "/r", env, "switch", "main")
        kashCommit(fs, "/r", "U", "u@x", "g", "other\n", "main other")
        runGit(fs, "/r", env, "cherry-pick", "topic~1")

        runBlocking { copyDir(fs, "/r", tmp) }

        // Compare the sign + subject columns (shas differ in width/value
        // only in that kash emits full 40, real git too for cherry).
        fun signSubjects(text: String): List<Pair<String, String>> =
            text.lines().filter { it.isNotEmpty() }.map {
                val sign = it.substring(0, 1)
                val subject = it.substringAfter(' ').substringAfter(' ')
                sign to subject
            }

        val kash = runGit(fs, "/r", env, "cherry", "-v", "main", "topic").second
        val real = probe.run(listOf("cherry", "-v", "main", "topic"), tmp).stdoutUtf8()
        assertEquals(signSubjects(real), signSubjects(kash), "cherry classification divergence\nreal:$real\nkash:$kash")
    }

    @Test fun whatchangedRawMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val fs = InMemoryFs()
        fs.mkdirs("/r")
        val env = identityEnv("U", "u@x")
        runGit(fs, "/r", env, "init", "-q")
        kashCommit(fs, "/r", "U", "u@x", "f", "l1\n", "base")
        kashCommit(fs, "/r", "U", "u@x", "f", "l1\nl2\n", "add l2")

        runBlocking { copyDir(fs, "/r", tmp) }

        val kash = runGit(fs, "/r", env, "whatchanged", "-n", "1").second
        val real = probe.run(listOf("whatchanged", "-n", "1"), tmp).stdoutUtf8()

        // The raw change line (`:mode mode sha sha STATUS\tpath`) is the
        // load-bearing part; commit headers/date formatting are checked
        // elsewhere. Compare the raw `:`-prefixed lines.
        fun rawLines(text: String) = text.lines().filter { it.startsWith(":") }
        assertEquals(rawLines(real), rawLines(kash), "whatchanged raw line divergence\nreal:$real\nkash:$kash")
    }

    private suspend fun copyDir(
        fs: InMemoryFs,
        src: String,
        dest: File,
    ) {
        dest.mkdirs()
        for (name in fs.list(src)) {
            val full = "$src/$name"
            val out = File(dest, name)
            if (fs.isDirectory(full)) {
                copyDir(fs, full, out)
            } else {
                out.writeBytes(fs.readBytes(full))
            }
        }
    }
}
