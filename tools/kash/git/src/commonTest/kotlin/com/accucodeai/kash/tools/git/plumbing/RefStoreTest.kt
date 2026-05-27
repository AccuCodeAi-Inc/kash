package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefStoreTest {
    private fun freshStore(): Pair<RefStore, InMemoryFs> {
        val fs = InMemoryFs()
        fs.mkdirs("/home/user/repo")
        return RefStore(RepoLayout("/home/user/repo"), fs) to fs
    }

    private val sha = "1234567890123456789012345678901234567890"
    private val sha2 = "abcdefabcdefabcdefabcdefabcdefabcdefabcd"

    @Test fun symbolicHeadRoundTrip() =
        runTest {
            val (refs, _) = freshStore()
            refs.writeHeadSymbolic("refs/heads/main")
            val head = refs.readHead()
            assertEquals(RefStore.Head.Symbolic("refs/heads/main"), head)
            // resolveHead before the ref exists → null
            assertNull(refs.resolveHead())
            refs.writeRef("refs/heads/main", sha)
            assertEquals(sha, refs.resolveHead())
        }

    @Test fun detachedHead() =
        runTest {
            val (refs, _) = freshStore()
            refs.writeHeadDetached(sha)
            assertEquals(RefStore.Head.Detached(sha), refs.readHead())
            assertEquals(sha, refs.resolveHead())
        }

    @Test fun packedRefsFallback() =
        runTest {
            val (refs, fs) = freshStore()
            fs.mkdirs("/home/user/repo/.git")
            fs.writeBytes(
                "/home/user/repo/.git/packed-refs",
                (
                    "# pack-refs with: peeled fully-peeled sorted\n" +
                        "$sha refs/heads/main\n" +
                        "$sha2 refs/tags/v1\n" +
                        "^${"0".repeat(40)}\n"
                ).encodeToByteArray(),
            )
            assertEquals(sha, refs.resolve("refs/heads/main"))
            assertEquals(sha2, refs.resolve("refs/tags/v1"))
            assertNull(refs.resolve("refs/heads/missing"))
        }

    @Test fun looseWinsOverPacked() =
        runTest {
            val (refs, fs) = freshStore()
            fs.mkdirs("/home/user/repo/.git")
            fs.writeBytes(
                "/home/user/repo/.git/packed-refs",
                "$sha refs/heads/main\n".encodeToByteArray(),
            )
            refs.writeRef("refs/heads/main", sha2)
            assertEquals(sha2, refs.resolve("refs/heads/main"))
        }

    @Test fun listRefsMergesLooseAndPacked() =
        runTest {
            val (refs, fs) = freshStore()
            fs.mkdirs("/home/user/repo/.git")
            fs.writeBytes(
                "/home/user/repo/.git/packed-refs",
                "$sha refs/heads/main\n$sha2 refs/heads/old\n".encodeToByteArray(),
            )
            refs.writeRef("refs/heads/feature", sha)
            val result = refs.listRefs("refs/heads").toMap()
            assertEquals(setOf("refs/heads/main", "refs/heads/old", "refs/heads/feature"), result.keys)
            assertEquals(sha, result["refs/heads/feature"])
            assertEquals(sha, result["refs/heads/main"])
        }
}
