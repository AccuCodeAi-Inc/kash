package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.git.GitHostAdapter
import com.accucodeai.kash.api.git.GitIdentity
import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.api.git.GitPushApplier
import com.accucodeai.kash.api.git.GitPushOutcome
import com.accucodeai.kash.api.git.GitPushRequest
import com.accucodeai.kash.api.git.GitRepoSeed
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.seed.materializeSeed
import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readString
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end push: kash drives the work, the host applier writes the
 * received bundles into a real bare git repo on disk, then real git
 * verifies the result.
 *
 * This proves the bundle contents are byte-loadable by real git — the
 * commit objects, trees, and blobs the applier receives ARE the bytes
 * the upstream needs to store to make the push real. (A GitLab-style
 * applier would do the same thing but via the REST API; we substitute
 * a local filesystem write to keep the test hermetic.)
 */
class PushDifferentialTest {
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
        adapter: GitHostAdapter,
        fs: InMemoryFs,
        cwd: String,
        vararg args: String,
    ): Triple<Int, String, String> {
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
        val res = runBlocking { GitCommand(adapter).run(args.toList(), ctx) }
        return Triple(res.exitCode, out.readString(), err.readString())
    }

    /**
     * Host-side applier that writes received bundles into a real
     * on-disk bare repo. The bundle's framed bytes are exactly what
     * `.git/objects/aa/bbbb...` should contain after zlib deflation —
     * the same path that real git itself uses, so real git can read
     * them back immediately.
     */
    private class DiskApplier(
        private val bareRepoDir: File,
    ) : GitPushApplier {
        override suspend fun apply(req: GitPushRequest): GitPushOutcome {
            for (bundle in req.newCommits) {
                writeLoose(bundle.commitSha, bundle.commit)
                for ((sha, framed) in bundle.trees) writeLoose(sha, framed)
                for ((sha, framed) in bundle.blobs) writeLoose(sha, framed)
            }
            // Advance the upstream ref.
            val refFile = File(bareRepoDir, "refs/heads/${req.branch}")
            refFile.parentFile.mkdirs()
            refFile.writeText(req.tipSha + "\n")
            return GitPushOutcome.Accepted(req.tipSha)
        }

        private fun writeLoose(
            sha: String,
            framed: ByteArray,
        ) {
            val dir = File(bareRepoDir, "objects/${sha.substring(0, 2)}")
            dir.mkdirs()
            val file = File(dir, sha.substring(2))
            // Idempotent: real git's stores are content-addressed.
            if (file.exists()) return
            file.writeBytes(deflate(framed))
        }

        private fun deflate(data: ByteArray): ByteArray {
            val def = Deflater(1)
            try {
                def.setInput(data)
                def.finish()
                val out = ByteArrayOutputStream(data.size + 16)
                val buf = ByteArray(4096)
                while (!def.finished()) {
                    val n = def.deflate(buf)
                    if (n > 0) out.write(buf, 0, n)
                }
                return out.toByteArray()
            } finally {
                def.end()
            }
        }
    }

    private fun makeBareRepo(dir: File) {
        // Real git's init --bare gives us the exact directory layout
        // applier expects (`objects/`, `refs/`, `HEAD`, `config`).
        val r = probe.run(listOf("init", "--bare", "-b", "main", dir.absolutePath), File("."))
        check(r.exitCode == 0) { r.stderrUtf8() }
    }

    /**
     * Mirror every loose object + every ref from the kash VFS into the
     * bare upstream so it has the horizon the OnDemand seed wrote. In
     * a real GitLab-style integration this would already be there
     * (the host fetched it from upstream and seeded the LLM session
     * from the same source).
     */
    private suspend fun seedBareFromKash(
        fs: InMemoryFs,
        kashRepo: String,
        bare: File,
    ) {
        // Copy every loose object byte-for-byte. Real git stores them
        // exactly as we wrote them (zlib-compressed framed bytes), so
        // these files transplant cleanly.
        val objectsDir = "$kashRepo/.git/objects"
        if (fs.exists(objectsDir)) {
            for (shard in fs.list(objectsDir)) {
                if (shard.length != 2) continue
                val shardDir = "$objectsDir/$shard"
                if (!fs.isDirectory(shardDir)) continue
                val destShard = File(bare, "objects/$shard").also { it.mkdirs() }
                for (name in fs.list(shardDir)) {
                    File(destShard, name).writeBytes(fs.readBytes("$shardDir/$name"))
                }
            }
        }
        // Copy local-heads refs into the bare's refs/heads/.
        val headsDir = "$kashRepo/.git/refs/heads"
        if (fs.exists(headsDir)) {
            for (name in fs.list(headsDir)) {
                val sha = fs.readBytes("$headsDir/$name").decodeToString().trim()
                File(bare, "refs/heads/$name").apply {
                    parentFile.mkdirs()
                    writeText("$sha\n")
                }
            }
        }
    }

    private fun adapterWithApplier(applier: GitPushApplier): GitHostAdapter =
        object : GitHostAdapter {
            override val repoSeed: GitRepoSeed =
                GitRepoSeed.OnDemand(
                    horizon =
                        GitRepoSeed.Synthetic(
                            workTree = mapOf("README" to "start\n".encodeToByteArray()),
                        ),
                    resolver = GitObjectResolver { null },
                    pushApplier = applier,
                )
            override val identity: GitIdentity = GitIdentity("T", "t@example.com")
            override val workTreePath: String = "/work"
        }

    @Test fun pushedCommitIsRealGitReadable(
        @TempDir tmp: File,
    ) {
        val bareDir = File(tmp, "remote.git").also { it.mkdirs() }
        makeBareRepo(bareDir)
        val applier = DiskApplier(bareDir)
        val adapter = adapterWithApplier(applier)

        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking {
            materializeSeed(adapter, fs)
            seedBareFromKash(fs, "/work", bareDir)
        }

        // Make a local commit and push.
        runBlocking { fs.writeBytes("/work/README", "from kash\n".encodeToByteArray()) }
        runGit(adapter, fs, "/work", "add", "README")
        runGit(adapter, fs, "/work", "commit", "-m", "edit on kash")
        val kashTip = runGit(adapter, fs, "/work", "rev-parse", "HEAD").second.trim()

        val push = runGit(adapter, fs, "/work", "push")
        assertEquals(0, push.first, push.third)

        // Real git on the bare repo agrees on the tip.
        val realTip = probe.run(listOf("rev-parse", "refs/heads/main"), bareDir).stdoutUtf8().trim()
        assertEquals(kashTip, realTip)

        // Real git can show the file content.
        assertEquals(
            "from kash\n",
            probe.run(listOf("show", "refs/heads/main:README"), bareDir).stdoutUtf8(),
        )

        // The whole repo passes fsck.
        val fsck = probe.run(listOf("fsck", "--strict", "--no-progress"), bareDir)
        assertEquals(0, fsck.exitCode, fsck.stderrUtf8())
    }

    @Test fun multiCommitPushAllArriveAtUpstream(
        @TempDir tmp: File,
    ) {
        val bareDir = File(tmp, "remote.git").also { it.mkdirs() }
        makeBareRepo(bareDir)
        val applier = DiskApplier(bareDir)
        val adapter = adapterWithApplier(applier)

        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking {
            materializeSeed(adapter, fs)
            seedBareFromKash(fs, "/work", bareDir)
        }

        // Three sequential commits.
        for (i in 1..3) {
            runBlocking { fs.writeBytes("/work/v$i", "version $i\n".encodeToByteArray()) }
            runGit(adapter, fs, "/work", "add", "-A")
            runGit(adapter, fs, "/work", "commit", "-m", "step $i")
        }
        val kashTip = runGit(adapter, fs, "/work", "rev-parse", "HEAD").second.trim()
        runGit(adapter, fs, "/work", "push")

        // Real git's log on the bare upstream sees all three.
        val log = probe.run(listOf("log", "--pretty=%s"), bareDir).stdoutUtf8()
        assertTrue("step 3" in log, log)
        assertTrue("step 2" in log, log)
        assertTrue("step 1" in log, log)
        // Tip sha matches.
        assertEquals(kashTip, probe.run(listOf("rev-parse", "refs/heads/main"), bareDir).stdoutUtf8().trim())
        // Files materialized.
        for (i in 1..3) {
            assertEquals(
                "version $i\n",
                probe.run(listOf("show", "refs/heads/main:v$i"), bareDir).stdoutUtf8(),
            )
        }
    }

    @Test fun pushAlreadyUpToDateSkipsApplier(
        @TempDir tmp: File,
    ) {
        val bareDir = File(tmp, "remote.git").also { it.mkdirs() }
        makeBareRepo(bareDir)
        var applierCalls = 0
        val applier =
            object : GitPushApplier {
                override suspend fun apply(req: GitPushRequest): GitPushOutcome {
                    applierCalls++
                    return GitPushOutcome.Accepted(req.tipSha)
                }
            }
        val adapter = adapterWithApplier(applier)
        val fs = InMemoryFs()
        fs.mkdirs("/work")
        runBlocking { materializeSeed(adapter, fs) }

        // No changes — push must short-circuit.
        val out = runGit(adapter, fs, "/work", "push")
        assertEquals(0, out.first, out.third)
        assertEquals("Everything up-to-date\n", out.second)
        assertEquals(0, applierCalls, "applier should not have been called")
    }
}
