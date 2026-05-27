package com.accucodeai.kash.tools.awk

/**
 * Runtime options for an awk program execution. Mirrors awk's CLI surface
 * the bits we expect to support — `-F` for field separator and `-v var=val`
 * for pre-assignments. Unset fields fall back to awk defaults (FS = " ").
 */
public data class AwkOptions(
    /** Initial value of `FS`. Default: a single space, meaning awk's
     *  default whitespace splitting. */
    public val fieldSeparator: String? = null,
    /** Pre-assignments visible inside `BEGIN` (awk's `-v` flag). */
    public val preAssignments: Map<String, String> = emptyMap(),
    /** Environment exposed to the program as `ENVIRON[…]`. POSIX awk
     *  snapshots this once at startup; subsequent host-side mutations
     *  aren't visible. Empty by default — the engine has no portable
     *  way to read the OS environment on its own. */
    public val environ: Map<String, String> = emptyMap(),
)
