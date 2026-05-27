package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests: spin up a JGit [InMemoryRepository], seed it with a
 * couple of commits, bootstrap a kash session against it, and verify
 * that `git log`/`git show`/`git push` actually round-trip the real
 * commit SHAs through to JGit.
 */
class JGitHostAdapterTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun runGit(
        adapter: JGitHostAdapter,
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Output {
        val out = Buffer()
        val err = Buffer()
        val ctx =
            bareCommandContext(
                fs = fs,
                env = mutableMapOf(),
                cwd = cwd,
                stdin = Buffer().asSuspendSource(),
                stdout = out.asSuspendSink(),
                stderr = err.asSuspendSink(),
            )
        val res = runBlocking { GitCommand(adapter).run(args.toList(), ctx) }
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /** Insert a blob → tree → commit chain into a JGit repo and return the new commit's id. */
    private fun appendCommit(
        repo: Repository,
        parent: ObjectId?,
        path: String,
        content: ByteArray,
        message: String,
        author: PersonIdent =
            PersonIdent(
                "Test",
                "t@e",
                java.time.Instant.ofEpochSecond(1_700_000_000L),
                java.time.ZoneOffset.UTC,
            ),
    ): ObjectId {
        repo.newObjectInserter().use { ins ->
            val blobId = ins.insert(Constants.OBJ_BLOB, content)
            val tree = TreeFormatter()
            tree.append(path, org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blobId)
            val treeId = ins.insert(tree)
            val cb = CommitBuilder()
            cb.setTreeId(treeId)
            cb.author = author
            cb.committer = author
            cb.message = message
            if (parent != null) cb.setParentId(parent)
            val commitId = ins.insert(cb)
            ins.flush()
            // Advance refs/heads/main.
            val upd = repo.updateRef("refs/heads/main")
            upd.setNewObjectId(commitId)
            upd.setExpectedOldObjectId(parent ?: ObjectId.zeroId())
            upd.update()
            return commitId
        }
    }

    private fun newInMemoryRepo(): Repository {
        val desc = DfsRepositoryDescription("kash-test")
        val repo = InMemoryRepository(desc)
        repo.create(true) // bare
        // Symbolic HEAD → refs/heads/main even though no commits yet.
        repo.updateRef(Constants.HEAD).apply {
            link("refs/heads/main")
        }
        return repo
    }

    @Test fun bootstrapMakesUpstreamHeadVisibleToKashGit() {
        val repo = newInMemoryRepo()
        val c1 = appendCommit(repo, null, "hello.txt", "hi\n".encodeToByteArray(), "first commit\n")
        val c2 = appendCommit(repo, c1, "hello.txt", "hi again\n".encodeToByteArray(), "second commit\n")

        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // kash's HEAD should be JGit's c2.
        val resolved = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()
        assertEquals(c2.name, resolved)

        // Working tree file is materialized.
        assertTrue(fs.exists("/work/hello.txt"), "expected hello.txt materialized")
        val contents = runBlocking { fs.readBytes("/work/hello.txt").decodeToString() }
        assertEquals("hi again\n", contents)

        // `git log` walks both commits via the resolver.
        val log = runGit(adapter, fs, "/work", "log", "--pretty=%H").stdout.trim().lines()
        assertEquals(listOf(c2.name, c1.name), log)
    }

    @Test fun catFileServesJGitObjectsLazily() {
        val repo = newInMemoryRepo()
        val c1 = appendCommit(repo, null, "a.txt", "A\n".encodeToByteArray(), "only commit\n")
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // We can read the parent (none) via cat-file -p HEAD and the
        // tree via HEAD^{tree}.
        val type = runGit(adapter, fs, "/work", "cat-file", "-t", c1.name).stdout.trim()
        assertEquals("commit", type)
        // Pretty-print the commit; should mention "only commit".
        val pretty = runGit(adapter, fs, "/work", "cat-file", "-p", c1.name).stdout
        assertTrue("only commit" in pretty, "commit payload missing 'only commit':\n$pretty")
    }

    @Test fun pushAppliesNewCommitToJGit() {
        val repo = newInMemoryRepo()
        val c1 = appendCommit(repo, null, "a.txt", "A\n".encodeToByteArray(), "base\n")
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // Make a local commit in the kash session.
        runBlocking { fs.writeBytes("/work/a.txt", "modified\n".encodeToByteArray()) }
        runGit(adapter, fs, "/work", "add", "-A")
        val commitOut = runGit(adapter, fs, "/work", "commit", "-m", "kash side change")
        assertEquals(0, commitOut.rc, commitOut.stderr)
        val newSha = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()
        assertNotEquals(c1.name, newSha)

        // Push to upstream — should land in JGit.
        val pushOut = runGit(adapter, fs, "/work", "push")
        assertEquals(0, pushOut.rc, pushOut.stderr)

        // JGit's refs/heads/main now points at the pushed commit.
        val upstreamTip = repo.exactRef("refs/heads/main")!!.objectId.name
        assertEquals(newSha, upstreamTip)
        // And JGit can read the committed blob back.
        val newObjId = ObjectId.fromString(newSha)
        repo.newObjectReader().use { reader ->
            val commitLoader = reader.open(newObjId)
            assertEquals(Constants.OBJ_COMMIT, commitLoader.type)
        }
    }

    @Test fun bootstrapMirrorsTagsAndOtherBranches() {
        val repo = newInMemoryRepo()
        val c1 = appendCommit(repo, null, "a.txt", "1\n".encodeToByteArray(), "one\n")
        // Add another branch + a lightweight tag pointing at c1.
        repo.updateRef("refs/heads/feature").apply {
            setNewObjectId(c1)
            setExpectedOldObjectId(ObjectId.zeroId())
            update()
        }
        repo.updateRef("refs/tags/v1.0").apply {
            setNewObjectId(c1)
            setExpectedOldObjectId(ObjectId.zeroId())
            update()
        }
        // And an annotated tag (insert tag object, point ref at it).
        val annotatedSha =
            repo.newObjectInserter().use { ins ->
                val tag =
                    org.eclipse.jgit.lib.TagBuilder().apply {
                        setObjectId(c1, Constants.OBJ_COMMIT)
                        setTag("v2.0")
                        setTagger(
                            PersonIdent(
                                "Tagger",
                                "t@e",
                                java.time.Instant.ofEpochSecond(1_700_000_000L),
                                java.time.ZoneOffset.UTC,
                            ),
                        )
                        setMessage("release v2\n")
                    }
                val tagId = ins.insert(tag)
                ins.flush()
                repo.updateRef("refs/tags/v2.0").apply {
                    setNewObjectId(tagId)
                    setExpectedOldObjectId(ObjectId.zeroId())
                    update()
                }
                tagId
            }

        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        val branches = runGit(adapter, fs, "/work", "branch").stdout
        assertTrue("feature" in branches, "feature branch not mirrored:\n$branches")
        val tags = runGit(adapter, fs, "/work", "tag").stdout
        assertTrue("v1.0" in tags && "v2.0" in tags, "tags not mirrored:\n$tags")
        // Annotated tag should resolve via its tag-object sha.
        val annotatedType = runGit(adapter, fs, "/work", "cat-file", "-t", annotatedSha.name).stdout.trim()
        assertEquals("tag", annotatedType, "v2.0 should be an annotated tag object")
    }

    @Test fun pushTagLandsInJGit() {
        val repo = newInMemoryRepo()
        appendCommit(repo, null, "a.txt", "1\n".encodeToByteArray(), "base\n")
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // Create an annotated tag in the kash session and push it.
        val tagOut = runGit(adapter, fs, "/work", "tag", "-a", "kashtag", "-m", "from kash")
        assertEquals(0, tagOut.rc, tagOut.stderr)
        val pushOut = runGit(adapter, fs, "/work", "push", "origin", "kashtag")
        assertEquals(0, pushOut.rc, pushOut.stderr)
        // JGit's refs/tags/kashtag should exist and point at a tag object.
        val ref = repo.exactRef("refs/tags/kashtag")
        assertNotNull(ref, "kashtag missing in JGit")
        repo.newObjectReader().use { reader ->
            val loader = reader.open(ref.objectId)
            assertEquals(Constants.OBJ_TAG, loader.type, "expected tag object in JGit")
        }
    }

    @Test fun fetchPicksUpUpstreamMove() {
        val repo = newInMemoryRepo()
        val c1 = appendCommit(repo, null, "a.txt", "1\n".encodeToByteArray(), "one\n")
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(repo, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // Upstream gets a new commit out-of-band.
        val c2 = appendCommit(repo, c1, "a.txt", "2\n".encodeToByteArray(), "two\n")

        // `git fetch origin main` should advance origin/main to c2.
        val fetchOut = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, fetchOut.rc, fetchOut.stderr)
        val originTip = runGit(adapter, fs, "/work", "rev-parse", "origin/main").stdout.trim()
        assertEquals(c2.name, originTip)
    }
}
