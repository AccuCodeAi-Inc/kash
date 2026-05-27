package com.accucodeai.kash.interpreter

/**
 * Unified variable storage — replaces the parallel
 * `env` / `indexedArrays` / `assocArrays` / `varAttrs` / `readonlyVars` /
 * `arrayAssigned` / `localScopes` maps with a single per-name [Variable]
 * record plus a function-local scope chain.
 *
 * Lookup walks the scope chain inside-out and falls back to globals.
 * Mutations target either the current scope frame (when the caller
 * passes `local = true`) or globals.
 *
 * `VarTable` is dumb storage: it has no I/O side effects, no DIRSTACK
 * callback, no `process.env` mirror. The policy layer
 * ([InterpreterVariables.kt] and the declare/typeset intrinsics) wraps
 * `VarTable` writes with whatever cross-cutting behaviour bash requires.
 *
 * Modeled on a shell's per-scope variable contexts plus a global
 * variable table.
 */
internal class VarTable {
    private val globals: MutableMap<String, Variable> = mutableMapOf()

    /** Innermost frame is LAST (matches bash's `variable_context` push order). */
    private val scopes: ArrayDeque<MutableMap<String, Variable>> = ArrayDeque()

    // ---------- lookup ----------

    /** Walk innermost→outermost, then globals. Returns null if undefined. */
    fun find(name: String): Variable? {
        for (i in scopes.indices.reversed()) {
            scopes[i][name]?.let { return it }
        }
        return globals[name]
    }

    /**
     * Resolve a [Variable] following [VarAttr.NameRef] chains. Returns
     * the final non-nameref [Variable] reached.
     *
     * When the chain points at a name with no current binding, behavior
     * depends on [createMissing]: reads pass `false` and get the
     * nameref-binding itself back (`scalarOrNull` will be the target
     * name, which is bash's "unset target reads as the target name"
     * quirk — handled by callers that special-case it). Writes pass
     * `true` so the target is materialized as an empty scalar that the
     * subsequent assignment populates (matches bash's
     * `declare -n foo=bar; foo=val` → `bar=val` even when `bar` had no
     * prior binding).
     *
     * Cycle detection: if the same name appears twice in the chain or
     * a nameref points at itself, resolution stops at that node.
     */
    fun resolveRef(
        start: Variable,
        createMissing: Boolean = false,
        maxDepth: Int = 16,
    ): Variable {
        var cur = start
        val seen = mutableSetOf<String>()
        var depth = 0
        while (VarAttr.NameRef in cur.attrs && depth < maxDepth) {
            val targetName = cur.scalarOrNull ?: return cur
            if (targetName.isEmpty() || targetName == cur.name || !seen.add(cur.name)) return cur
            val next = find(targetName) ?: return if (createMissing) findOrCreate(targetName) else cur
            cur = next
            depth++
        }
        return cur
    }

    /**
     * Like [find] but transparently follows [VarAttr.NameRef] chains.
     * Returns the resolved leaf binding, or `null` if [name] itself
     * isn't bound. Callers that need to inspect the nameref *binding
     * itself* (e.g. `declare -p`, `unset -n`) should use [find]; everyone
     * else should use this so reads see the referenced target's value.
     */
    fun findResolved(name: String): Variable? = find(name)?.let { resolveRef(it) }

    /**
     * Find an existing binding, or create a fresh [Variable] at the
     * appropriate scope:
     *   - [local] = true  AND we're in a function (scopes non-empty)
     *     → top frame
     *   - otherwise        → globals
     */
    fun findOrCreate(
        name: String,
        local: Boolean = false,
    ): Variable {
        find(name)?.let { return it }
        val fresh = Variable(name)
        if (local && scopes.isNotEmpty()) {
            scopes.last()[name] = fresh
        } else {
            globals[name] = fresh
        }
        return fresh
    }

    /**
     * `local NAME` — shadow [name] in the current scope frame. If no
     * function is active (scopes empty), this is a no-op returning the
     * existing global (matching bash's "local outside function: error").
     * The caller is responsible for diagnosing the no-function case;
     * here we just hand back whatever binding exists.
     */
    fun shadowLocal(name: String): Variable {
        if (scopes.isEmpty()) return findOrCreate(name)
        val frame = scopes.last()
        frame[name]?.let { return it }
        // Fresh local binding starts Unset; bash carries forward the
        // outer attribute set onto the new binding (`local x` doesn't
        // drop the integer/readonly flag), so seed attrs from the
        // outer Variable if one exists.
        val outerAttrs = find(name)?.attrs?.toMutableSet() ?: mutableSetOf()
        val fresh = Variable(name, attrs = outerAttrs)
        frame[name] = fresh
        return fresh
    }

