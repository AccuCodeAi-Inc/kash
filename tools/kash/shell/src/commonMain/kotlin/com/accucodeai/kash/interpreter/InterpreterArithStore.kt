package com.accucodeai.kash.interpreter

/**
 * [ArithEval.ArrayStore] view onto the unified [VarTable] so arithmetic
 * expressions can read and mutate array elements (`((dice[i]++))`,
 * `((a[0]+=5))`, etc.).
 *
 * Bash semantics:
 *   - indexed-array subscripts are themselves arithmetic
 *   - associative-array subscripts are literal string keys
 *
 * Writes mutate the [Variable]'s element map directly — we deliberately
 * bypass the full [setIndexedElement]/[setAssocElement] suspend
 * pipeline (DIRSTACK side effects, integer/case attrs) because
 * arithmetic is pure integer evaluation and re-entering those paths
 * would require a suspend context the synchronous evaluator doesn't
 * have. Side effects on DIRSTACK via `((DIRSTACK[N]=...))` are
 * documented as not supported through this path.
 */
internal class InterpreterArithStore(
    private val interp: Interpreter,
) : ArithEval.ArrayStore {
    override fun hasName(name: String): Boolean = interp.varTable.find(name) != null

    override fun isAssoc(name: String): Boolean = interp.varTable.find(name)?.isAssoc == true

    override fun isReadonly(name: String): Boolean = interp.varTable.find(name)?.isReadonly == true

    override fun readIndexed(
        name: String,
        idx: Int,
    ): Long {
        val raw =
            interp.varTable
                .find(name)
                ?.indexedOrNull
                ?.get(idx) ?: return 0L
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull() ?: try {
            ArithEval(raw, interp.env, this).evaluate()
        } catch (_: Throwable) {
            0L
        }
    }

    override fun readAssoc(
        name: String,
        key: String,
    ): Long {
        val raw =
            interp.varTable
                .find(name)
                ?.assocOrNull
                ?.get(key) ?: return 0L
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull() ?: try {
            ArithEval(raw, interp.env, this).evaluate()
        } catch (_: Throwable) {
            0L
        }
    }

    override fun writeIndexed(
        name: String,
        idx: Int,
        value: Long,
    ) {
        val v = interp.varTable.findOrCreate(name)
        val arr =
            v.indexedOrNull ?: mutableMapOf<Int, String>().also {
                v.value = VariableValue.Indexed(it)
            }
        arr[idx] = value.toString()
        interp.process.env.remove(name)
        v.attrs += VarAttr.Indexed
    }

    override fun writeAssoc(
        name: String,
        key: String,
        value: Long,
    ) {
        val v = interp.varTable.findOrCreate(name)
        val arr =
            v.assocOrNull ?: linkedMapOf<String, String>().also {
                v.value = VariableValue.Assoc(it)
            }
        arr[key] = value.toString()
        interp.process.env.remove(name)
        v.attrs += VarAttr.Associative
    }
}
