package com.accucodeai.kash.tools.git.porcelain

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.asSuspendSource
import com.accucodeai.kash.fs.InMemoryFs
import com.accucodeai.kash.test.bareCommandContext
import com.accucodeai.kash.tools.git.GitCommand
import com.accucodeai.kash.tools.git.plumbing.CommitPayload
import com.accucodeai.kash.tools.git.plumbing.FileMode
import com.accucodeai.kash.tools.git.plumbing.ObjectStore
import com.accucodeai.kash.tools.git.plumbing.ObjectType
import com.accucodeai.kash.tools.git.plumbing.PersonStamp
import com.accucodeai.kash.tools.git.plumbing.RefStore
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
 * Regression test for the `git log` shallow-clone crash.
 *
 * Before the fix in commit 5b450f4: `git clone --depth=N` produced a
 * repo where the deepest commit's parent pointer referenced a sha
 * whose object isn't local. The log walk called `readCommit(parent)`
 * unconditionally, the exception propagated through `GitCommand`,
 * and the framework caught it and silently returned exit 1.
 * Effect: `git log` on any shallow clone showed nothing.
 *
 * Fixture: a single local commit whose declared parent is the
 * all-zero sha (`00…00`), guaranteed not to exist locally — same
 * shape JGit's Transport.fetch produces for a `--depth=1` clone
 * (modulo the actual upstream parent sha).
 */
class LogShallowWalkTest {
    private data class Output(
        val rc: Int,
        val stdout: String,
        val stderr: String,
    )

    private suspend fun runGit(
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
        val res = GitCommand().run(args.toList(), ctx)
        return Output(res.exitCode, out.readString(), err.readString())
    }

    /**
     * Seed `/r/.git/` with a single commit whose parent is a sha we
     * never write. Returns the tip sha.
     */
    private suspend fun seedShallowRepo(fs: InMemoryFs): String {
        val person = PersonStamp("Test User", "t@example.com", 1700000000, "+0000")
        val blob = "hello\n".encodeToByteArray()
        val blobSha = blobSha(blob)
        val tree = encodeTree(listOf(TreeEntry(FileMode.REGULAR, "a.txt", blobSha)))
        val treeSha = objectSha(ObjectType.TREE, tree)
        val missingParent = "0".repeat(40)
        val commit = CommitPayload(treeSha, listOf(missingParent), person, person, "tip\n")
        val tipSha = commitSha(commit)

        val layout = RepoLayout("/r")
        fs.mkdirs(layout.gitDir)
        fs.mkdirs(layout.objectsDir)
        fs.mkdirs("${layout.gitDir}/refs/heads")
        val store = ObjectStore(layout, fs, null)
        store.writeFramed(framedObject(ObjectType.BLOB, blob))
        store.writeFramed(framedObject(ObjectType.TREE, tree))
        store.writeFramed(framedObject(ObjectType.COMMIT, encodeCommit(commit)))
        RefStore(layout, fs).writeRef("refs/heads/master", tipSha)
        RefStore(layout, fs).writeHeadSymbolic("refs/heads/master")
        return tipSha
    }

    @Test fun logEmitsTipAndExitsAtShallowBoundary() =
        runTest {
            val fs = InMemoryFs()
            val tipSha = seedShallowRepo(fs)

            val out = runGit(fs, "/r", "log")
            assertEquals(0, out.rc, "git log on shallow repo crashed: rc=${out.rc} stderr=${out.stderr}")
            assertTrue(out.stdout.contains(tipSha), "expected tip sha $tipSha in log output; got: ${out.stdout}")
            assertTrue(out.stdout.contains("tip"), "expected commit message 'tip' in log output; got: ${out.stdout}")
            // Should not surface the missing parent's sha — the walk stops
            // before we'd try to render it.
            assertTrue(
                !out.stdout.contains("0".repeat(40)),
                "missing parent sha leaked into log output: ${out.stdout}",
            )
        }

    @Test fun logBareCountShorthandWorksOnShallowRepo() =
        runTest {
            val fs = InMemoryFs()
            val tipSha = seedShallowRepo(fs)

            // `-5` shorthand (synonym for `-n 5`). Combined with shallow walk
            // it should produce exactly one line (only the tip is local).
            val out = runGit(fs, "/r", "log", "-5", "--oneline")
            assertEquals(0, out.rc, "git log -5 on shallow repo crashed: rc=${out.rc} stderr=${out.stderr}")
            val lines =
                out.stdout
                    .trim('\n')
                    .split('\n')
                    .filter { it.isNotBlank() }
            assertEquals(1, lines.size, "expected exactly 1 oneline entry on shallow repo; got: ${out.stdout}")
            assertTrue(
                lines[0].startsWith(tipSha.take(7)),
                "expected oneline to start with short sha; got: ${lines[0]}",
            )
        }
}