    // ---------- mutation ----------

    /**
     * `unset NAME` — drop or clear the binding at the topmost scope
     * that has it. Returns true if a binding existed.
     *
     * Bash semantics for local scopes: a `local NAME` shadow stays
     * "present but unset" after `unset NAME`, so a subsequent
     * `NAME=value` inside the same function writes to the local — the
     * outer scope is NOT revealed mid-function. The local frame entry
     * is therefore kept (value cleared, attributes preserved) and only
     * unwound when the function returns (popScope). For globals there
     * is no enclosing scope to preserve into, so we remove outright.
     *
     * (Matches bash: a function-local unset keeps the binding slot until
     * the function returns; a global unset reclaims it immediately.)
     */
    fun unset(name: String): Boolean {
        for (i in scopes.indices.reversed()) {
            val v = scopes[i][name] ?: continue
            v.value = VariableValue.Unset
            v.attrs.clear()
            return true
        }
        return globals.remove(name) != null
    }

    // ---------- scope chain ----------

    fun pushScope() {
        scopes.addLast(mutableMapOf())
    }

    fun popScope(): MutableMap<String, Variable> = scopes.removeLast()

    /**
     * Detach (remove) the topmost local shadow of [name] without
     * touching outer scopes. Returns the removed binding so the
     * caller can reattach it. Returns null if no local shadow exists.
     *
     * Used by the posix-mode special-builtin prefix path:
     * `var=20 return` inside a function must write past any
     * function-local shadow into the caller's scope (POSIX
     * §2.9.1.4 / bash posix-mode), but the shadow has to be
     * restored once the write lands so the rest of the function
     * sees its own temp-env value of `name`.
     */
    fun detachLocalShadow(name: String): Variable? {
        for (i in scopes.indices.reversed()) {
            val frame = scopes[i]
            if (name in frame) return frame.remove(name)
        }
        return null
    }

    fun reattachLocalShadow(
        name: String,
        v: Variable,
    ) {
        if (scopes.isEmpty()) return
        scopes.last()[name] = v
    }

    val scopeDepth: Int get() = scopes.size

    // ---------- iteration ----------

    /**
     * Names visible from the current scope chain — for `declare -p`
     * listings and `set` dump. A name shadowed by a local is reported
     * once (the local binding wins).
     */
    fun visibleNames(): Set<String> {
        val out = linkedSetOf<String>()
        for (i in scopes.indices.reversed()) out += scopes[i].keys
        out += globals.keys
        return out
    }

    /** Globals only — for snapshot serialization. */
    fun globalNames(): Set<String> = globals.keys.toSet()

    /** Iterate the (name → resolved Variable) view from visibleNames(). */
    fun visibleEntries(): Sequence<Pair<String, Variable>> =
        visibleNames().asSequence().mapNotNull { n -> find(n)?.let { n to it } }

    // ---------- fork / snapshot helpers ----------

    /**
     * Deep copy: every [Variable] is cloned (value union and attrs
     * cloned via [Variable.copy]). Used by [Interpreter.forkSubshell]
     * so `(...)` subshells inherit the parent's full state without
     * mutating it.
     */
    fun deepCopy(): VarTable {
        val copy = VarTable()
        copyStateInto(copy)
        return copy
    }

    /**
     * Overwrite [target]'s contents with a deep copy of this table.
     * Used so callers (notably [Interpreter.forkSubshell]) can keep the
     * fork's existing [VarTable] *instance* — which the array views
     * captured at construction — and only replace its contents.
     */
    fun deepCopyInto(target: VarTable) {
        target.globals.clear()
        target.scopes.clear()
        copyStateInto(target)
    }

    private fun copyStateInto(target: VarTable) {
        for ((n, v) in globals) target.globals[n] = v.copy()
        for (frame in scopes) {
            val frameCopy = mutableMapOf<String, Variable>()
            for ((n, v) in frame) frameCopy[n] = v.copy()
            target.scopes.addLast(frameCopy)
        }
    }

    /**
     * Erase all variable state — used at the fork-and-exec boundary
     * where the new shell process must NOT inherit the parent's
     * shell-internal variable table (only the OS-process env block,
     * which is owned separately by [com.accucodeai.kash.api.KashProcess]).
     */
    fun clearShellInternal() {
        globals.clear()
        scopes.clear()
    }
}
