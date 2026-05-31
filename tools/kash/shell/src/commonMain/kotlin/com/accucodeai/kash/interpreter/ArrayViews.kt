package com.accucodeai.kash.interpreter

/**
 * `MutableMap` view over [VarTable] presenting the indexed-array storage
 * the legacy `indexedArrays: MutableMap<String, MutableMap<Int, String>>`
 * field used to be. Every operation routes through [VarTable]:
 *   - `view[name]` → the live element map of the [Variable], or null
 *   - `view[name] = map` → wraps `map` as the Variable's value
 *   - `view.getOrPut(name) { mutableMapOf() }` → fresh empty element
 *      map installed inside the Variable; subsequent legacy-shape
 *      mutations write through to the Variable
 *
 * There is NO independent storage in this class — all state lives in
 * [VarTable]. That's the whole point: keeps array writes and Variable
 * records from diverging.
 */
internal class IndexedArraysView(
    private val table: VarTable,
) : MutableMap<String, MutableMap<Int, String>> {
    override val size: Int get() = table.visibleNames().count { table.find(it)?.isIndexed == true }

    override fun isEmpty(): Boolean = !table.visibleNames().any { table.find(it)?.isIndexed == true }

    override fun containsKey(key: String): Boolean = table.find(key)?.isIndexed == true

    override fun containsValue(value: MutableMap<Int, String>): Boolean =
        table.visibleNames().any { table.find(it)?.indexedOrNull === value }

    override fun get(key: String): MutableMap<Int, String>? = table.find(key)?.indexedOrNull

    override fun put(
        key: String,
        value: MutableMap<Int, String>,
    ): MutableMap<Int, String>? {
        val v = table.findOrCreate(key)
        val prior = v.indexedOrNull
        v.value = VariableValue.Indexed(value)
        v.attrs += VarAttr.Indexed
        return prior
    }

    override fun remove(key: String): MutableMap<Int, String>? {
        val v = table.find(key) ?: return null
        val prior = v.indexedOrNull ?: return null
        v.value = VariableValue.Unset
        return prior
    }

    override fun putAll(from: Map<out String, MutableMap<Int, String>>) {
        for ((k, v) in from) put(k, v)
    }

    override fun clear() {
        for (name in table.visibleNames().toList()) {
            val v = table.find(name) ?: continue
            if (v.isIndexed) v.value = VariableValue.Unset
        }
    }

    override val keys: MutableSet<String>
        get() =
            object : AbstractMutableSet<String>() {
                override val size: Int get() = this@IndexedArraysView.size

                override fun iterator(): MutableIterator<String> = namesSnapshot().toMutableList().iterator()

                override fun add(element: String): Boolean = throw UnsupportedOperationException()

                override fun remove(element: String): Boolean = this@IndexedArraysView.remove(element) != null

                override fun contains(element: String): Boolean = containsKey(element)
            }

    override val values: MutableCollection<MutableMap<Int, String>>
        get() =
            object : AbstractMutableCollection<MutableMap<Int, String>>() {
                override val size: Int get() = this@IndexedArraysView.size

                override fun iterator(): MutableIterator<MutableMap<Int, String>> =
                    namesSnapshot().mapNotNull { get(it) }.toMutableList().iterator()

                override fun add(element: MutableMap<Int, String>): Boolean = throw UnsupportedOperationException()
            }

    override val entries: MutableSet<MutableMap.MutableEntry<String, MutableMap<Int, String>>>
        get() =
            object : AbstractMutableSet<MutableMap.MutableEntry<String, MutableMap<Int, String>>>() {
                override val size: Int get() = this@IndexedArraysView.size

                @Suppress("ktlint:standard:max-line-length")
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, MutableMap<Int, String>>> =
                    namesSnapshot()
                        .mapNotNull { n -> get(n)?.let { v -> Entry(n, v) } }
                        .toMutableList()
                        .iterator()

                override fun add(element: MutableMap.MutableEntry<String, MutableMap<Int, String>>): Boolean =
                    throw UnsupportedOperationException()
            }

    private fun namesSnapshot(): List<String> = table.visibleNames().filter { table.find(it)?.isIndexed == true }

    private inner class Entry(
        override val key: String,
        override val value: MutableMap<Int, String>,
    ) : MutableMap.MutableEntry<String, MutableMap<Int, String>> {
        override fun setValue(newValue: MutableMap<Int, String>): MutableMap<Int, String> {
            val prior = value
            put(key, newValue)
            return prior
        }
    }
}

