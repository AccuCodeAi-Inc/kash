package com.accucodeai.kash.api

import com.accucodeai.kash.api.binfmt.BinfmtEntry

/**
 * Descriptor for every command kash knows about — intrinsics, builtins, and
 * tools alike. Loaded into the Koin graph as `@Single CommandSpec` so the
 * interpreter, `type`/`command -v`, and registry filtering all consult one
 * source of truth.
 *
 * Intrinsics ([CommandKind.INTRINSIC]) set [command] to `null`: their impl
 * lives in the interpreter because they touch shell state (positional
 * parameters, the function table, control-flow exceptions, the parser) that
 * the public [Command] interface intentionally doesn't expose. The spec still
 * carries the metadata so `type :` and `type set` get the same answer as
 * `type echo` from the same lookup table.
 */
public interface CommandSpec {
    public val name: String

    /** Alternative names that resolve to this same impl. e.g. `test` ↔ `[`. */
    public val aliases: List<String> get() = emptyList()

    public val kind: CommandKind

    public val tags: Set<CommandTag> get() = emptySet()

    /** Whether this command is part of [com.accucodeai.kash.api.CommandRegistry]. */
    public val defaultEnabled: Boolean get() = true

    /**
     * The runnable command, or `null` for intrinsics (interpreter-owned).
     * For builtins and tools this is typically `this` — the impl class is
     * also the spec.
     */
    public val command: Command?

    /**
     * POSIX "special builtin" semantics ([Special Builtins, bash manual](
     * https://www.gnu.org/software/bash/manual/html_node/Special-Builtins.html)):
     *  - errors in a non-interactive shell abort the script
     *  - prefix variable assignments persist into the parent shell
     *  - lookup precedence beats user functions
     *
     * Mostly orthogonal to [kind]: an intrinsic may be special (`set`, `eval`,
     * `export`) or regular (`declare`, `typeset`, `local`, `type`, `command`).
     * The default below is the POSIX-correct one for each kind; specs
     * override only when needed.
     */
    public val isSpecial: Boolean
        get() = kind == CommandKind.SPECIAL_BUILTIN

    /**
     * Optional contribution to the machine's binfmt handler chain. When
     * non-null, the [KashMachine] auto-registers a synthesized
     * [com.accucodeai.kash.api.binfmt.BinfmtHandler] that matches files by
     * the entry's magic / extensions and invokes [command] with the matched
     * path as argv[1]. Lets a tool declare "I handle `.py` files" next to
     * its impl, binfmt_misc-style, without touching the registry wiring.
     *
     * Defaults to `null` (most commands don't claim a file format).
     */
    public val binfmt: BinfmtEntry? get() = null
}

/**
 * What family of command this is. See [`docs/TOOLS.md`][doc] for the full
 * POSIX-aware roadmap and the label legend (🅂 / 🅁 / 🅄 / 🄱 / 🅃).
 *
 * [doc]: https://github.com/AccuCodeAi-Inc/kash/blob/main/docs/TOOLS.md
 */
public enum class CommandKind {
    /**
     * Interpreter-owned. [CommandSpec.command] is `null`. Covers both POSIX
     * special intrinsics (🅂: `:`, `eval`, `export`, `readonly`, `return`,
     * `set`, `shift`, `unset`, `break`, `continue`) and regular intrinsics
     * (🅁: `command`, `type`; 🄱: `declare`, `typeset`, `local`). The
     * [CommandSpec.isSpecial] flag separates the two.
     */
    INTRINSIC,

    /**
     * Has a [Command] impl but follows POSIX [special-builtin semantics](
     * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_14).
     * Reserved for future `.`/`source`, `exec`, `exit`, `trap`, `times`.
     */
    SPECIAL_BUILTIN,

    /**
     * Regular shell builtin — POSIX [Built-in Utilities](
     * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap01.html#tag_18_06)
     * or the corresponding [Standard Utilities](
     * https://pubs.opengroup.org/onlinepubs/9699919799/utilities/contents.html)
     * shipped in-process. Scoped prefix assignments, errors don't abort.
     */
    BUILTIN,

    /** External-style tool implemented in-process (`jq`, `grep`, `awk`, etc.). */
    TOOL,
}

/**
 * Capability tags. Descriptive metadata — not enforced at runtime. See
 * [`docs/TOOLS.md`][doc] for how the labels are applied across the roadmap.
 *
 * [doc]: https://github.com/AccuCodeAi-Inc/kash/blob/main/docs/TOOLS.md
 */
public enum class CommandTag {
    /** Defined in the POSIX spec (special builtin, regular builtin, or standard utility). */
    POSIX,

    /** Bash extension (`declare`, `local`, `[[`, `recho`, etc.). */
    BASH_EXT,

    /**
     * Bash ships this command as a shell builtin (in bash's `enable -a`
     * list). Affects `type`/`command -v` resolution order: the in-process
     * impl wins over a PATH-located executable of the same name, matching
     * what bash users expect (`type echo` → `echo is a shell builtin`,
     * NOT `echo is /usr/bin/echo`). Commands that are POSIX standard
     * utilities but NOT bash builtins (`cat`, `ls`, `grep`, …) must NOT
     * carry this tag — `type cat` should report `/bin/cat` because that's
     * what bash does.
     */
    BASH_BUILTIN,

    /** Mutates the [com.accucodeai.kash.fs.FileSystem]. */
    FS_WRITE,

    /** Touches the network. */
    NETWORK,

    /** Non-deterministic output (time, random, env-dependent). */
    IMPURE,

    /** Mutates interpreter state — env, cwd, function table, positional params. */
    STATEFUL,
}

/**
 * Wrap a plain [Command] into a [CommandSpec] with sensible defaults
 * (`kind = BUILTIN`, no tags, no aliases, default-enabled). Used by
 * `customCommands` callers and by [defineCommand] consumers that haven't
 * declared a richer spec.
 */
public fun Command.asSpec(
    kind: CommandKind = CommandKind.BUILTIN,
    tags: Set<CommandTag> = emptySet(),
    aliases: List<String> = emptyList(),
    defaultEnabled: Boolean = true,
): CommandSpec = SimpleCommandSpec(this, kind, tags, aliases, defaultEnabled)

private class SimpleCommandSpec(
    override val command: Command,
    override val kind: CommandKind,
    override val tags: Set<CommandTag>,
    override val aliases: List<String>,
    override val defaultEnabled: Boolean,
) : CommandSpec {
    override val name: String get() = command.name
}
