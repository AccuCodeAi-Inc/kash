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
import com.accucodeai.kash.tools.git.seed.materializeSeed
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FetchTest {
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

    @Test fun fetchAdvancesTrackingRefThroughResolver() =
        runTest {
            val person = PersonStamp("T", "t@e", 1700000000, "+0000")
            // Build a fake upstream tip out-of-band.
            val blobBytes = "upstream\n".encodeToByteArray()
            val blobShaUp = blobSha(blobBytes)
            val tree = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "f", blobShaUp)))
            val treeShaUp = objectSha(ObjectType.TREE, tree)
            val commit = CommitPayload(treeShaUp, emptyList(), person, person, "upstream commit\n")
            val newTipSha = commitSha(commit)

            val framedByShaUp =
                mapOf(
                    blobShaUp to framedObject(ObjectType.BLOB, blobBytes),
                    treeShaUp to framedObject(ObjectType.TREE, tree),
                    newTipSha to framedObject(ObjectType.COMMIT, encodeCommit(commit)),
                )
            val resolver = GitObjectResolver { sha -> framedByShaUp[sha] }
            val refResolver =
                GitRefResolver { ref ->
                    when (ref) {
                        "refs/heads/main" -> newTipSha
                        else -> null
                    }
                }

            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed =
                        GitRepoSeed.OnDemand(
                            horizon =
                                GitRepoSeed.Synthetic(
                                    workTree = mapOf("seed" to "v0\n".encodeToByteArray()),
                                ),
                            resolver = resolver,
                            refResolver = refResolver,
                        )
                    override val identity: GitIdentity = GitIdentity("T", "t@e")
                    override val workTreePath: String = "/work"
                }

            val fs = InMemoryFs()
            fs.mkdirs("/work")
            materializeSeed(adapter, fs)

            // Initial tracking ref points at the local horizon tip (not the
            // fabricated upstream).
            val initialTracking = runGit(adapter, fs, "/work", "rev-parse", "refs/remotes/origin/main").stdout.trim()
            val localTip = runGit(adapter, fs, "/work", "rev-parse", "HEAD").stdout.trim()
            assertEquals(localTip, initialTracking)

            // Fetch.
            val out = runGit(adapter, fs, "/work", "fetch")
            assertEquals(0, out.rc, out.stderr)
            assertTrue("main -> origin/main" in out.stdout, out.stdout)

            // Tracking ref advanced to the upstream sha.
            val afterTracking = runGit(adapter, fs, "/work", "rev-parse", "refs/remotes/origin/main").stdout.trim()
            assertEquals(newTipSha, afterTracking)

            // Inspect the new upstream content via show.
            val show = runGit(adapter, fs, "/work", "show", "$newTipSha:f").stdout
            assertEquals("upstream\n", show)
        }

    @Test fun fetchWithoutRefResolverFailsCleanly() =
        runTest {
            val fs = InMemoryFs()
            fs.mkdirs("/work")
            runGit(null, fs, "/work", "init")
            fs.writeBytes("/work/a", "x".encodeToByteArray())
            runGit(null, fs, "/work", "add", "a")
            runGit(null, fs, "/work", "commit", "-m", "x")
            val out = runGit(null, fs, "/work", "fetch")
            assertEquals(1, out.rc)
            assertTrue("no remote configured" in out.stderr, out.stderr)
        }

    @Test fun fetchNoOpWhenAlreadyUpToDate() =
        runTest {
            val person = PersonStamp("T", "t@e", 1700000000, "+0000")
            // Resolver always answers "I have nothing new" (returns horizon's tip).
            val adapter =
                object : GitHostAdapter {
                    override val repoSeed: GitRepoSeed =
                        GitRepoSeed.OnDemand(
                            horizon = GitRepoSeed.Synthetic(workTree = mapOf("a" to "v\n".encodeToByteArray())),
                            resolver = GitObjectResolver { null },
                            refResolver = GitRefResolver { _ -> null },
                        )
                    override val identity: GitIdentity = GitIdentity("T", "t@e")
                    override val workTreePath: String = "/w"
                }
            val fs = InMemoryFs()
            fs.mkdirs("/w")
            materializeSeed(adapter, fs)
            val out = runGit(adapter, fs, "/w", "fetch")
            assertEquals(0, out.rc, out.stderr)
            assertEquals("Already up to date.\n", out.stdout)
        }
}
