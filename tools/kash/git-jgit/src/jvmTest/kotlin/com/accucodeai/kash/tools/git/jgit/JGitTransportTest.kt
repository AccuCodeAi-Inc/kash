package com.accucodeai.kash.tools.git.jgit

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.TreeFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end tests for the URL-aware fetch/push paths: an upstream
 * on-disk JGit FileRepository acts as the "remote", a session-local
 * InMemoryRepository is the kash side, JGit's [Transport][org.eclipse.jgit
 * .transport.Transport] bridges the two using `file://` URLs.
 *
 * `file://` URLs use [TransportLocal][org.eclipse.jgit.transport
 * .TransportLocal] under the hood — no daemon thread, no real socket,
 * the same code path that backs `https://`/`git://` in production
 * with the network bits stubbed out by the local protocol.
 */
class JGitTransportTest {
    @TempDir
    lateinit var tempDir: Path

    private val openRepos = mutableListOf<Repository>()

    @AfterEach fun closeRepos() {
        openRepos.forEach { it.close() }
        openRepos.clear()
    }

    private fun newUpstreamRepoOnDisk(): Repository {
        val gitDir = tempDir.resolve("upstream.git").toFile()
        gitDir.mkdirs()
        val repo = FileRepositoryBuilder.create(gitDir)
        repo.create(true)
        openRepos += repo
        return repo
    }

    private fun newSessionRepo(): Repository {
        // JGit's Transport API requires a non-null FS on the source
        // repo — InMemoryRepository doesn't have one, so use a temp
        // FileRepository for the session side of these tests.
        val gitDir = tempDir.resolve("session-${System.nanoTime()}.git").toFile()
        gitDir.mkdirs()
        val repo = FileRepositoryBuilder.create(gitDir)
        repo.create(true)
        repo.updateRef(Constants.HEAD).apply { link("refs/heads/main") }
        openRepos += repo
        return repo
    }

    private fun appendCommit(
        repo: Repository,
        parent: ObjectId?,
        path: String,
        content: ByteArray,
        message: String,
    ): ObjectId =
        repo.newObjectInserter().use { ins ->
            val blob = ins.insert(Constants.OBJ_BLOB, content)
            val tree = TreeFormatter().apply { append(path, org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob) }
            val treeId = ins.insert(tree)
            val cb =
                CommitBuilder().apply {
                    setTreeId(treeId)
                    author =
                        PersonIdent(
                            "T",
                            "t@e",
                            java.time.Instant.ofEpochSecond(1_700_000_000L),
                            java.time.ZoneOffset.UTC,
                        )
                    committer = author
                    setMessage(message)
                    if (parent != null) setParentId(parent)
                }
            val cid = ins.insert(cb)
            ins.flush()
            val upd = repo.updateRef("refs/heads/main")
            upd.setNewObjectId(cid)
            upd.setExpectedOldObjectId(parent ?: ObjectId.zeroId())
            upd.update()
            cid
        }

