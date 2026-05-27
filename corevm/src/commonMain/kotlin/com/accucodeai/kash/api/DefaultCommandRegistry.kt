package com.accucodeai.kash.api

/**
 * Every default-enabled spec, keyed by primary name then by alias.
 * Aliases only fill empty slots — the primary name always wins on conflict.
 */
public fun CommandRegistry(specs: List<CommandSpec>): CommandRegistry = DefaultCommandRegistry.standard(specs)

internal class DefaultCommandRegistry private constructor(
    private val byName: Map<String, CommandSpec>,
) : CommandRegistry {
    override val specs: Collection<CommandSpec> get() = byName.values

    override fun get(name: String): CommandSpec? = byName[name]

    override fun contains(name: String): Boolean = name in byName

    override fun names(): Set<String> = byName.keys

    override fun namesOfKind(kind: CommandKind): Set<String> =
        byName.values
            .asSequence()
            .filter { it.kind == kind }
            .map { it.name }
            .toSet()

    companion object {
        fun standard(specs: List<CommandSpec>): CommandRegistry {
            val byName = LinkedHashMap<String, CommandSpec>(specs.size)
            for (spec in specs) {
                if (!spec.defaultEnabled) continue
                byName[spec.name] = spec
                for (alias in spec.aliases) if (alias !in byName) byName[alias] = spec
            }
            return DefaultCommandRegistry(byName)
        }
    }
}
