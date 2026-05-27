package com.accucodeai.kash.fs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun buildSessionLikeMfs(): MountedFileSystem {
    // Three mount labels — proves the label-aware filter actually filters.
    // For the test we use InMemoryFs as the backing for the ENGINE_CACHE
    // mount too; the label, not the backing, is what drives snapshot
    // exclusion.
    return MountedFileSystem(
        listOf(
            Mount("/", InMemoryFs(), FsLabel.USER),
            Mount("/.cache/engine", InMemoryFs(), FsLabel.ENGINE_CACHE),
            Mount("/tmp/scratch", InMemoryFs(), FsLabel.EPHEMERAL),
        ),
    )
}

class MountedFileSystemSnapshotTest {
    @Test
    fun `snapshot captures USER content only`() =
        runTest {
            val mfs = buildSessionLikeMfs()
            mfs.writeBytes("/home/user/data.txt", "user-data".encodeToByteArray())
            mfs.writeBytes("/.cache/engine/cache.bin", "engine-cache".encodeToByteArray())
            mfs.writeBytes("/tmp/scratch/temp.txt", "ephemeral".encodeToByteArray())

            val snap = mfs.snapshot()

            // USER mount content is present.
            assertTrue("/" in snap.userMounts, "expected '/' in userMounts: ${snap.userMounts.keys}")
            val userFiles = snap.userMounts["/"]!!.files
            assertTrue(
                userFiles.keys.any { it == "/home/user/data.txt" },
                "USER content missing; got keys: ${userFiles.keys}",
            )

            // ENGINE_CACHE and EPHEMERAL content NOT captured.
            assertFalse("/.cache/engine" in snap.userMounts, "ENGINE_CACHE content leaked into snapshot")
            assertFalse("/tmp/scratch" in snap.userMounts, "EPHEMERAL content leaked into snapshot")
        }

    @Test
    fun `manifest lists every mount with its label`() =
        runTest {
            val mfs = buildSessionLikeMfs()
            val snap = mfs.snapshot()
            val byLabel = snap.mountManifest.associate { it.mountPoint to it.label }
            assertEquals(FsLabel.USER, byLabel["/"])
            assertEquals(FsLabel.ENGINE_CACHE, byLabel["/.cache/engine"])
            assertEquals(FsLabel.EPHEMERAL, byLabel["/tmp/scratch"])
        }

    @Test
    fun `restore rehydrates USER content in place`() =
        runTest {
            val source = buildSessionLikeMfs()
            source.writeBytes("/home/user/persistent.txt", "persistent".encodeToByteArray())
            source.writeBytes("/home/user/another.bin", byteArrayOf(1, 2, 3, 4))
            val snap = source.snapshot()

            // Fresh target with the same mount layout — engines would re-register
            // their cache mounts at session construction; the user/library
            // re-declares HOST_BORROW mounts. We mimic that here.
            val target = buildSessionLikeMfs()
            assertFalse(target.exists("/home/user/persistent.txt"))

            target.restore(snap)

            assertTrue(target.exists("/home/user/persistent.txt"))
            assertEquals("persistent", target.readBytes("/home/user/persistent.txt").decodeToString())
            assertContentEquals(byteArrayOf(1, 2, 3, 4), target.readBytes("/home/user/another.bin"))
        }

    @Test
    fun `restore does not touch non-USER mounts`() =
        runTest {
            val source = buildSessionLikeMfs()
            source.writeBytes("/home/user/x", "x".encodeToByteArray())
            val snap = source.snapshot()

            val target = buildSessionLikeMfs()
            // Seed the ENGINE_CACHE mount in the target — restore should leave it alone.
            target.writeBytes("/.cache/engine/precomputed.bin", "live-cache".encodeToByteArray())

            target.restore(snap)

            assertTrue(target.exists("/home/user/x"))
            // Engine cache content survived the restore unchanged.
            assertEquals("live-cache", target.readBytes("/.cache/engine/precomputed.bin").decodeToString())
        }

    @Test
    fun `mismatched manifest throws`() =
        runTest {
            val source = buildSessionLikeMfs()
            val snap = source.snapshot()

            // Target with different mount table — missing the EPHEMERAL mount.
            val target =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/.cache/engine", InMemoryFs(), FsLabel.ENGINE_CACHE),
                    ),
                )
            assertFailsWith<MountManifestMismatchException> { target.restore(snap) }
        }

    @Test
    fun `round trip preserves mode and mtime`() =
        runTest {
            val source = buildSessionLikeMfs()
            source.writeBytes("/home/user/script.sh", "echo hi".encodeToByteArray())
            source.chmod("/home/user/script.sh", 0b111_101_101)
            val srcMode = source.stat("/home/user/script.sh").mode
            val snap = source.snapshot()

            val target = buildSessionLikeMfs()
            target.restore(snap)

            assertEquals(srcMode, target.stat("/home/user/script.sh").mode)
        }

    // ---- Vuln #5: snapshot keys are normalized on restore ----

    @Test
    fun `restore normalizes dotted keys in snapshot`() =
        runTest {
            val target = buildSessionLikeMfs()
            // Craft a snapshot whose dirs/files keys contain `..` segments. Pre-fix,
            // these keys went into the maps verbatim; lookups via `exists()` would
            // normalize the query and find weird matches (or none). With the fix,
            // keys are normalized at restore time and the snapshot's `/legit/../escape`
            // ends up stored under the canonical `/escape` key.
            val malicious =
                MountedFsSnapshot(
                    userMounts =
                        mapOf(
                            "/" to
                                FsSnapshot(
                                    files =
                                        mapOf(
                                            "/legit/../escape" to
                                                FileEntry(
                                                    bytes = "spoof".encodeToByteArray(),
                                                    mode = 0b110_100_100,
                                                    mtime = 0,
                                                ),
                                        ),
                                    dirs = emptyMap(),
                                ),
                        ),
                    mountManifest = target.mounts().map { MountManifestEntry(it.mountPoint, it.label, it.readOnly) },
                )
            target.restore(malicious)
            // The malicious key was un-normalized. After restore, lookups for the
            // *canonical* path find the entry — i.e. the un-normalized key was
            // collapsed to `/escape`, not stored verbatim.
            assertTrue(target.exists("/escape"), "normalized canonical path should resolve")
            assertEquals("spoof", target.readBytes("/escape").decodeToString())
            // The un-normalized form is also accepted by exists() (kash normalizes
            // queries at lookup) but only because both resolve to the same key.
            assertTrue(target.exists("/legit/../escape"), "both forms resolve to same canonical key")
        }

    @Test
    fun `read-only flag is part of the manifest`() =
        runTest {
            val mfs =
                MountedFileSystem(
                    listOf(
                        Mount("/", InMemoryFs(), FsLabel.USER),
                        Mount("/ro", InMemoryFs(), FsLabel.ENGINE_CACHE, readOnly = true),
                    ),
                )
            val snap = mfs.snapshot()
            val roEntry = snap.mountManifest.single { it.mountPoint == "/ro" }
            assertTrue(roEntry.readOnly)
        }
}