    private fun runGit(
        adapter: JGitHostAdapter,
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Triple<Int, String, String> {
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
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    @Test fun fetchFromUrlPullsUpstreamCommits() {
        val upstream = newUpstreamRepoOnDisk()
        val c1 = appendCommit(upstream, null, "a.txt", "one\n".encodeToByteArray(), "first\n")
        val c2 = appendCommit(upstream, c1, "a.txt", "two\n".encodeToByteArray(), "second\n")

        val session = newSessionRepo()
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(session, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        // Configure the session's remote.origin.url to point at upstream
        // via file:// — JGit picks TransportLocal for this scheme.
        val url = "file://" + tempDir.resolve("upstream.git").absolutePathString()
        runGit(adapter, fs, "/work", "config", "remote.origin.url", url)

        val fetch = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, fetch.first, fetch.third)

        // refs/remotes/origin/main now points at upstream's c2.
        val refRes = runGit(adapter, fs, "/work", "rev-parse", "origin/main")
        assertEquals(c2.name, refRes.second.trim(), "tracking ref should match upstream head")

        // And we can log it — the resolver finds the objects in the
        // session's in-memory repo (transport landed them there).
        val log = runGit(adapter, fs, "/work", "log", "--pretty=%H", "origin/main")
        assertEquals(0, log.first, log.third)
        val seen =
            log.second
                .trim()
                .lines()
                .filter { it.isNotBlank() }
        assertEquals(listOf(c2.name, c1.name), seen)
    }

    @Test fun pushToUrlAppliesToUpstream() {
        val upstream = newUpstreamRepoOnDisk()
        val baseSha = appendCommit(upstream, null, "a.txt", "base\n".encodeToByteArray(), "base\n")

        // Session repo starts empty; fetch seeds it from upstream.
        val session = newSessionRepo()
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(session, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }

        val url = "file://" + tempDir.resolve("upstream.git").absolutePathString()
        runGit(adapter, fs, "/work", "config", "remote.origin.url", url)
        // Pull upstream's history into session JGit + tracking refs.
        val fetch = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, fetch.first, fetch.third)
        // Move local main onto upstream's tip and materialize the work
        // tree so `git add` has something to diff against.
        runGit(adapter, fs, "/work", "reset", "--hard", "origin/main")

        // Make a local commit in the kash session and push it.
        runBlocking { fs.writeBytes("/work/a.txt", "from kash\n".encodeToByteArray()) }
        runGit(adapter, fs, "/work", "add", "-A")
        val commitOut = runGit(adapter, fs, "/work", "commit", "-m", "kash-side change")
        assertEquals(0, commitOut.first, commitOut.third)
        val newSha = runGit(adapter, fs, "/work", "rev-parse", "HEAD").second.trim()
        assertNotEquals(baseSha.name, newSha)

        val push = runGit(adapter, fs, "/work", "push")
        assertEquals(0, push.first, push.third)

        // Upstream's refs/heads/main now points at the pushed commit.
        val upstreamTip = upstream.exactRef("refs/heads/main")?.objectId?.name
        assertEquals(newSha, upstreamTip)
        // And the new commit object is present upstream.
        val pushedId = ObjectId.fromString(newSha)
        upstream.newObjectReader().use { reader ->
            assertTrue(reader.has(pushedId), "upstream missing pushed commit object")
        }
    }

    @Test fun networkPolicyDenyAllBlocksFetchAndPush() {
        val upstream = newUpstreamRepoOnDisk()
        appendCommit(upstream, null, "a.txt", "x\n".encodeToByteArray(), "x\n")

        val session = newSessionRepo()
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        // Use a policy that denies a real network host but still allows
        // file:// — except: file:// is exempt by design (local FS access),
        // so to exercise the deny path we point the URL at an https host
        // that the policy refuses.
        val adapter =
            JGitHostAdapter(
                session,
                workTreePath = "/work",
                networkPolicy = com.accucodeai.kash.api.sandbox.NetworkPolicy.DenyAll,
            )
        runBlocking { adapter.bootstrap(fs) }
        runGit(adapter, fs, "/work", "config", "remote.origin.url", "https://example.com/r.git")

        // Fetch — policy denies; falls through to local-only resolver
        // (which finds nothing), so kash reports "Already up to date".
        val fetch = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, fetch.first, fetch.third)
        // refs/remotes/origin/main should NOT have been written by a
        // real fetch — but our pre-existing tracking ref (if any) is
        // intact. Sanity: no commits appeared in the session repo.
        val log = runGit(adapter, fs, "/work", "log", "--pretty=%H", "origin/main")
        // origin/main is unresolvable (no fetch happened), so rev-parse
        // logs an error and returns the literal.
        assertNotEquals(0, log.first, "log should fail when no network happened")
    }

    @Test fun fileUrlBypassesNetworkPolicyDeny() {
        // file:// is local-FS access, not network. Even DenyAll lets it
        // through so the LLM can read/write a local mirror upstream.
        val upstream = newUpstreamRepoOnDisk()
        val c1 = appendCommit(upstream, null, "a.txt", "local\n".encodeToByteArray(), "first\n")

        val session = newSessionRepo()
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter =
            JGitHostAdapter(
                session,
                workTreePath = "/work",
                networkPolicy = com.accucodeai.kash.api.sandbox.NetworkPolicy.DenyAll,
            )
        runBlocking { adapter.bootstrap(fs) }
        val url = "file://" + tempDir.resolve("upstream.git").absolutePathString()
        runGit(adapter, fs, "/work", "config", "remote.origin.url", url)

        val fetch = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, fetch.first, fetch.third)
        val originHead = runGit(adapter, fs, "/work", "rev-parse", "origin/main").second.trim()
        assertEquals(c1.name, originHead, "file:// must bypass NetworkPolicy.DenyAll")
    }

    @Test fun fetchWithoutRemoteUrlStillWorksLocally() {
        // No remote URL configured — fetch should fall through to the
        // local-only refResolver path (returns nothing for unknown refs),
        // exit 0 with "Already up to date.".
        val session = newSessionRepo()
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        val adapter = JGitHostAdapter(session, workTreePath = "/work")
        runBlocking { adapter.bootstrap(fs) }
        val out = runGit(adapter, fs, "/work", "fetch", "origin", "main")
        assertEquals(0, out.first, out.third)
        assertTrue("Already up to date" in out.second || out.second.isEmpty(), "got: ${out.second}")
    }
}
