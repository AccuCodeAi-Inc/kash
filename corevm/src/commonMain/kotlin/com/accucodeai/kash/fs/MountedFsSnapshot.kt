package com.accucodeai.kash.fs

import kotlinx.serialization.Serializable

/**
 * Serializable capture of a [MountedFileSystem]'s state, label-aware.
 *
 * Only [FsLabel.USER] mounts whose backing [FileSystem] is an [InMemoryFs] are
 * captured into [userMounts]. Other USER mounts (host-backed, custom impls)
 * skip their contents but still appear in [mountManifest] so the restore
 * target can verify it has the same shape. Non-USER mounts (`ENGINE_CACHE`,
 * `HOST_BORROW`, `EPHEMERAL`) are listed in the manifest but never
 * snapshotted — engines regenerate them on first use; host borrows are out of
 * scope; ephemeral is gone by definition.
 *
 * Restore is *in place* against an existing [MountedFileSystem] whose mount
 * table matches the manifest. We do NOT rebuild the table from a snapshot —
 * the table is set up by the session's construction code (engines contribute
 * their cache mounts, etc.); snapshot just rehydrates the USER content.
 */
@Serializable
public data class MountedFsSnapshot(
    val userMounts: Map<String, FsSnapshot>,
    val mountManifest: List<MountManifestEntry>,
    /** Schema version for future-proofing. Bump on incompatible changes. */
    val version: Int = 1,
)

/** One entry per declared mount; used to verify restore-target shape. */
@Serializable
public data class MountManifestEntry(
    val mountPoint: String,
    val label: FsLabel,
    val readOnly: Boolean,
)

/**
 * Raised by [MountedFileSystem.restore] when the snapshot's manifest doesn't
 * line up with the target's mount table. Catch this to either reconstruct the
 * mount table or surface a clear error to the user.
 */
public class MountManifestMismatchException(
    public val expected: List<MountManifestEntry>,
    public val actual: List<MountManifestEntry>,
) : RuntimeException(
        "snapshot mount manifest does not match target:\n" +
            "  expected: $expected\n" +
            "  actual:   $actual",
    )
