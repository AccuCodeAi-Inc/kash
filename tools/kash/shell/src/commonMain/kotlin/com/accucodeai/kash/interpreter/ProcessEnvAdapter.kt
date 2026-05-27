package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.KashProcess

/**
 * `MutableMap<String, String>` view that backs [Interpreter.env]. Scalar
 * shell-variable state lives in [VarTable]; the OS-level `process.env`
 * is kept as a write-through mirror so:
 *   - reads via `env[name]` walk the [VarTable] scope chain (function
 *     locals shadow globals, matching bash),
 *   - fork-and-exec sees the same `process.env` shape it always did
 *     (subsequently filtered by `pruneToExportedEnv` for non-exported
 *     names),
 *   - external code that still reads `process.env[name]` directly
 *     (CdCommand, KashShellCommand) keeps working without churn.
 *
 * Writes do NOT add the Export attribute — bash semantics: plain
 * `FOO=bar` doesn't export. The init block seeds Export on each name
 * inherited from `environ`, and `export FOO` flips it explicitly.
 *
 * Iteration is "scalars in the [VarTable]" — assoc/indexed names are
 * skipped. That matches the legacy behavior because the legacy code
 * removed name from `env` whenever it became array-typed.
 */
internal class ProcessEnvAdapter(
    private val table: VarTable,
    private val process: KashProcess,
) : MutableMap<String, String> {
    override val size: Int get() = visibleScalars().size

    override fun isEmpty(): Boolean = visibleScalars().isEmpty()

    override fun containsKey(key: String): Boolean =
        table.find(key)?.isScalar == true ||
            // Fall back to the OS env block — captures direct writes by
            // built-in commands that hold a [KashProcess] but no
            // [Interpreter] reference (CdCommand updates `OLDPWD/PWD`
            // through `ctx.process.env` after a successful cd).
            (table.find(key)?.isSet != true && key in process.env)

    override fun containsValue(value: String): Boolean = visibleScalars().any { get(it) == value }

    override fun get(key: String): String? {
        val raw = table.find(key) ?: return process.env[key]
        // Follow [VarAttr.NameRef] chains: a `declare -n foo=bar` binding
        // reads as `bar`'s value, not as the literal string "bar".
        // [VarTable.resolveRef] caps depth and short-circuits cycles.
        val v = if (VarAttr.NameRef in raw.attrs) table.resolveRef(raw) else raw
        if (v.isSet) return v.scalarOrNull
        return process.env[key]
    }

    override fun put(
        key: String,
        value: String,
    ): String? {
        // Tools that pass through `ctx.process.env` (printf -v, builtins
        // that don't drop into the interpreter directly) may carry a
        // `NAME[sub]` key the user supplied as an arg. Bash treats that
        // as an array-element write; we mirror by parsing the bracket
        // form and routing to the array map directly.
        val lb = key.indexOf('[')
        if (lb > 0 && key.endsWith(']')) {
            val base = key.substring(0, lb)
            val sub = key.substring(lb + 1, key.length - 1)
            val baseIsId =
                base.isNotEmpty() &&
                    (base[0].isLetter() || base[0] == '_') &&
                    base.all { it.isLetterOrDigit() || it == '_' }
            if (baseIsId && sub.isNotEmpty()) {
                val vbase = table.findOrCreate(base)
                if (vbase.isAssoc) {
                    val map = vbase.assocOrNull
                    val prior = map?.get(sub)
                    map?.set(sub, value)
                    return prior
                }
                // Indexed (or scalar that needs to become indexed): try
                // arith on the subscript; fall through to scalar write on
                // failure so we don't paper over a real error here.
                val idx = sub.toIntOrNull()
                if (idx != null && idx >= 0) {
                    val map =
                        vbase.indexedOrNull ?: run {
                            vbase.value = VariableValue.Indexed(mutableMapOf())
                            vbase.attrs += VarAttr.Indexed
                            vbase.indexedOrNull!!
                        }
                    val prior = map[idx]
                    map[idx] = value
                    return prior
                }
            }
        }
        val rawV = table.findOrCreate(key)
        // `declare -n foo=bar; foo=val` MUST write to `bar`, not to `foo`.
        // The exception is the `declare -n NAME=TARGET` invocation itself
        // — that creates / rebinds the nameref and writes the *target
        // name* into NAME's scalar. We disambiguate by checking the
        // attr BEFORE the assignment lands: if the nameref attr was
        // already set (this is a subsequent write), follow; if it's
        // being set as part of this write (declare's `attrs += NameRef`
        // path), we never reach this method — declare's own code path
        // assigns directly through [Variable.value].
        val v =
            if (VarAttr.NameRef in rawV.attrs) {
                table.resolveRef(rawV, createMissing = true)
            } else {
                rawV
            }
        val prior = v.scalarOrNull
        // `env[name] = value` MUST NOT clobber an existing array binding.
        // Bash's `name=value` on an array does `name[0]=value` (handled
        // upstream by `setScalar`); raw `env[]=` writes from intrinsics
        // are scalar-mirror writes that should leave the array intact.
        //
        // Dynamic specials (RANDOM, SECONDS) route the write through
        // their setter — `RANDOM=42` reseeds, `SECONDS=N` rebases.
        // Read-only specials (BASHPID, LINENO) have no setter; we still
        // store the assignment so a subsequent `unset` works, but the
        // dynamic getter wins on next read.
        when {
            v.setter != null -> {
                v.setter!!.invoke(value)
            }

            v.isIndexed -> {
                // Update element 0 so `$arr` and `${arr[0]}` agree.
                v.indexedOrNull?.set(0, value)
            }

            v.isAssoc -> {
                v.assocOrNull?.set("0", value)
            }

            else -> {
                v.value = VariableValue.Scalar(value)
            }
        }
        // Bash semantics: plain `FOO=bar` does NOT export — only exported
        // names should appear in the OS-level process.env. If the
        // variable already had Export set (e.g. by a prior `export FOO`),
        // mirror the new value. Otherwise drop any stale mirror so
        // in-process builtins like `printenv` see the bash-correct view.
        // `export FOO=...` runs through declare/export which set the
        // Export attr BEFORE calling into put, so the new value lands
        // here with isExported already true.
        if (v.isExported) {
            process.env[key] = value
        } else {
            process.env.remove(key)
        }
        return prior
    }

    override fun remove(key: String): String? {
        val v =
            table.find(key) ?: run {
                process.env.remove(key)
                return null
            }
        val prior = v.scalarOrNull
        if (v.isScalar) v.value = VariableValue.Unset
        process.env.remove(key)
        return prior
    }

    override fun putAll(from: Map<out String, String>) {
        for ((k, v) in from) put(k, v)
    }

    override fun clear() {
        for (name in visibleScalars().toList()) remove(name)
    }

    private fun visibleScalars(): List<String> {
        val out = linkedSetOf<String>()
        for (n in table.visibleNames()) {
            if (table.find(n)?.isScalar == true) out += n
        }
        // Names that exist only as direct `process.env` writes (CdCommand
        // OLDPWD/PWD, KashShellCommand's external setup) — surface them
        // too so callers iterating `env` see the full scalar view.
        for (n in process.env.keys) {
            val v = table.find(n)
            if (v == null || !v.isSet) out += n
        }
        return out.toList()
    }

    override val keys: MutableSet<String>
        get() =
            object : AbstractMutableSet<String>() {
                override val size: Int get() = this@ProcessEnvAdapter.size

                override fun iterator(): MutableIterator<String> = visibleScalars().toMutableList().iterator()

                override fun add(element: String): Boolean = throw UnsupportedOperationException()

                override fun remove(element: String): Boolean = this@ProcessEnvAdapter.remove(element) != null

                override fun contains(element: String): Boolean = containsKey(element)
            }

    override val values: MutableCollection<String>
        get() =
            object : AbstractMutableCollection<String>() {
                override val size: Int get() = this@ProcessEnvAdapter.size

                override fun iterator(): MutableIterator<String> =
                    visibleScalars().mapNotNull { get(it) }.toMutableList().iterator()

                override fun add(element: String): Boolean = throw UnsupportedOperationException()
            }

    override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
        get() =
            object : AbstractMutableSet<MutableMap.MutableEntry<String, String>>() {
                override val size: Int get() = this@ProcessEnvAdapter.size

                override fun iterator(): MutableIterator<MutableMap.MutableEntry<String, String>> =
                    visibleScalars()
                        .mapNotNull { n -> get(n)?.let { v -> Entry(n, v) } }
                        .toMutableList()
                        .iterator()

                override fun add(element: MutableMap.MutableEntry<String, String>): Boolean =
                    throw UnsupportedOperationException()
            }

    private inner class Entry(
        override val key: String,
        override val value: String,
    ) : MutableMap.MutableEntry<String, String> {
        override fun setValue(newValue: String): String {
            val prior = value
            put(key, newValue)
            return prior
        }
    }
}
