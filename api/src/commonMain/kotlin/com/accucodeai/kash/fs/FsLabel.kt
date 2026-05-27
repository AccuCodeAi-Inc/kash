package com.accucodeai.kash.fs

/**
 * Persistence category for a [Mount]. Lets a snapshot/restore layer know
 * which mounts to serialize, which to skip-and-regenerate, and which are
 * out of scope.
 *
 * Modeled on Docker's volume/bind/tmpfs split — the same axis ("how should
 * this data outlive the session?") but with explicit labels rather than
 * implicit mount-type semantics.
 *
 * **Note:** this is the *persistence* axis. A separate caching axis (is
 * this data content-addressed? globally replicable?) is intentionally not
 * folded in here — that's a future label set.
 */
public enum class FsLabel {
    /** Script-owned state. Snapshotted. The default for the root mount. */
    USER,

    /**
     * Cache derivable from engine state (e.g. extracted Python stdlib for
     * GraalPy). Skipped on snapshot; regenerated on first use after restore.
     */
    ENGINE_CACHE,

    /**
     * Opt-in passthrough to the real host filesystem. Out of snapshot
     * scope — what's there is what's on the host, not kash state.
     */
    HOST_BORROW,

    /**
     * Memory-only scratch. Never snapshotted; evaporates at session end.
     */
    EPHEMERAL,

    /**
     * System utilities surfaced via [com.accucodeai.kash.fs.ToolsFs] at
     * `/usr/bin`. Stateless — every entry is derived from the
     * [com.accucodeai.kash.api.CommandRegistry]. Skipped on snapshot; the
     * mount is regenerated on each session.
     */
    SYSTEM_BIN,
}
