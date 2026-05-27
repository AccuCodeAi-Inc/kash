package com.accucodeai.kash.interpreter

/**
 * Per-variable attribute flags set by `declare`/`typeset`/`readonly`/`export`.
 * Bash variables are not just name→value pairs: each carries a small set of
 * sticky attributes that influence subsequent assignments and expansions.
 */
internal enum class VarAttr {
    /** `declare -u` — uppercase on every assignment. */
    Upper,

    /** `declare -l` — lowercase on every assignment. */
    Lower,

    /** `declare -c` — capitalize first char on every assignment. */
    Capitalize,

    /** `declare -i` — assignments are evaluated as arithmetic. (Not yet enforced.) */
    Integer,

    /** `declare -a` — variable is a sparse indexed array. */
    Indexed,

    /** `declare -A` — variable is an associative array. */
    Associative,

    /** `declare -r` — write attempts return non-zero. */
    Readonly,

    /** `declare -x` — variable is exported to child processes. */
    Export,

    /**
     * `declare -n NAME=TARGET` (alias `typeset -n`). The variable's
     * stored scalar holds the *name* of another variable; reads and
     * writes transparently follow one level of indirection. Mirrors
     * bash's nameref attribute. Chained namerefs
     * resolve recursively with cycle detection.
     */
    NameRef,
}
