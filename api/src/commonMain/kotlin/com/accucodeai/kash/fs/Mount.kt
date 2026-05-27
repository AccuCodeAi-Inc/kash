package com.accucodeai.kash.fs

/**
 * A mount binding: a [FileSystem] attached at [mountPoint] inside a
 * [MountedFileSystem] route table, tagged with a persistence [label] and an
 * optional [readOnly] flag.
 *
 * `mountPoint` is normalized via [Paths.normalize] at construction — pass
 * `"/tmp"` or `"/tmp/"`; both end up as `/tmp`. Must be absolute.
 *
 * The [readOnly] flag is enforced by the router, not by the underlying
 * [fs] — so the same [FileSystem] instance can be mounted writable at one
 * path and read-only at another. Modeled after Docker's `:ro` bind flag.
 */
public data class Mount(
    val mountPoint: String,
    val fs: FileSystem,
    val label: FsLabel,
    val readOnly: Boolean = false,
)

/**
 * Thrown when an operation would touch two distinct mounts (e.g. rename
 * across mount boundaries). Analogous to POSIX `EXDEV`. Tools that want
 * cross-mount semantics (`mv`, `cp -l`) must catch and fall back to
 * copy + delete.
 *
 * No `MountedFileSystem` method throws this in v1 because the [FileSystem]
 * interface has no rename op. Exposed here so the future `:tools:mv` can
 * use it.
 */
public class CrossMountException(
    public val from: String,
    public val to: String,
) : RuntimeException("operation crosses mount boundary: $from -> $to (analogous to EXDEV)")

/** Thrown by the router when a write op targets a read-only mount. */
public class ReadOnlyMountException(
    public val path: String,
) : RuntimeException("$path is on a read-only mount")
