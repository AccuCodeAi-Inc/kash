package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitRefResolver
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.TreeEntry
import com.accucodeai.kash.tools.git.plumbing.blobSha
import com.accucodeai.kash.tools.git.plumbing.commitSha
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.encodeTree
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.objectSha
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * v1.5 follow-ups:
 *  - Reflog entries get written on every ref-mover (merge/rebase/reset/
 *    branch creation/fetch/push/stash). Hardens HEAD@{N} semantics.
 *  - `commit --amend` on a merge commit preserves both parents.
 *  - `clone` against a real GitRefResolver materializes the work tree
 *    and writes refs/heads/<branch>.
 */
class ReflogAmendCloneTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun run(
        fs: InMemoryFs,
        cwd: String,
        adapter: GitHostAdapter? = null,
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
        val cmd = if (adapter != null) GitCommand(adapter) else GitCommand()
        val res = runBlocking { cmd.run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    private fun setup(fs: InMemoryFs) {
        fs.mkdirs("/r")
        run(fs, "/r", args = arrayOf("init"))
    }

    private fun commit(
        fs: InMemoryFs,
        path: String,
        bytes: String,
        msg: String,
    ) {
        runBlocking { fs.writeBytes("/r/$path", bytes.encodeToByteArray()) }
        run(fs, "/r", args = arrayOf("add", "-A"))
        run(fs, "/r", args = arrayOf("commit", "-m", msg))
    }

    // ---- reflog on more sites ----

    @Test fun resetWritesReflogAndHeadAtNWorks() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "one")
        val first = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        commit(fs, "a.txt", "2\n", "two")
        commit(fs, "a.txt", "3\n", "three")
        val third = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        // Reset hard back to first.
        assertEquals(0, run(fs, "/r", args = arrayOf("reset", "--hard", first)).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "HEAD")).stdout
        assertTrue("reset:" in log, "no reset entry in reflog:\n$log")
        // HEAD@{1} (one move back) should be the commit we reset from = third.
        val atOne = run(fs, "/r", args = arrayOf("rev-parse", "HEAD@{1}")).stdout.trim()
        assertEquals(third, atOne)
    }

    @Test fun mergeFastForwardWritesReflog() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "base")
        val basetip = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        run(fs, "/r", args = arrayOf("switch", "-c", "feature"))
        commit(fs, "a.txt", "2\n", "more")
        run(fs, "/r", args = arrayOf("switch", "main"))
        // FF merge of feature into main.
        assertEquals(0, run(fs, "/r", args = arrayOf("merge", "feature")).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "HEAD")).stdout
        assertTrue("merge feature" in log, "no merge entry in reflog:\n$log")
        // HEAD@{1} after the FF merge = base tip.
        val atOne = run(fs, "/r", args = arrayOf("rev-parse", "HEAD@{1}")).stdout.trim()
        assertEquals(basetip, atOne)
    }

    @Test fun mergeNonFFWritesReflog() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "base")
        run(fs, "/r", args = arrayOf("switch", "-c", "feature"))
        commit(fs, "f.txt", "F\n", "feat work")
        run(fs, "/r", args = arrayOf("switch", "main"))
        commit(fs, "m.txt", "M\n", "main work")
        val mainTipBefore = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        assertEquals(0, run(fs, "/r", args = arrayOf("merge", "feature", "-m", "merge feature")).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "HEAD")).stdout
        assertTrue("merge feature: Merge made" in log, "no merge-commit entry in reflog:\n$log")
        // Pre-merge sha is at HEAD@{1}.
        val atOne = run(fs, "/r", args = arrayOf("rev-parse", "HEAD@{1}")).stdout.trim()
        assertEquals(mainTipBefore, atOne)
    }

    @Test fun rebaseWritesReflogPerPickAndFinish() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "base.txt", "0\n", "base")
        run(fs, "/r", args = arrayOf("switch", "-c", "feature"))
        commit(fs, "a.txt", "1\n", "feat A")
        commit(fs, "b.txt", "B\n", "feat B")
        // Add a commit on main so feature is divergent.
        run(fs, "/r", args = arrayOf("switch", "main"))
        commit(fs, "m.txt", "M\n", "main only")
        run(fs, "/r", args = arrayOf("switch", "feature"))
        assertEquals(0, run(fs, "/r", args = arrayOf("rebase", "main")).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "HEAD")).stdout
        assertTrue("rebase: feat A" in log || "rebase: feat B" in log, "no per-pick reflog:\n$log")
    }

    @Test fun branchCreationWritesReflog() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "one")
        assertEquals(0, run(fs, "/r", args = arrayOf("branch", "feature")).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "refs/heads/feature")).stdout
        assertTrue("branch: Created from" in log, "no branch-creation entry:\n$log")
    }

    @Test fun branchDeleteRemovesReflogFile() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "one")
        run(fs, "/r", args = arrayOf("branch", "feature"))
        // Reflog file should exist.
        assertTrue(fs.exists("/r/.git/logs/refs/heads/feature"), "branch creation should leave a log")
        run(fs, "/r", args = arrayOf("branch", "-d", "feature"))
        assertEquals(false, fs.exists("/r/.git/logs/refs/heads/feature"), "log should be cleared on -d")
    }

    @Test fun stashWritesReflog() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "one")
        runBlocking { fs.writeBytes("/r/a.txt", "modified\n".encodeToByteArray()) }
        assertEquals(0, run(fs, "/r", args = arrayOf("stash", "push", "-m", "wip")).rc)
        val log = run(fs, "/r", args = arrayOf("reflog", "refs/stash")).stdout
        assertTrue("wip" in log || "WIP" in log || "On main" in log, "no stash entry:\n$log")
    }

    // ---- amend on merge commit ----

    @Test fun amendOnMergeCommitPreservesBothParents() {
        val fs = InMemoryFs()
        setup(fs)
        commit(fs, "a.txt", "1\n", "base")
        run(fs, "/r", args = arrayOf("switch", "-c", "feature"))
        commit(fs, "f.txt", "F\n", "feat work")
        val featSha = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        run(fs, "/r", args = arrayOf("switch", "main"))
        commit(fs, "m.txt", "M\n", "main work")
        val mainSha = run(fs, "/r", args = arrayOf("rev-parse", "HEAD")).stdout.trim()
        // Merge produces a 2-parent commit.
        run(fs, "/r", args = arrayOf("merge", "feature", "-m", "merge feature"))
        // Amend the merge commit.
        val r = run(fs, "/r", args = arrayOf("commit", "--amend", "-m", "merge feature (amended)"))
        assertEquals(0, r.rc, r.stderr)
        // Parents are unchanged: [mainSha, featSha].
        val parents = run(fs, "/r", args = arrayOf("log", "--pretty=%P", "-n1")).stdout.trim()
        val parentList = parents.split(" ")
        assertEquals(2, parentList.size, "amend on merge should keep 2 parents; got $parents")
        assertTrue(mainSha in parentList && featSha in parentList, "missing original parents in $parents")
        // Subject is the new one.
        val subj = run(fs, "/r", args = arrayOf("log", "--pretty=%s", "-n1")).stdout.trim()
        assertEquals("merge feature (amended)", subj)
    }

    // ---- clone via real refResolver ----

    @Test fun cloneViaRefResolverMaterializesWorkTree() {
        val identity = GitIdentity("Test", "t@e")
        val person = PersonStamp(identity.name, identity.email, 1700000000, "+0000")

        val blobBytes = "fetched content\n".encodeToByteArray()
        val blobSha = blobSha(blobBytes)
        val tree = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "hello.txt", blobSha)))
        val treeSha = objectSha(ObjectType.TREE, tree)
        val commit = CommitPayload(treeSha, emptyList(), person, person, "from upstream\n")
        val tipSha = commitSha(commit)

        val framedBySha =
            mapOf(
                blobSha to framedObject(ObjectType.BLOB, blobBytes),
                treeSha to framedObject(ObjectType.TREE, tree),
                tipSha to framedObject(ObjectType.COMMIT, encodeCommit(commit)),
            )
        val objResolver = GitObjectResolver { framedBySha[it] }
        val refResolver = GitRefResolver { ref -> if (ref == "refs/heads/main") tipSha else null }

        // Empty synthetic seed (no commits) so init lays down a bare layout,
        // then clone fetches origin/main via the resolvers.
        val seed =
            GitRepoSeed.OnDemand(
                horizon =
                    GitRepoSeed.Synthetic(
                        workTree = emptyMap(),
                        history = emptyList(),
                    ),
                resolver = objResolver,
                refResolver = refResolver,
            )
        val adapter =
            object : GitHostAdapter {
                override val repoSeed: GitRepoSeed = seed
                override val identity: GitIdentity = identity
                override val workTreePath: String = "/work"
            }
        val fs = InMemoryFs()
        fs.mkdirs("/parent")

        val r = run(fs, "/parent", adapter = adapter, args = arrayOf("clone", "https://example.com/repo.git", "repo"))
        assertEquals(0, r.rc, r.stderr)
        // Work tree should contain hello.txt.
        assertTrue(fs.exists("/parent/repo/hello.txt"), "expected hello.txt materialized:\n${r.stdout}\n${r.stderr}")
        // refs/heads/main should point at the tip.
        val mainSha =
            runBlocking {
                fs.readBytes("/parent/repo/.git/refs/heads/main").decodeToString().trim()
            }
        assertEquals(tipSha, mainSha)
        // origin remote URL persisted.
        val cfg = runBlocking { fs.readBytes("/parent/repo/.git/config").decodeToString() }
        assertTrue("https://example.com/repo.git" in cfg, "origin URL missing in .git/config:\n$cfg")
    }
}
