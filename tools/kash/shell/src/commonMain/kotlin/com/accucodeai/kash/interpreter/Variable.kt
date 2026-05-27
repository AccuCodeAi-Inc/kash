package com.accucodeai.kash.interpreter

/**
 * Per-name shell-variable record: one record per name carries the value
 * (a union, reinterpreted by attribute flags) plus the attribute set.
 *
 * The seven parallel maps that previously lived on [Interpreter]
 * (env / indexedArrays / assocArrays / varAttrs / readonlyVars /
 * arrayAssigned / localScopes per-frame) collapse into this type held in
 * [VarTable]. Behavioural rules that used to be enforced by side-by-side
 * mutations of those maps (scalar→array promotion, declared-but-unset
 * vs. assigned-empty, attr propagation on assignment) become invariants
 * on a single object — fixing whole classes of "the maps disagree" bugs.
 */
internal class Variable(
    val name: String,
    var value: VariableValue = VariableValue.Unset,
    val attrs: MutableSet<VarAttr> = mutableSetOf(),
    /**
     * Dynamic-read hook. When non-null, [scalarOrNull] returns
     * `getter()` rather than the stored [value]'s scalar. A
     * dynamic-variable read hook (bash's RANDOM/SECONDS) — used for
     * `RANDOM` (fresh each read), `SECONDS`, `LINENO`, `EPOCHSECONDS`,
     * `BASHPID`, `PPID`, `$-`, and any future special whose value can't
     * just sit in a string.
     */
    var getter: (() -> String)? = null,
    /**
     * Dynamic-write hook. Bash semantics: even special variables accept
     * assignment (e.g. `RANDOM=N` reseeds; `SECONDS=N` rebases the
     * elapsed counter). When non-null, scalar writes from
     * [Interpreter.setScalar] / `env[name] = v` route here instead of
     * storing into [value]. Null means "writes do nothing" for the
     * read-only specials (`BASHPID=42` is silently dropped, matching
     * bash's diagnostic-but-no-effect behaviour).
     */
    var setter: ((String) -> Unit)? = null,
    /** Indexed-array dynamic-read hook — synthesized arrays (FUNCNAME, BASH_LINENO, etc.). */
    var indexedGetter: (() -> Map<Int, String>)? = null,
    /** Associative-array dynamic-read hook. */
    var assocGetter: (() -> Map<String, String>)? = null,
    /**
     * True when the variable was created via `declare -a NAME` / `declare
     * -A NAME` / `local -a NAME` with NO `=value` — bash's `declare -p`
     * for such "attribute-only" arrays prints `declare -a NAME` (no `=()`),
     * distinguishing it from an empty assignment `NAME=()` which prints
     * `declare -a NAME=()`. Cleared on the first real assignment.
     */
    var attributeOnly: Boolean = false,
    /**
     * True when the variable's most recent assignment was an EMPTY array
     * literal via the declare/local/readonly/export builtin
     * (`declare -a foo=()`). bash's `${var@A}` transform omits `=()` for
     * this case, while plain `B=()` keeps it.
     */
    var declaredEmptyViaBuiltin: Boolean = false,
    /**
     * `getopts` scan offset WITHIN the current clustered-option argument
     * (`-xyz` → which of x/y/z is next). Only meaningful on the `OPTIND`
     * variable. Storing it here — rather than in a separate variable —
     * binds it to OPTIND's scope: `local`/`typeset OPTIND` gives a recursive
     * frame a fresh scan (a new [Variable] starts at 1) and restores the
     * caller's position on return, so recursive `getopts` can't corrupt the
     * caller's scan (mirrors bash keeping this offset internal to OPTIND).
     */
    var getoptsSubIndex: Int = 1,
) {
    /**
     * "Has a hook attached" — used by lookup helpers to know whether
     * the variable's "real" value lives in the stored [value] or has
     * to be computed on each read.
     */
    val isDynamic: Boolean get() = getter != null || indexedGetter != null || assocGetter != null

    val isSet: Boolean get() = value !is VariableValue.Unset || isDynamic
    val isScalar: Boolean get() = value is VariableValue.Scalar || getter != null
    val isIndexed: Boolean get() = value is VariableValue.Indexed || indexedGetter != null
    val isAssoc: Boolean get() = value is VariableValue.Assoc || assocGetter != null
    val isReadonly: Boolean get() = VarAttr.Readonly in attrs
    val isExported: Boolean get() = VarAttr.Export in attrs

    /** Scalar string, or null if the variable is unset or array-typed. */
    val scalarOrNull: String? get() = getter?.invoke() ?: (value as? VariableValue.Scalar)?.s

    /**
     * Mutable indexed-element map, or null if the variable isn't an
     * indexed array (including dynamic indexed arrays — those are
     * read-only and surface via [indexedView]).
     */
    val indexedOrNull: MutableMap<Int, String>? get() = (value as? VariableValue.Indexed)?.elements

    /**
     * Read-only indexed view — falls back to the dynamic [indexedGetter]
     * for synthesized arrays like FUNCNAME/BASH_LINENO. Use this for
     * reads; use [indexedOrNull] only when you intend to mutate.
     */
    val indexedView: Map<Int, String>?
        get() = indexedGetter?.invoke() ?: (value as? VariableValue.Indexed)?.elements

    /** Mutable assoc-element map; null for dynamic vars (use [assocView]). */
    val assocOrNull: LinkedHashMap<String, String>? get() = (value as? VariableValue.Assoc)?.elements

    /** Read-only assoc view — falls back to [assocGetter] for dynamic vars. */
    val assocView: Map<String, String>?
        get() = assocGetter?.invoke() ?: (value as? VariableValue.Assoc)?.elements

    /** Deep copy — used by [VarTable.deepCopy] for `(...)` subshells. */
    fun copy(): Variable =
        Variable(
            name = name,
            value = value.copy(),
            attrs = attrs.toMutableSet(),
            // Dynamic hooks are by reference — closures over the parent
            // [Interpreter]'s state. `(...)` subshells SHOULD see the
            // fork's own SECONDS/LINENO/etc., but since the fork's
            // session-startup hook re-installs these in its own init,
            // we don't propagate the parent's closures across.
            getter = null,
            setter = null,
            indexedGetter = null,
            assocGetter = null,
            // Carry the declare-builtin printing flags across the fork —
            // `declare -p` / `declare -a` in a pipe stage must report the
            // same "attribute-only" status the parent saw. Without this,
            // `declare -a b[256]; declare -a | grep b` prints
            // `declare -a b=()` instead of `declare -a b`.
            attributeOnly = attributeOnly,
            declaredEmptyViaBuiltin = declaredEmptyViaBuiltin,
            getoptsSubIndex = getoptsSubIndex,
        )
}
