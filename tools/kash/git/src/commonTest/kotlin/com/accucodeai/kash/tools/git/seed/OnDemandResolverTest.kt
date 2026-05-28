package com.accucodeai.kash.tools.git.seed

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.git.SyntheticCommit
import com.accucodeai.kash.api.git.TreeChange
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RepoLayout
import com.accucodeai.kash.tools.git.plumbing.TreeEntry
import com.accucodeai.kash.tools.git.plumbing.blobSha
import com.accucodeai.kash.tools.git.plumbing.commitSha
import com.accucodeai.kash.tools.git.plumbing.encodeCommit
import com.accucodeai.kash.tools.git.plumbing.encodeTree
import com.accucodeai.kash.tools.git.plumbing.framedObject
import com.accucodeai.kash.tools.git.plumbing.objectSha
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OnDemand integration: the host materializes a synthetic horizon
 * containing just the tip commit, and supplies a resolver for the
 * earlier history. When `git log` walks past the horizon, the resolver
 * is consulted and the missing objects are cached locally.
 */
class OnDemandResolverTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun runGit(
        adapter: GitHostAdapter,
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
        val res = GitCommand(adapter).run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    @Test fun logWalksThroughResolverPastHorizon() =
        runTest {
            // Build an upstream history out-of-band: c1 → c2 → c3 (HEAD).
            // The horizon materializes c3 only; resolver provides c1, c2,
            // plus their trees/blobs.
            val identity = GitIdentity("Test User", "t@example.com")
            val person = PersonStamp(identity.name, identity.email, 1700000000, "+0000")

            // Build c1 tree (a = "v1")
            val blobV1 = "v1\n".encodeToByteArray()
            val blobV2 = "v2\n".encodeToByteArray()
            val blobV3 = "v3\n".encodeToByteArray()
            val v1Sha = blobSha(blobV1)
            val v2Sha = blobSha(blobV2)
            val v3Sha = blobSha(blobV3)

            val tree1 = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "a", v1Sha)))
            val tree2 = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "a", v2Sha)))
            val tree3 = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "a", v3Sha)))
            val tree1Sha = objectSha(ObjectType.TREE, tree1)
            val tree2Sha = objectSha(ObjectType.TREE, tree2)
            val tree3Sha = objectSha(ObjectType.TREE, tree3)

            val c1 = CommitPayload(tree1Sha, emptyList(), person, person, "first\n")
            val c1Sha = commitSha(c1)
            val c2 = CommitPayload(tree2Sha, listOf(c1Sha), person, person, "second\n")
            val c2Sha = commitSha(c2)
            val c3 = CommitPayload(tree3Sha, listOf(c2Sha), person, person, "third\n")

            // Map every framed object the resolver might be asked for.
            val framedBySha =
                mapOf(
                    v1Sha to framedObject(ObjectType.BLOB, blobV1),
                    v2Sha to framedObject(ObjectType.BLOB, blobV2),
                    tree1Sha to framedObject(ObjectType.TREE, tree1),
                    tree2Sha to framedObject(ObjectType.TREE, tree2),
                    c1Sha to framedObject(ObjectType.COMMIT, encodeCommit(c1)),
                    c2Sha to framedObject(ObjectType.COMMIT, encodeCommit(c2)),
                )
            val callsBySha = mutableMapOf<String, Int>()
            val resolver =
                GitObjectResolver { askedSha ->
                    callsBySha[askedSha] = (callsBySha[askedSha] ?: 0) + 1
                    framedBySha[askedSha]
                }

            // Horizon: the synthetic c3 commit. The materializer will write
            // c3 + tree3 + v3 itself.
            val horizon =
                GitRepoSeed.Synthetic(
                    workTree = mapOf("a" to blobV3),
                    history =
                        listOf(
                            SyntheticCommit(
                                tag = "tip",
                                // We *lie* about the parent — synthetic commits
                                // don't know about c2 directly, but we hard-wire
                                // it via a fake history with a sentinel and
                                // override extras. Simpler: leave parents empty
                                // and patch the commit's parent on-disk below.
                                parents = emptyList(),
                                message = "third\n",
                                authorDate = kotlin.time.Instant.fromEpochSeconds(1700000000),
                                author = identity,
                                changes = listOf(TreeChange.Write("a", blobV3)),
                            ),
                        ),
                )

            // The synthetic c3 will have parents = [], so we'd need to
            // overwrite its commit object on the VFS to give it
            // parents = [c2Sha]. Instead of doing the on-disk surgery here,
            // skip the actual log-past-horizon test in this test and just
            // verify: a manual `read(c1Sha)` on the ObjectStore (via the
            // git tool) triggers the resolver.

            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed = GitRepoSeed.OnDemand(horizon, resolver)
                    override val identity: GitIdentity = identity
                    override val workTreePath: String = "/work"
                }

            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            // Now: hand-write c3' that points at c2Sha (the resolver-backed
            // commit) to set up the cross-horizon walk. The synthetic
            // materializer wrote a parent-less c3; we overwrite it.
            val c3WithParent = CommitPayload(tree3Sha, listOf(c2Sha), person, person, "third\n")
            val c3NewSha = commitSha(c3WithParent)
            // Write the new commit and point the branch at it.
            val ourFramed = framedObject(ObjectType.COMMIT, encodeCommit(c3WithParent))
            val store =
                com.accucodeai.kash.tools.git.plumbing.ObjectStore(
                    RepoLayout("/work"),
                    fs,
                    resolver,
                )
            store.writeFramed(ourFramed)
            com.accucodeai.kash.tools.git.plumbing
                .RefStore(RepoLayout("/work"), fs)
                .writeRef("refs/heads/main", c3NewSha)

            // `git log` should walk: c3' → c2 (resolver) → c1 (resolver) → done.
            val log = runGit(adapter, fs, "/work", "log", "--pretty=%s")
            assertEquals(0, log.rc, log.stderr)
            assertEquals("third\nsecond\nfirst\n", log.stdout)

            // Verify the resolver was actually consulted for each missing
            // ancestor (commit + its tree).
            assertTrue(callsBySha[c2Sha]!! > 0, "resolver not called for c2: $callsBySha")
            assertTrue(callsBySha[c1Sha]!! > 0, "resolver not called for c1: $callsBySha")

            // And that subsequent reads of the same sha hit the local cache
            // (resolver count for already-resolved objects doesn't grow).
            val countBefore = callsBySha[c2Sha]!!
            runGit(adapter, fs, "/work", "log", "--pretty=%s")
            assertEquals(countBefore, callsBySha[c2Sha]!!, "resolver called again for cached c2")
        }

    @Test fun resolverNotCalledForLocallyResolvedObjects() =
        runTest {
            val identity = GitIdentity("T", "t@e")
            val resolver =
                GitObjectResolver { _ ->
                    error("resolver should not be called for local-only operations")
                }
            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed =
                        GitRepoSeed.OnDemand(
                            GitRepoSeed.Synthetic(workTree = mapOf("a" to "hello\n".encodeToByteArray())),
                            resolver,
                        )
                    override val identity: GitIdentity = identity
                    override val workTreePath: String = "/work"
                }
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)
            val log = runGit(adapter, fs, "/work", "log", "--pretty=%s")
            assertEquals(0, log.rc, log.stderr)
            assertEquals("Initial commit\n", log.stdout)
        }
}
