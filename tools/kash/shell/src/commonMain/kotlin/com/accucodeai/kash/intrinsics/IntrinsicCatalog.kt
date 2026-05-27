package com.accucodeai.kash.intrinsics

/**
 * The shell's intrinsic verbs (`set`, `cd`-no-wait, `export`, `declare`, …).
 *
 * Intrinsics are NOT [com.accucodeai.kash.api.CommandSpec]s and don't flow
 * through the [com.accucodeai.kash.api.CommandRegistry]: they can't fork to a
 * separate process, can't be invoked by a `KashMachine.spawn`, and require
 * mutable access to interpreter state (positional parameters, the function
 * table, control-flow exceptions, the parser) that the public `Command`
 * interface deliberately doesn't expose. The interpreter dispatches them
 * directly via [com.accucodeai.kash.interpreter.runIntrinsic].
 *
 * This catalog is the single source of truth so:
 *  - resolveCommand consults one place before falling through to functions / PATH
 *  - `type` and `command -v` print intrinsics with the same metadata
 *    they execute under
 */
public data class IntrinsicEntry(
    val name: String,
    val isSpecial: Boolean,
    /** Bash extension (vs. POSIX). Used by `type`. */
    val isBashExt: Boolean = false,
)

public object IntrinsicCatalog {
    public val entries: List<IntrinsicEntry> =
        listOf(
            // POSIX special builtins — assignment-prefix persists, errors abort.
            IntrinsicEntry(":", isSpecial = true),
            IntrinsicEntry("eval", isSpecial = true),
            IntrinsicEntry("export", isSpecial = true),
            IntrinsicEntry("readonly", isSpecial = true),
            IntrinsicEntry("return", isSpecial = true),
            IntrinsicEntry("exit", isSpecial = true),
            IntrinsicEntry("set", isSpecial = true),
            IntrinsicEntry("exec", isSpecial = true),
            IntrinsicEntry("times", isSpecial = true),
            IntrinsicEntry("shift", isSpecial = true),
            IntrinsicEntry("unset", isSpecial = true),
            IntrinsicEntry("trap", isSpecial = true),
            // POSIX `.` (dot): in-shell script execution. Bash `source` is its alias.
            IntrinsicEntry(".", isSpecial = true),
            IntrinsicEntry("source", isSpecial = false, isBashExt = true),
            // Regular intrinsics — POSIX-style scoping, no abort-on-error.
            IntrinsicEntry("declare", isSpecial = false, isBashExt = true),
            IntrinsicEntry("typeset", isSpecial = false, isBashExt = true),
            IntrinsicEntry("local", isSpecial = false, isBashExt = true),
            IntrinsicEntry("type", isSpecial = false),
            IntrinsicEntry("command", isSpecial = false),
            IntrinsicEntry("hash", isSpecial = false),
            IntrinsicEntry("umask", isSpecial = false),
            IntrinsicEntry("ulimit", isSpecial = false, isBashExt = true),
            // POSIX §2.14: `break` and `continue` are special builtins. The
            // "special" distinction is observable via `type NAME` in POSIX
            // mode ("special shell builtin") and via the §2.8.1 abort-on-
            // preparation-error precedence. Listed in
            // [Interpreter.NON_ABORTING_SPECIAL_BUILTINS] because plain
            // non-zero exit (e.g. `break` at top-level) shouldn't abort.
            IntrinsicEntry("break", isSpecial = true),
            IntrinsicEntry("continue", isSpecial = true),
            IntrinsicEntry("wait", isSpecial = false),
            IntrinsicEntry("jobs", isSpecial = false),
            IntrinsicEntry("kill", isSpecial = false),
            IntrinsicEntry("fg", isSpecial = false),
            IntrinsicEntry("bg", isSpecial = false),
            IntrinsicEntry("disown", isSpecial = false, isBashExt = true),
            IntrinsicEntry("suspend", isSpecial = false, isBashExt = true),
            IntrinsicEntry("alias", isSpecial = false),
            IntrinsicEntry("unalias", isSpecial = false),
            IntrinsicEntry("getopts", isSpecial = false),
            // POSIX `read` — writes into caller scope (env + indexed arrays
            // for `-a`), so it must run in-process. Living in the catalog
            // (rather than as a registered Command) lets the intrinsic
            // reach `indexedArrays` directly for `-a NAME` array writes.
            IntrinsicEntry("read", isSpecial = false),
            IntrinsicEntry("shopt", isSpecial = false, isBashExt = true),
            IntrinsicEntry("compgen", isSpecial = false, isBashExt = true),
            IntrinsicEntry("complete", isSpecial = false, isBashExt = true),
            IntrinsicEntry("compopt", isSpecial = false, isBashExt = true),
            IntrinsicEntry("builtin", isSpecial = false, isBashExt = true),
            // Bash builtins beyond the POSIX special / regular sets.
            IntrinsicEntry("caller", isSpecial = false, isBashExt = true),
            IntrinsicEntry("let", isSpecial = false, isBashExt = true),
            IntrinsicEntry("logout", isSpecial = false, isBashExt = true),
            IntrinsicEntry("enable", isSpecial = false, isBashExt = true),
            IntrinsicEntry("mapfile", isSpecial = false, isBashExt = true),
            IntrinsicEntry("readarray", isSpecial = false, isBashExt = true),
            IntrinsicEntry("dirs", isSpecial = false, isBashExt = true),
            IntrinsicEntry("pushd", isSpecial = false, isBashExt = true),
            IntrinsicEntry("popd", isSpecial = false, isBashExt = true),
            IntrinsicEntry("history", isSpecial = false, isBashExt = true),
            // `fc` is POSIX.
            IntrinsicEntry("fc", isSpecial = false),
            IntrinsicEntry("help", isSpecial = false, isBashExt = true),
            IntrinsicEntry("bind", isSpecial = false, isBashExt = true),
        )

    public val byName: Map<String, IntrinsicEntry> = entries.associateBy { it.name }

    public val names: Set<String> = byName.keys
}
