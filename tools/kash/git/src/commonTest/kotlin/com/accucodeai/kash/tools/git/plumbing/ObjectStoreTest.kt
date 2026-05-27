package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.api.git.GitObjectResolver
import com.accucodeai.kash.fs.InMemoryFs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ObjectStoreTest {
    private fun freshStore(resolver: GitObjectResolver? = null): Pair<ObjectStore, InMemoryFs> {
        val fs = InMemoryFs()
        fs.mkdirs("/home/user/repo")
        val store = ObjectStore(RepoLayout("/home/user/repo"), fs, resolver)
        return store to fs
    }

    @Test fun writeAndReadRoundTrip() =
        runTest {
            val (store, _) = freshStore()
            val sha = store.write(ObjectType.BLOB, "hello".encodeToByteArray())
            assertEquals("b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0", sha)
            assertTrue(store.hasLocal(sha))
            assertEquals("hello", store.readBlob(sha).decodeToString())
        }

    @Test fun idempotentRewriteIsNoOp() =
        runTest {
            val (store, _) = freshStore()
            val sha1 = store.write(ObjectType.BLOB, "x".encodeToByteArray())
            val sha2 = store.write(ObjectType.BLOB, "x".encodeToByteArray())
            assertEquals(sha1, sha2)
        }

    @Test fun missingObjectThrows() =
        runTest {
            val (store, _) = freshStore()
            assertFailsWith<Exception> { store.read("0".repeat(40)) }
        }

    @Test fun resolverFallbackCachesObject() =
        runTest {
            val payload = "from-host".encodeToByteArray()
            val framed = framedObject(ObjectType.BLOB, payload)
            val sha = blobSha(payload)
            var calls = 0
            val resolver =
                GitObjectResolver { askedSha ->
                    calls++
                    if (askedSha == sha) framed else null
                }
            val (store, _) = freshStore(resolver)

            // First read: miss → resolver.
            val first = store.readBlob(sha)
            assertEquals(payload.toList(), first.toList())
            assertEquals(1, calls)
            assertTrue(store.hasLocal(sha), "object should be cached locally after resolve")

            // Second read: local hit, resolver untouched.
            val second = store.readBlob(sha)
            assertEquals(payload.toList(), second.toList())
            assertEquals(1, calls, "resolver should not be called again after cache")
        }

    @Test fun resolverShaMismatchRejected() =
        runTest {
            val askSha = "1".repeat(40)
            val resolver =
                GitObjectResolver { _ ->
                    framedObject(ObjectType.BLOB, "different".encodeToByteArray())
                }
            val (store, _) = freshStore(resolver)
            assertFailsWith<IllegalArgumentException> { store.read(askSha) }
        }

    @Test fun treeAndCommitRoundTrip() =
        runTest {
            val (store, _) = freshStore()
            val blobA = store.write(ObjectType.BLOB, "A".encodeToByteArray())
            val blobB = store.write(ObjectType.BLOB, "B".encodeToByteArray())
            val treeSha =
                store.write(
                    ObjectType.TREE,
                    encodeTree(
                        listOf(
                            TreeEntry(FileMode.REGULAR, "a", blobA),
                            TreeEntry(FileMode.REGULAR, "b", blobB),
                        ),
                    ),
                )
            val commit =
                CommitPayload(
                    tree = treeSha,
                    parents = emptyList(),
                    author = PersonStamp("T", "t@e", 1700000000, "+0000"),
                    committer = PersonStamp("T", "t@e", 1700000000, "+0000"),
                    message = "init\n",
                )
            val commitSha = store.write(ObjectType.COMMIT, encodeCommit(commit))

            val decodedTree = store.readTree(treeSha)
            assertEquals(listOf("a", "b"), decodedTree.map { it.name })
            val decodedCommit = store.readCommit(commitSha)
            assertEquals(commit, decodedCommit)
        }
}