/** Assoc counterpart of [IndexedArraysView]. */
internal class AssocArraysView(
    private val table: VarTable,
) : MutableMap<String, LinkedHashMap<String, String>> {
    override val size: Int get() = table.visibleNames().count { table.find(it)?.isAssoc == true }

    override fun isEmpty(): Boolean = !table.visibleNames().any { table.find(it)?.isAssoc == true }

    override fun containsKey(key: String): Boolean = table.find(key)?.isAssoc == true

    override fun containsValue(value: LinkedHashMap<String, String>): Boolean =
        table.visibleNames().any { table.find(it)?.assocOrNull === value }

    override fun get(key: String): LinkedHashMap<String, String>? = table.find(key)?.assocOrNull

    override fun put(
        key: String,
        value: LinkedHashMap<String, String>,
    ): LinkedHashMap<String, String>? {
        val v = table.findOrCreate(key)
        val prior = v.assocOrNull
        v.value = VariableValue.Assoc(value)
        v.attrs += VarAttr.Associative
        return prior
    }

    override fun remove(key: String): LinkedHashMap<String, String>? {
        val v = table.find(key) ?: return null
        val prior = v.assocOrNull ?: return null
        v.value = VariableValue.Unset
        return prior
    }

    override fun putAll(from: Map<out String, LinkedHashMap<String, String>>) {
        for ((k, v) in from) put(k, v)
    }

    override fun clear() {
        for (name in table.visibleNames().toList()) {
            val v = table.find(name) ?: continue
            if (v.isAssoc) v.value = VariableValue.Unset
        }
    }

    override val keys: MutableSet<String>
        get() =
            object : AbstractMutableSet<String>() {
                override val size: Int get() = this@AssocArraysView.size

                override fun iterator(): MutableIterator<String> = namesSnapshot().toMutableList().iterator()

                override fun add(element: String): Boolean = throw UnsupportedOperationException()

                override fun remove(element: String): Boolean = this@AssocArraysView.remove(element) != null

                override fun contains(element: String): Boolean = containsKey(element)
            }

    override val values: MutableCollection<LinkedHashMap<String, String>>
        get() =
            object : AbstractMutableCollection<LinkedHashMap<String, String>>() {
                override val size: Int get() = this@AssocArraysView.size

                override fun iterator(): MutableIterator<LinkedHashMap<String, String>> =
                    namesSnapshot().mapNotNull { get(it) }.toMutableList().iterator()

                override fun add(element: LinkedHashMap<String, String>): Boolean =
                    throw UnsupportedOperationException()
            }

    override val entries: MutableSet<MutableMap.MutableEntry<String, LinkedHashMap<String, String>>>
        get() =
            object : AbstractMutableSet<MutableMap.MutableEntry<String, LinkedHashMap<String, String>>>() {
                override val size: Int get() = this@AssocArraysView.size

                @Suppress("ktlint:standard:max-line-length")
                override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, LinkedHashMap<String, String>>> =
                    namesSnapshot()
                        .mapNotNull { n -> get(n)?.let { v -> Entry(n, v) } }
                        .toMutableList()
                        .iterator()

                override fun add(element: MutableMap.MutableEntry<String, LinkedHashMap<String, String>>): Boolean =
                    throw UnsupportedOperationException()
            }

    private fun namesSnapshot(): List<String> = table.visibleNames().filter { table.find(it)?.isAssoc == true }

    private inner class Entry(
        override val key: String,
        override val value: LinkedHashMap<String, String>,
    ) : MutableMap.MutableEntry<String, LinkedHashMap<String, String>> {
        override fun setValue(newValue: LinkedHashMap<String, String>): LinkedHashMap<String, String> {
            val prior = value
            put(key, newValue)
            return prior
        }
    }
}

/**
 * Read-only `Map` view resolved per-key from a backing store, with no upfront
 * materialization. Used to hand the [Expander] the indexed/assoc array storage:
 * the Expander only ever point-accesses it (`name in arrays`, `arrays[name]`),
 * which become O(scope-depth) [VarTable] lookups instead of building a
 * whole-variable-table snapshot on every `${...}` expansion. The bulk views
 * ([keys]/[entries]/[values]/[size]) fall back to the full name walk, but the
 * Expander never hits those on the hot path.
 *
 * Pure Kotlin (no `java.*`) so it compiles on wasmJs as well as JVM.
 */
internal class LiveNamedView<V : Any>(
    private val getter: (String) -> V?,
    private val names: () -> List<String>,
) : Map<String, V> {
    override fun get(key: String): V? = getter(key)

    override fun containsKey(key: String): Boolean = getter(key) != null

    override fun isEmpty(): Boolean = names().isEmpty()

    override val size: Int get() = names().size

    override fun containsValue(value: V): Boolean = names().any { getter(it) == value }

    override val keys: Set<String> get() = names().toCollection(LinkedHashSet())

    override val values: Collection<V> get() = names().mapNotNull(getter)

    override val entries: Set<Map.Entry<String, V>>
        get() = names().mapNotNullTo(LinkedHashSet()) { n -> getter(n)?.let { ViewEntry(n, it) } }

    private class ViewEntry<V>(
        override val key: String,
        override val value: V,
    ) : Map.Entry<String, V>
}

/** Helper for getOrPut semantics through a view: existing map wins, else install a fresh empty one. */
internal fun IndexedArraysView.getOrPutIndexed(name: String): MutableMap<Int, String> {
    get(name)?.let { return it }
    val fresh = mutableMapOf<Int, String>()
    put(name, fresh)
    return fresh
}

internal fun AssocArraysView.getOrPutAssoc(name: String): LinkedHashMap<String, String> {
    get(name)?.let { return it }
    val fresh = linkedMapOf<String, String>()
    put(name, fresh)
    return fresh
}
