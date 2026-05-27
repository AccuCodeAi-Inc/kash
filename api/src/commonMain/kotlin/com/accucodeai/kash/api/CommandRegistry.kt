package com.accucodeai.kash.api

/**
 * Immutable, name-keyed catalog of every command known to a Kash instance.
 *
 * Built once at startup from the Koin graph — `koin.getAll<CommandSpec>()` —
 * and handed to the interpreter. The interpreter consults it for dispatch,
 * for `type` / `command -v` classification, and for the intrinsic name set
 * that used to be hardcoded.
 */
public interface CommandRegistry {
    public val specs: Collection<CommandSpec>

    public operator fun get(name: String): CommandSpec?

    public fun contains(name: String): Boolean

    public fun names(): Set<String>

    public fun namesOfKind(kind: CommandKind): Set<String>
}
