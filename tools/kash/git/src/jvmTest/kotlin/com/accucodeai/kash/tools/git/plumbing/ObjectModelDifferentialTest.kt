package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

/**
 * Differential checks: for each shape we encode, ask `/usr/bin/git` to
 * produce the same object and assert SHA equality. Hard-coded SHA tests
 * in `ObjectModelTest` are the regression guard; this class re-derives
 * the truth from a live git on each run so newer git versions (which
 * might change defaults) are exercised too.
 */
class ObjectModelDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    @Test fun blobShaMatchesRealGitForArbitraryPayloads(
        @TempDir tmp: File,
    ) {
        val payloads =
            listOf(
                ByteArray(0),
                "x".encodeToByteArray(),
                "Hello, world!\n".encodeToByteArray(),
                ByteArray(257) { ((it * 7) xor 0x5a).toByte() },
            )
        for (p in payloads) {
            val expected =
                probe
                    .run(listOf("hash-object", "-t", "blob", "--stdin"), tmp, stdin = p)
                    .stdoutUtf8()
                    .trim()
            assertEquals(expected, blobSha(p), "blobSha mismatch for ${p.size}-byte payload")
        }
    }

    @Test fun treeShaMatchesRealGit(
        @TempDir tmp: File,
    ) {
        val repo = probe.freshRepo()
        try {
            File(repo, "a").writeText("A")
            File(repo, "sub.c").writeText("B")
            File(repo, "sub").mkdir()
            File(repo, "sub/inside").writeText("C")
            probe.run(listOf("add", "-A"), repo).also { check(it.exitCode == 0) { it.stderrUtf8() } }
            val expected = probe.run(listOf("write-tree"), repo).stdoutUtf8().trim()

            val aSha =
                probe.run(listOf("rev-parse", "HEAD:a"), repo).let {
                    if (it.exitCode == 0) it.stdoutUtf8().trim() else blobSha("A".encodeToByteArray())
                }
            val subCSha = blobSha("B".encodeToByteArray())
            val insideSha = blobSha("C".encodeToByteArray())
            val subTreeSha = treeSha(listOf(TreeEntry(FileMode.REGULAR, "inside", insideSha)))

            val ours =
                treeSha(
                    listOf(
                        TreeEntry(FileMode.REGULAR, "a", aSha),
                        TreeEntry(FileMode.REGULAR, "sub.c", subCSha),
                        TreeEntry(FileMode.TREE, "sub", subTreeSha),
                    ),
                )
            assertEquals(expected, ours)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test fun commitShaMatchesRealGit() {
        val repo = probe.freshRepo()
        try {
            File(repo, "f").writeText("hello\n")
            probe.run(listOf("add", "f"), repo).also { check(it.exitCode == 0) { it.stderrUtf8() } }
            val env =
                mapOf(
                    "GIT_AUTHOR_DATE" to "1700000000 +0000",
                    "GIT_COMMITTER_DATE" to "1700000000 +0000",
                )
            val commitResult = probe.run(listOf("commit", "-q", "-m", "msg"), repo, env = env)
            check(commitResult.exitCode == 0) { commitResult.stderrUtf8() }
            val expected = probe.run(listOf("rev-parse", "HEAD"), repo).stdoutUtf8().trim()
            val treeSha = probe.run(listOf("rev-parse", "HEAD^{tree}"), repo).stdoutUtf8().trim()

            val ours =
                commitSha(
                    CommitPayload(
                        tree = treeSha,
                        parents = emptyList(),
                        author = PersonStamp("Test User", "test@example.com", 1700000000, "+0000"),
                        committer = PersonStamp("Test User", "test@example.com", 1700000000, "+0000"),
                        message = "msg\n",
                    ),
                )
            assertEquals(expected, ours)
        } finally {
            repo.deleteRecursively()
        }
    }
}
