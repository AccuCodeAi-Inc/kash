package com.accucodeai.kash.completion

/**
 * Per-command completion specification registered by the bash `complete`
 * builtin. The interpreter keeps a map of name → [CompleteSpec]; when
 * [ShellCompleter] sees a TAB on an argument of a registered command, it
 * consults the spec to produce candidates instead of falling back to the
 * default filename/command heuristic.
 *
 * Mirrors bash's `compspec` (see `pcomplete.h`). Field ordering matches the
 * canonical `complete -p` print order: options first (alphabetical), then
 * single-letter action flags, then long actions, then -G/-W/-X/-P/-S, then
 * -F/-C, then the name.
 */
public data class CompleteSpec(
    val actions: Set<CompleteAction> = emptySet(),
    val options: Set<CompleteOption> = emptySet(),
    /** -G globpat */
    val glob: String? = null,
    /** -W wordlist (split on IFS at completion time) */
    val wordlist: String? = null,
    /** -X filterpat (negative filter: candidates matching the pat are EXCLUDED). */
    val filter: String? = null,
    /** -P prefix prepended to each candidate */
    val prefix: String? = null,
    /** -S suffix appended to each candidate */
    val suffix: String? = null,
    /** -F function-name — shell function returning candidates via $COMPREPLY */
    val function: String? = null,
    /** -C command — runs the command, splits stdout on newlines */
    val command: String? = null,
)

/**
 * The completion "action" — what kind of names to enumerate. Bash defines
 * these via `-a`/`-b`/... single-letter flags or `-A <name>`.
 */
public enum class CompleteAction(
    /** Single-letter flag (`-a`/`-b`/...) or null if `-A name`-only. */
    public val flag: Char?,
    /** `-A` long name. */
    public val longName: String,
) {
    Alias('a', "alias"),
    ArrayVar(null, "arrayvar"),
    Binding(null, "binding"),
    Builtin('b', "builtin"),
    Command('c', "command"),
    Directory('d', "directory"),
    Disabled(null, "disabled"),
    Enabled(null, "enabled"),
    Export('e', "export"),
    File('f', "file"),
    Function(null, "function"),
    Group('g', "group"),
    HelpTopic(null, "helptopic"),
    Hostname(null, "hostname"),
    Job('j', "job"),
    Keyword('k', "keyword"),
    Running(null, "running"),
    Service('s', "service"),
    SetOpt(null, "setopt"),
    Shopt(null, "shopt"),
    Signal(null, "signal"),
    Stopped(null, "stopped"),
    User('u', "user"),
    Variable('v', "variable"),
    ;

    public companion object {
        public fun fromFlag(ch: Char): CompleteAction? = entries.firstOrNull { it.flag == ch }

        public fun fromLong(name: String): CompleteAction? = entries.firstOrNull { it.longName == name }
    }
}

/**
 * `-o` completion options. Names match bash exactly so `complete -o name`
 * round-trips through our spec.
 */
public enum class CompleteOption(
    public val optionName: String,
) {
    BashDefault("bashdefault"),
    Default("default"),
    DirNames("dirnames"),
    FileNames("filenames"),
    NoQuote("noquote"),
    NoSort("nosort"),
    NoSpace("nospace"),
    PlusDirs("plusdirs"),
    ;

    public companion object {
        public fun fromName(name: String): CompleteOption? = entries.firstOrNull { it.optionName == name }
    }
}

/**
 * Format [spec] as a `complete <flags> <name>` line for `complete -p`
 * output. Mirrors bash's `print_one_completion` order so the output is
 * shell-replayable.
 */
public fun formatCompleteSpec(
    spec: CompleteSpec,
    name: String?,
): String {
    val sb = StringBuilder("complete")
    // -o options first (sorted alphabetically by option name, which is what
    // bash does at print time even when registration was in a different
    // order).
    for (opt in spec.options.sortedBy { it.optionName }) {
        sb.append(" -o ").append(opt.optionName)
    }
    // Single-letter action flags, in CompleteAction enum order so a, b, c,
    // d, e, f, g, j, k, s, u, v.
    for (act in CompleteAction.entries) {
        if (act.flag != null && act in spec.actions) {
            sb.append(" -").append(act.flag)
        }
    }
    // Long actions via -A.
    for (act in CompleteAction.entries) {
        if (act.flag == null && act in spec.actions) {
            sb.append(" -A ").append(act.longName)
        }
    }
    spec.glob?.let { sb.append(" -G ").append(shellSingleQuote(it)) }
    spec.wordlist?.let { sb.append(" -W ").append(shellSingleQuote(it)) }
    spec.filter?.let { sb.append(" -X ").append(shellSingleQuote(it)) }
    spec.prefix?.let { sb.append(" -P ").append(shellSingleQuote(it)) }
    spec.suffix?.let { sb.append(" -S ").append(shellSingleQuote(it)) }
    spec.function?.let { sb.append(" -F ").append(it) }
    spec.command?.let { sb.append(" -C ").append(shellSingleQuote(it)) }
    if (name != null) sb.append(' ').append(name)
    return sb.toString()
}

/**
 * Bash's compspec hash table size. Must be a power of two so the bucket
 * index is computed as `hash & (N-1)`. Bash never grows the compspec table
 * — completion has far fewer entries than the rehash threshold of 1024
 * (= N×2), so 512 is sustained.
 */
internal const val BASH_COMPSPEC_BUCKETS: Int = 512

/**
 * Bash's string hash: FNV-1 with the canonical 32-bit offset basis
 * [BASH_FNV_OFFSET] and prime [BASH_FNV_PRIME], iterating bytes of the
 * key and folding `hash = (hash * PRIME) xor byte`.
 *
 * We compute in signed `Int` arithmetic; Kotlin's 2's-complement overflow
 * matches C's unsigned overflow at 32 bits, so the bottom 9 bits we mask
 * with `& 511` are identical to bash's result.
 */
internal const val BASH_FNV_OFFSET: Int = -2128831035 // 2166136261 as signed Int
internal const val BASH_FNV_PRIME: Int = 16777619

internal fun bashHashString(s: String): Int {
    var i = BASH_FNV_OFFSET
    for (c in s) {
        i = (i * BASH_FNV_PRIME) xor c.code
    }
    return i
}

/**
 * Bash compspec bucket index for [name]: `hash(name) & (N-1)` where N is
 * [BASH_COMPSPEC_BUCKETS]. Used when emitting the `complete -p` listing
 * in bash-compatible hash-table iteration order.
 */
internal fun bashCompspecBucket(name: String): Int = bashHashString(name) and (BASH_COMPSPEC_BUCKETS - 1)

private fun shellSingleQuote(s: String): String =
    if (s.isEmpty()) {
        "''"
    } else {
        // Bash's `complete -p` always single-quotes -W/-X/-P/-S even when the
        // value is plain. Embedded single quotes are escaped as `'\''`.
        "'" + s.replace("'", "'\\''") + "'"
    }
