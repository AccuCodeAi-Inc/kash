package com.accucodeai.kash.tools.getconf

/**
 * Static table of POSIX-defined configuration values. Values are taken
 * directly from the POSIX.1-2017 specification (IEEE Std 1003.1-2017) and
 * its minimum-acceptable-value constants. They are *not* host-derived — kash
 * reports its own POSIX-conformance choices, not the underlying OS's limits.
 *
 * Returned as strings so this map is the single source of truth for the
 * `getconf -a` formatter; numeric clients parse on demand.
 *
 * `_POSIX_*` boolean-style symbols use "200809L" to indicate "supported".
 *
 * `PATH` is a sentinel — the runtime fills it from `$PATH` (with a fallback
 * to a reasonable default).
 */
public object GetconfTable {
    public const val PATH_SENTINEL: String = "@PATH@"

    public val TABLE: Map<String, String> =
        linkedMapOf(
            // POSIX version constants.
            "_POSIX_VERSION" to "200809",
            "_POSIX2_VERSION" to "200809",
            "_POSIX2_C_BIND" to "200809",
            "_POSIX2_C_DEV" to "200809",
            "_POSIX2_SW_DEV" to "200809",
            "_POSIX2_UPE" to "200809",
            "_POSIX_C_LANG_SUPPORT" to "200809",
            "_POSIX_THREADS" to "200809",
            "_POSIX_FORK" to "200809",
            "_POSIX_JOB_CONTROL" to "1",
            "_POSIX_SAVED_IDS" to "1",
            // System minima / "as-implemented" values.
            "ARG_MAX" to "4096",
            "CHILD_MAX" to "32",
            "LINE_MAX" to "2048",
            "NAME_MAX" to "255",
            "PATH_MAX" to "4096",
            "OPEN_MAX" to "64",
            "PAGESIZE" to "4096",
            "_SC_PAGE_SIZE" to "4096",
            "PAGE_SIZE" to "4096",
            "_SC_CLK_TCK" to "100",
            "CLK_TCK" to "100",
            // POSIX-spec compile-time minima.
            "_POSIX_NAME_MAX" to "14",
            "_POSIX_PATH_MAX" to "256",
            "_POSIX_ARG_MAX" to "4096",
            "_POSIX_OPEN_MAX" to "20",
            "_POSIX_CHILD_MAX" to "25",
            "_POSIX_LINE_MAX" to "2048",
            // Confstr keys.
            "PATH" to PATH_SENTINEL,
            "_CS_PATH" to PATH_SENTINEL,
            "_CS_POSIX_V7_ILP32_OFF32_CFLAGS" to "",
            "_CS_POSIX_V7_ILP32_OFF32_LDFLAGS" to "",
            "_CS_POSIX_V7_ILP32_OFF32_LIBS" to "",
            // System sysconfs commonly queried.
            "_SC_OPEN_MAX" to "64",
            "_SC_NPROCESSORS_ONLN" to "1",
            "_SC_NPROCESSORS_CONF" to "1",
        )
}
