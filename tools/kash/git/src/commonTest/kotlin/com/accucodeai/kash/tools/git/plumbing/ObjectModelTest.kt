package com.accucodeai.kash.tools.git.plumbing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * SHAs compared against `/usr/bin/git hash-object` / `git write-tree` /
 * `git commit-tree` output. If any of these regress, our object framing
 * has drifted from real git's wire format.
 *
 * Capture script (run once when adding fixtures):
 * ```
 * git init -q gobj && cd gobj
 * git config user.email t@example.com && git config user.name Test
 * printf '' | git hash-object -t blob --stdin   # → e69de29...
 * printf 'hello' | git hash-object -t blob --stdin # → b6fc4c6...
 * # … etc.
 * ```
 */
class ObjectModelTest {
    @Test fun blobShaEmpty() {
        // git: well-known SHA of the empty blob.
        assertEquals(
            "e69de29bb2d1d6434b8b29ae775ad8c2e48c5391",
            blobSha(byteArrayOf()),
        )
    }

    @Test fun blobShaHello() {
        assertEquals(
            "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0",
            blobSha("hello".encodeToByteArray()),
        )
    }

    @Test fun blobBinary() {
        // Identical to sha1Hex of the framed bytes — compute and assert
        // self-consistency for an arbitrary binary payload. The byte-for-
        // byte agreement with real git is covered by the empty + ASCII
        // cases above; binary payloads exercise no new framing code.
        val data = ByteArray(64) { it.toByte() }
        val expected = objectSha(ObjectType.BLOB, data)
        assertEquals(expected, blobSha(data))
    }

    @Test fun treeWithDirectorySortOrder() {
        // Captured from `git write-tree` for:
        //   a       = blob "A"  (sha 8c7e5a6…)
        //   sub.c   = blob "B"  (sha 7371f47…)
        //   sub/    = tree     (sha 306dc17…)
        // Real git serializes them as: a, sub.c, sub — because directories
        // sort with an implicit trailing '/'.
        val entries =
            listOf(
                TreeEntry(FileMode.TREE, "sub", "306dc175dbef8bea1c04f0dadac3d80ea043cb42"),
                TreeEntry(FileMode.REGULAR, "a", "8c7e5a667f1b771847fe88c01c3de34413a1b220"),
                TreeEntry(FileMode.REGULAR, "sub.c", "7371f47a6f8bd23a8fa1a8b2a9479cdd76380e54"),
            )
        assertEquals("899adc219da372f770a79ba4d6db516847426334", treeSha(entries))
    }

    @Test fun treeRoundTrip() {
        val entries =
            listOf(
                TreeEntry(FileMode.TREE, "sub", "306dc175dbef8bea1c04f0dadac3d80ea043cb42"),
                TreeEntry(FileMode.REGULAR, "a", "8c7e5a667f1b771847fe88c01c3de34413a1b220"),
                TreeEntry(FileMode.EXECUTABLE, "run.sh", "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0"),
            )
        val bytes = encodeTree(entries)
        val decoded = decodeTree(bytes)
        // sorted on the way in
        assertEquals(listOf("a", "run.sh", "sub"), decoded.map { it.name })
        assertEquals(entries.sorted(), decoded)
    }

    @Test fun treeRejectsDuplicateNames() {
        val sha = "8c7e5a667f1b771847fe88c01c3de34413a1b220"
        assertFailsWith<IllegalArgumentException> {
            encodeTree(
                listOf(
                    TreeEntry(FileMode.REGULAR, "dup", sha),
                    TreeEntry(FileMode.EXECUTABLE, "dup", sha),
                ),
            )
        }
    }

    @Test fun treeRejectsHostileEntryNames() {
        // CVE-2014-9390 class: real git refuses tree entries named
        // `.`, `..`, or `.git` (case-insensitive). kash must too —
        // otherwise a hostile remote can plant `.git/hooks/pre-commit`
        // inside the kash session via a `materializeWorkTree` /
        // `pullTreeRecursive` path that honors the attacker-controlled
        // tree name verbatim.
        val sha = "8c7e5a667f1b771847fe88c01c3de34413a1b220"
        for (bad in listOf(".", "..", ".git", ".Git", ".GIT")) {
            assertFailsWith<IllegalArgumentException>("name '$bad' should be rejected") {
                TreeEntry(FileMode.REGULAR, bad, sha)
            }
        }
        // And the decode path must reject too — we can't let an
        // attacker forge bytes that bypass the constructor. Build a
        // wire entry naming `.git` (format `<mode> <name>\0<sha20>`)
        // and verify decodeTree throws.
        val hostilePayload =
            "100644 .git".encodeToByteArray() +
                byteArrayOf(0) +
                ByteArray(20) // 20 zero sha bytes
        assertFailsWith<IllegalArgumentException> { decodeTree(hostilePayload) }
    }

    @Test fun commitSha_simple() {
        // Captured: `git commit -m "hello"` with pinned identity + dates
        // produced commit SHA 1fc679ef… on the tree 899adc21….
        val commit =
            CommitPayload(
                tree = "899adc219da372f770a79ba4d6db516847426334",
                parents = emptyList(),
                author = PersonStamp("Test User", "t@example.com", 1700000000, "+0000"),
                committer = PersonStamp("Test User", "t@example.com", 1700000000, "+0000"),
                message = "hello\n",
            )
        assertEquals("1fc679ef22a024c0c6c11b756d9b182fd3cd0299", commitSha(commit))
    }

    @Test fun commitSha_multilineMessageAndParent() {
        // `git commit -m "title" -m "body line 1"` produces message
        // "title\n\nbody line 1\n" with one parent.
        val commit =
            CommitPayload(
                tree = "98bb6e58e3a5b66ed5a0ae91c55863243b679be2",
                parents = listOf("1fc679ef22a024c0c6c11b756d9b182fd3cd0299"),
                author = PersonStamp("Test User", "t@example.com", 1700000001, "+0000"),
                committer = PersonStamp("Test User", "t@example.com", 1700000001, "+0000"),
                message = "title\n\nbody line 1\n",
            )
        assertEquals("ef571d96089675e4641ccea7956afa05e3038721", commitSha(commit))
    }

    @Test fun commitRoundTrip() {
        val commit =
            CommitPayload(
                tree = "899adc219da372f770a79ba4d6db516847426334",
                parents = listOf("1fc679ef22a024c0c6c11b756d9b182fd3cd0299"),
                author = PersonStamp("Test User", "t@example.com", 1700000000, "+0000"),
                committer = PersonStamp("Test User", "t@example.com", 1700000000, "+0000"),
                message = "title\n\nbody\n",
            )
        val bytes = encodeCommit(commit)
        val decoded = decodeCommit(bytes)
        assertEquals(commit, decoded)
    }

    @Test fun frameRoundTrip() {
        val payload = "hello".encodeToByteArray()
        val framed = framedObject(ObjectType.BLOB, payload)
        val parsed = parseFramedObject(framed)
        assertEquals(ObjectType.BLOB, parsed.type)
        assertEquals(payload.toList(), parsed.payload.toList())
    }
}
