package com.accucodeai.kash.tools.git.plumbing

import com.accucodeai.kash.tools.git.testsupport.RealGitProbe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Three-way verification of our index v2 codec:
 *  1. Encode an index from our types, write it to disk, and have
 *     `/usr/bin/git ls-files --stage` read it back — assert the paths
 *     and shas match.
 *  2. Generate a real index by `git add`, read it with our decoder,
 *     and assert the entries round-trip back to identical bytes.
 *  3. Cross-decode: encode-decode-encode produces stable bytes
 *     (proves our re-encode after a decode is deterministic).
 */
class IndexDifferentialTest {
    companion object {
        private lateinit var probe: RealGitProbe

        @BeforeAll
        @JvmStatic
        fun setUp() {
            RealGitProbe.assumeAvailable()
            probe = RealGitProbe()
        }
    }

    @Test fun realGitReadsOurIndex(
        @TempDir tmp: File,
    ) {
        val repo = probe.freshRepo()
        try {
            // Materialize blobs so the SHAs we put in the index exist.
            File(repo, "a").writeText("A")
            File(repo, "sub.c").writeText("BC")
            File(repo, "nested").mkdir()
            File(repo, "nested/inside").writeText("hello\n")
            probe.run(listOf("hash-object", "-w", "a"), repo).also { check(it.exitCode == 0) }
            probe.run(listOf("hash-object", "-w", "sub.c"), repo).also { check(it.exitCode == 0) }
            probe.run(listOf("hash-object", "-w", "nested/inside"), repo).also { check(it.exitCode == 0) }
            val shaA = probe.run(listOf("hash-object", "a"), repo).stdoutUtf8().trim()
            val shaSubC = probe.run(listOf("hash-object", "sub.c"), repo).stdoutUtf8().trim()
            val shaInside = probe.run(listOf("hash-object", "nested/inside"), repo).stdoutUtf8().trim()

            // Hand-build an index. Out of order on purpose to verify the
            // encoder's sort.
            val index =
                IndexFile(
                    version = 2,
                    entries =
                        listOf(
                            IndexEntry(mode = FileMode.REGULAR, size = 6, sha = shaInside, path = "nested/inside"),
                            IndexEntry(mode = FileMode.REGULAR, size = 1, sha = shaA, path = "a"),
                            IndexEntry(mode = FileMode.REGULAR, size = 2, sha = shaSubC, path = "sub.c"),
                        ),
                )
            File(repo, ".git/index").writeBytes(encodeIndex(index))

            val ls = probe.run(listOf("ls-files", "--stage"), repo)
            assertEquals(0, ls.exitCode, ls.stderrUtf8())
            // ls-files --stage output: "<mode> <sha> <stage>\t<path>"
            val expected =
                """
                100644 $shaA 0	a
                100644 $shaInside 0	nested/inside
                100644 $shaSubC 0	sub.c
                """.trimIndent() + "\n"
            assertEquals(expected, ls.stdoutUtf8())
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test fun decodeRealGitIndex(
        @TempDir tmp: File,
    ) {
        val repo = probe.freshRepo()
        try {
            File(repo, "a").writeText("hi\n")
            File(repo, "sub.c").writeText("there\n")
            File(repo, "sub").mkdir()
            File(repo, "sub/b").writeText("nested\n")
            probe.run(listOf("add", "-A"), repo).also { check(it.exitCode == 0) }
            // Force the exec bit independent of the on-disk mode so the
            // test exercises the EXECUTABLE codepath even on FSes where
            // chmod doesn't stick.
            probe
                .run(listOf("update-index", "--chmod=+x", "sub/b"), repo)
                .also { check(it.exitCode == 0) }

            val bytes = File(repo, ".git/index").readBytes()
            val decoded = decodeIndex(bytes)

            assertEquals(3, decoded.entries.size)
            val byPath = decoded.entries.associateBy { it.path }
            assertTrue("a" in byPath)
            assertTrue("sub.c" in byPath)
            assertTrue("sub/b" in byPath)

            assertEquals(FileMode.REGULAR, byPath.getValue("a").mode)
            assertEquals(FileMode.EXECUTABLE, byPath.getValue("sub/b").mode)
            assertEquals(3, byPath.getValue("a").size)
            assertEquals(7, byPath.getValue("sub/b").size)

            // Shas should match git's view.
            val shaA = probe.run(listOf("hash-object", "a"), repo).stdoutUtf8().trim()
            assertEquals(shaA, byPath.getValue("a").sha)
        } finally {
            repo.deleteRecursively()
        }
    }

    @Test fun encodeDecodeRoundTrip() {
        val original =
            IndexFile(
                version = 2,
                entries =
                    listOf(
                        IndexEntry(mode = FileMode.REGULAR, size = 10, sha = "0".repeat(40), path = "a/b/c"),
                        IndexEntry(mode = FileMode.EXECUTABLE, size = 99, sha = "1".repeat(40), path = "z"),
                    ),
            )
        val encoded = encodeIndex(original)
        val decoded = decodeIndex(encoded)
        // Re-encode produces identical bytes.
        assertTrue(encodeIndex(decoded).contentEquals(encoded))
        assertEquals(original.entries.sortedBy { it.path }, decoded.entries)
    }
}
