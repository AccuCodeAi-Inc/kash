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
 * Tests for v1.4: log --pretty=format, log --graph, status --branch /
 * --porcelain=v2, commit --fixup/--squash + rebase --autosquash.
 */
class PrettyGraphFixupStatusTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
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

    private fun setupRepo(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", "init")
    }

    private fun commit(
        fs: InMemoryFs,
        path: String,
        bytes: String,
        msg: String,
    ) {
        runBlocking { fs.writeBytes("/r/$path", bytes.encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "-m", msg)
    }

    // ---- pretty format ----

    @Test fun prettyFormatPlaceholders() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "subject one")
        val sha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()

        val out =
            run(fs, "/r", "log", "--pretty=format:%H|%h|%s|%an|%ae|%at|%T|%P").stdout.trim()
        val parts = out.split("|")
        assertEquals(sha, parts[0])
        assertEquals(sha.take(7), parts[1])
        assertEquals("subject one", parts[2])
        // %an / %ae default identity
        assertEquals("kash", parts[3])
        assertEquals("kash@localhost", parts[4])
        // %at unix ts from the env we passed
        assertEquals("1700000000", parts[5])
        // %T is tree sha (40 hex)
        assertEquals(40, parts[6].length)
        // %P for a root commit is empty
        assertEquals("", parts[7])
    }

    @Test fun prettyIsoDate() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "one")
        val out = run(fs, "/r", "log", "--pretty=format:%ai").stdout.trim()
        // 1700000000 unix → 2023-11-14 22:13:20 UTC
        assertEquals("2023-11-14 22:13:20 +0000", out)
    }

    @Test fun prettyPresetOneline() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "subj")
        val out = run(fs, "/r", "log", "--pretty=oneline").stdout.trim()
        val sha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        assertEquals("${sha.take(7)} subj", out)
    }

    // ---- graph ----

    @Test fun graphLinearHistory() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "c1")
        commit(fs, "a.txt", "2\n", "c2")
        commit(fs, "a.txt", "3\n", "c3")
        val out = run(fs, "/r", "log", "--graph", "--oneline").stdout
        // Three `*` rows (graph star may be in any column).
        assertEquals(3, out.lines().count { "*" in it }, "expected 3 commit rows:\n$out")
    }

    @Test fun graphMergeHistoryShowsBackslash() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "base")
        // Branch off and create a divergent commit on `feature`.
        run(fs, "/r", "switch", "-c", "feature")
        commit(fs, "f.txt", "F\n", "feat work")
        // Back to main, divergent change.
        run(fs, "/r", "switch", "main")
        commit(fs, "m.txt", "M\n", "main work")
        // Merge feature -> main.
        val mergeOut = run(fs, "/r", "merge", "feature", "-m", "merge feature")
        assertEquals(0, mergeOut.rc, mergeOut.stderr)
        val out = run(fs, "/r", "log", "--graph", "--oneline").stdout
        // At least one merge-bar row (|\) and four commits (any column).
        assertTrue("\\" in out, "expected merge backslash in graph:\n$out")
        assertTrue(out.lines().count { "*" in it } >= 4, "expected ≥4 commits:\n$out")
    }

    // ---- status --branch ----

    @Test fun statusBranchHeaderV1() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "one")
        val out = run(fs, "/r", "status", "--porcelain", "--branch").stdout
        assertTrue(out.startsWith("## main"), "expected branch header, got:\n$out")
    }

    @Test fun statusPorcelainV2() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "one")
        runBlocking { fs.writeBytes("/r/b.txt", "b\n".encodeToByteArray()) }
        val out = run(fs, "/r", "status", "--porcelain=v2").stdout
        assertTrue("# branch.oid " in out, "missing branch.oid:\n$out")
        assertTrue("# branch.head main" in out, "missing branch.head:\n$out")
        assertTrue("? b.txt" in out, "untracked b.txt missing in v2 output:\n$out")
    }

    @Test fun statusV2WithUpstreamAheadBehind() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "one")
        // Create a remote-tracking ref pointing at HEAD, then add a commit
        // on main so we should be 1 ahead.
        val head = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        runBlocking {
            fs.mkdirs("/r/.git/refs/remotes/origin")
            fs.writeBytes("/r/.git/refs/remotes/origin/main", "$head\n".encodeToByteArray())
        }
        // Wire up the branch config so v2 emits branch.upstream + branch.ab.
        run(fs, "/r", "config", "branch.main.remote", "origin")
        run(fs, "/r", "config", "branch.main.merge", "refs/heads/main")
        commit(fs, "a.txt", "2\n", "two")
        val out = run(fs, "/r", "status", "--porcelain=v2").stdout
        assertTrue("# branch.upstream origin/main" in out, "missing upstream:\n$out")
        assertTrue("# branch.ab +1 -0" in out, "expected +1 -0; got:\n$out")
    }

    // ---- fixup / squash + autosquash ----

    @Test fun commitFixupCreatesMagicSubject() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "feat: alpha")
        val firstSha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        commit(fs, "b.txt", "B\n", "noise")
        // Now a fixup of the first commit.
        runBlocking { fs.writeBytes("/r/a.txt", "1+\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        val out = run(fs, "/r", "commit", "--fixup", firstSha)
        assertEquals(0, out.rc, out.stderr)
        val subject = run(fs, "/r", "log", "--pretty=%s", "-n1").stdout.trim()
        assertEquals("fixup! feat: alpha", subject)
    }

    @Test fun commitSquashCreatesMagicSubject() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "feat: bravo")
        val firstSha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        runBlocking { fs.writeBytes("/r/a.txt", "1+\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        val out = run(fs, "/r", "commit", "--squash", firstSha, "-m", "extra detail")
        assertEquals(0, out.rc, out.stderr)
        val subject = run(fs, "/r", "log", "--pretty=%s", "-n1").stdout.trim()
        assertEquals("squash! feat: bravo", subject)
    }

    @Test fun rebaseAutosquashFoldsFixup() {
        val fs = InMemoryFs()
        setupRepo(fs)
        commit(fs, "a.txt", "1\n", "feat: A")
        val targetSha = run(fs, "/r", "rev-parse", "HEAD").stdout.trim()
        commit(fs, "b.txt", "B\n", "feat: B")
        // Fixup the first commit while sitting on the second.
        runBlocking { fs.writeBytes("/r/a.txt", "1+\n".encodeToByteArray()) }
        run(fs, "/r", "add", "-A")
        run(fs, "/r", "commit", "--fixup", targetSha)
        // Before rebase: 3 commits.
        assertEquals("3", run(fs, "/r", "rev-list", "--count", "HEAD").stdout.trim())
        // Rebase --autosquash onto the parent of the first commit (= HEAD~3).
        // The base is the empty tree; pass HEAD~3 — which is null for a
        // root commit — so use --root semantics by giving the first commit
        // a marker we can rebase onto. Simplest: rebase onto first commit
        // itself, which makes feat:B + fixup the only replays, then the
        // fixup gets folded into feat:B... but that's not what we want.
        // Instead: create a base commit BEFORE feat: A.
        // Reset: redo the setup to give us a clean base.
        val fs2 = InMemoryFs()
        setupRepo(fs2)
        commit(fs2, "base.txt", "0\n", "base")
        commit(fs2, "a.txt", "1\n", "feat: A")
        val targetSha2 = run(fs2, "/r", "rev-parse", "HEAD").stdout.trim()
        commit(fs2, "b.txt", "B\n", "feat: B")
        runBlocking { fs2.writeBytes("/r/a.txt", "1+\n".encodeToByteArray()) }
        run(fs2, "/r", "add", "-A")
        run(fs2, "/r", "commit", "--fixup", targetSha2)
        // Now 4 commits: base, feat:A, feat:B, fixup!feat:A
        val before = run(fs2, "/r", "rev-list", "--count", "HEAD").stdout.trim()
        assertEquals("4", before)
        // Rebase --autosquash onto base.
        val rb = run(fs2, "/r", "rebase", "--autosquash", "HEAD~3")
        assertEquals(0, rb.rc, rb.stderr)
        // After: fixup should fold into feat:A → 3 commits.
        val after = run(fs2, "/r", "rev-list", "--count", "HEAD").stdout.trim()
        assertEquals(
            "3",
            after,
            "expected fixup to fold; subjects:\n" +
                run(fs2, "/r", "log", "--pretty=%s").stdout,
        )
        // The subject "fixup! feat: A" should be gone.
        val subjects = run(fs2, "/r", "log", "--pretty=%s").stdout
        assertTrue("fixup!" !in subjects, "fixup! subject leaked:\n$subjects")
    }
}
