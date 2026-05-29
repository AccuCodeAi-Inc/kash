package com.accucodeai.kash.tools.jq.eval

import com.accucodeai.kash.json.JsonKind
import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.asBoolOrNull
import com.accucodeai.kash.json.asDoubleOrNull
import com.accucodeai.kash.json.asLongOrNull
import com.accucodeai.kash.json.asStringOrNull
import com.accucodeai.kash.json.isTruthy
import com.accucodeai.kash.json.jsonArray
import com.accucodeai.kash.json.jsonBool
import com.accucodeai.kash.json.jsonNull
import com.accucodeai.kash.json.jsonNumber
import com.accucodeai.kash.json.jsonObject
import com.accucodeai.kash.json.jsonString
import com.accucodeai.kash.json.kind
import com.accucodeai.kash.json.typeName
import com.accucodeai.kash.tools.jq.JqRuntimeError
import com.accucodeai.kash.tools.jq.ast.ArrayConstruct
import com.accucodeai.kash.tools.jq.ast.Assign
import com.accucodeai.kash.tools.jq.ast.BinOp
import com.accucodeai.kash.tools.jq.ast.BinaryOp
import com.accucodeai.kash.tools.jq.ast.BindAs
import com.accucodeai.kash.tools.jq.ast.Comma
import com.accucodeai.kash.tools.jq.ast.ErrorExpr
import com.accucodeai.kash.tools.jq.ast.FieldAccess
import com.accucodeai.kash.tools.jq.ast.Foreach
import com.accucodeai.kash.tools.jq.ast.FormatStr
import com.accucodeai.kash.tools.jq.ast.FuncCall
import com.accucodeai.kash.tools.jq.ast.FunctionDef
import com.accucodeai.kash.tools.jq.ast.Identity
import com.accucodeai.kash.tools.jq.ast.IfThenElse
import com.accucodeai.kash.tools.jq.ast.Index
import com.accucodeai.kash.tools.jq.ast.IndexKind
import com.accucodeai.kash.tools.jq.ast.InterpolatedString
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.ast.JqParam
import com.accucodeai.kash.tools.jq.ast.Literal
import com.accucodeai.kash.tools.jq.ast.Negate
import com.accucodeai.kash.tools.jq.ast.ObjectConstruct
import com.accucodeai.kash.tools.jq.ast.Optional
import com.accucodeai.kash.tools.jq.ast.Pipe
import com.accucodeai.kash.tools.jq.ast.RecursiveDescent
import com.accucodeai.kash.tools.jq.ast.Reduce
import com.accucodeai.kash.tools.jq.ast.StringPart
import com.accucodeai.kash.tools.jq.ast.TryCatch
import com.accucodeai.kash.tools.jq.ast.UpdateAssign
import com.accucodeai.kash.tools.jq.ast.VarRef
import com.accucodeai.kash.tools.jq.builtins.Builtins
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun JqExpr.eval(
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> =
    when (this) {
        Identity -> {
            sequenceOf(input)
        }

        RecursiveDescent -> {
            recurse(input)
        }

        is Literal -> {
            sequenceOf(value)
        }

        is InterpolatedString -> {
            evalString(parts, ctx, input)
        }

        is VarRef -> {
            sequenceOf(ctx.vars[name] ?: throw JqRuntimeError("$$name is not defined"))
        }

        is FieldAccess -> {
            sequence {
                for (v in source.eval(ctx, input)) yield(applyField(v, name, optional) ?: continue)
            }
        }

        is Index -> {
            evalIndex(this, ctx, input)
        }

        is Pipe -> {
            left.eval(ctx, input).flatMap { v -> right.eval(ctx, v) }
        }

        is Comma -> {
            left.eval(ctx, input) + right.eval(ctx, input)
        }

        is ArrayConstruct -> {
            sequenceOf(jsonArray(body?.eval(ctx, input)?.toList() ?: emptyList()))
        }

        is ObjectConstruct -> {
            evalObject(this, ctx, input)
        }

        is Negate -> {
            operand.eval(ctx, input).map { negateNum(it) }
        }

        is BinaryOp -> {
            evalBinary(this, ctx, input)
        }

        is IfThenElse -> {
            cond.eval(ctx, input).flatMap { c ->
                var picked: JqExpr = elseBranch
                if (c.isTruthy()) {
                    picked = thenBranch
                } else {
                    for ((ec, et) in elifBranches) {
                        val r = ec.eval(ctx, input).toList()
                        if (r.any { it.isTruthy() }) {
                            picked = et
                            break
                        }
                    }
                }
                picked.eval(ctx, input)
            }
        }

        is TryCatch -> {
            sequence {
                try {
                    for (v in body.eval(ctx, input)) yield(v)
                } catch (e: JqRuntimeError) {
                    if (handler != null) {
                        for (v in handler.eval(ctx, jsonString(e.message ?: ""))) yield(v)
                    }
                }
            }
        }

        is Optional -> {
            sequence {
                try {
                    for (v in body.eval(ctx, input)) yield(v)
                } catch (_: JqRuntimeError) {
                }
            }
        }

        is BindAs -> {
            source.eval(ctx, input).flatMap { v ->
                body.eval(ctx.withVar(varName, v), input)
            }
        }

        is Reduce -> {
            evalReduce(this, ctx, input)
        }

        is Foreach -> {
            evalForeach(this, ctx, input)
        }

        is FuncCall -> {
            // User defs and filter-param thunks shadow builtins.
            val slot = ctx.funcs["$name/${args.size}"]
            if (slot != null) {
                callUserFunction(slot.binding, args, ctx, input)
            } else {
                Builtins.invoke(name, args, ctx, input)
            }
        }

        is ErrorExpr -> {
            val msg = message?.eval(ctx, input)?.firstOrNull()?.let { it.asStringOrNull() ?: it.toString() } ?: "error"
            throw JqRuntimeError(msg)
        }

        is FormatStr -> {
            sequenceOf(applyFormat(name, input))
        }

        is Assign -> {
            sequence {
                // Snapshot paths so a single new-value reapplies them all coherently.
                val ps = path.paths(ctx, input).map { it.toList() }.toList()
                for (v in value.eval(ctx, input)) {
                    var cur = input
                    for (p in ps) cur = setPath(cur, p, v)
                    yield(cur)
                }
            }
        }

        is FunctionDef -> {
            val key = "$name/${params.size}"
            val slot = FunctionSlot()
            val newFuncs = ctx.funcs + (key to slot)
            slot.binding = UserDef(params, body, capturedFuncs = newFuncs)
            rest.eval(ctx.copy(funcs = newFuncs), input)
        }

        is UpdateAssign -> {
            sequence {
                // For each output combination from the transform applied at each path,
                // jq's behavior is: walk all paths once, replacing each location with
                // the first output of transform(current). Multi-output transforms only
                // use the first value (matches jq's update-assignment semantics).
                val ps = path.paths(ctx, input).map { it.toList() }.toList()
                var cur = input
                for (p in ps) {
                    val before = getPath(cur, p) ?: jsonNull()
                    val after = transform.eval(ctx, before).firstOrNull()
                    cur = if (after == null) deletePath(cur, p) else setPath(cur, p, after)
                }
                yield(cur)
            }
        }
    }

private fun recurse(v: JsonValue): Sequence<JsonValue> =
    sequence {
        yield(v)
        when (v) {
            is JsonArray -> {
                for (e in v) yieldAll(recurse(e))
            }

            is JsonObject -> {
                for ((_, e) in v) yieldAll(recurse(e))
            }

            else -> {}
        }
    }

private fun evalString(
    parts: List<StringPart>,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> {
    // Cartesian over interpolation outputs.
    val segments: List<List<String>> =
        parts.map { p ->
            when (p) {
                is StringPart.Text -> {
                    listOf(p.s)
                }

                is StringPart.Expr -> {
                    p.e
                        .eval(ctx, input)
                        .map { v ->
                            v.asStringOrNull() ?: jqEncode(v)
                        }.toList()
                }
            }
        }
    return cartesian(segments).map { jsonString(it.joinToString("")) }
}

private fun <T> cartesian(lists: List<List<T>>): Sequence<List<T>> =
    sequence {
        if (lists.isEmpty()) {
            yield(emptyList())
            return@sequence
        }
        val indices = IntArray(lists.size)
        while (true) {
            yield(List(lists.size) { lists[it][indices[it]] })
            var i = lists.size - 1
            while (i >= 0) {
                indices[i]++
                if (indices[i] < lists[i].size) break
                indices[i] = 0
                i--
            }
            if (i < 0) return@sequence
        }
    }

private fun applyField(
    v: JsonValue,
    name: String,
    optional: Boolean,
): JsonValue? =
    when (v) {
        is JsonObject -> {
            v[name] ?: jsonNull()
        }

        is kotlinx.serialization.json.JsonNull -> {
            jsonNull()
        }

        else -> {
            if (optional) {
                null
            } else {
                throw JqRuntimeError("Cannot index ${v.typeName()} with \"$name\"")
            }
        }
    }

private fun evalIndex(
    idx: Index,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> =
    sequence {
        for (src in idx.source.eval(ctx, input)) {
            when (val k = idx.index) {
                IndexKind.Iterate -> {
                    yieldAll(iterate(src, idx.optional))
                }

                is IndexKind.At -> {
                    for (key in k.key.eval(ctx, input)) {
                        val r = atIndex(src, key, idx.optional) ?: continue
                        yield(r)
                    }
                }

                is IndexKind.Slice -> {
                    val from = k.from?.eval(ctx, input)?.firstOrNull()
                    val to = k.to?.eval(ctx, input)?.firstOrNull()
                    yield(slice(src, from, to, idx.optional) ?: continue)
                }
            }
        }
    }

private fun iterate(
    v: JsonValue,
    optional: Boolean,
): Sequence<JsonValue> =
    when (v) {
        is JsonArray -> {
            v.asSequence()
        }

        is JsonObject -> {
            v.values.asSequence()
        }

        is kotlinx.serialization.json.JsonNull -> {
            if (optional) {
                emptySequence()
            } else {
                throw JqRuntimeError("Cannot iterate over null")
            }
        }

        else -> {
            if (optional) {
                emptySequence()
            } else {
                throw JqRuntimeError("Cannot iterate over ${v.typeName()}")
            }
        }
    }

private fun atIndex(
    src: JsonValue,
    key: JsonValue,
    optional: Boolean,
): JsonValue? =
    when (src) {
        is JsonArray -> {
            val i =
                key.asLongOrNull()?.toInt()
                    ?: if (optional) return null else throw JqRuntimeError("array index must be integer")
            val n = src.size
            val ix = if (i < 0) i + n else i
            if (ix in 0 until n) src[ix] else jsonNull()
        }

        is JsonObject -> {
            val s =
                key.asStringOrNull()
                    ?: if (optional) return null else throw JqRuntimeError("object key must be string")
            src[s] ?: jsonNull()
        }

        is kotlinx.serialization.json.JsonNull -> {
            jsonNull()
        }

        else -> {
            if (optional) null else throw JqRuntimeError("Cannot index ${src.typeName()}")
        }
    }

private fun slice(
    src: JsonValue,
    from: JsonValue?,
    to: JsonValue?,
    optional: Boolean,
): JsonValue? {
    val (lo, hi) =
        when (src) {
            is JsonArray -> {
                0 to src.size
            }

            is kotlinx.serialization.json.JsonPrimitive -> {
                if (src.isString) {
                    0 to src.content.length
                } else if (optional) {
                    return null
                } else {
                    throw JqRuntimeError("Cannot slice ${src.typeName()}")
                }
            }

            is kotlinx.serialization.json.JsonNull -> {
                return jsonNull()
            }

            else -> {
                if (optional) return null else throw JqRuntimeError("Cannot slice ${src.typeName()}")
            }
        }
    val f = from?.asLongOrNull()?.toInt() ?: lo
    val t = to?.asLongOrNull()?.toInt() ?: hi
    val ff = (if (f < 0) f + hi else f).coerceIn(lo, hi)
    val tt = (if (t < 0) t + hi else t).coerceIn(lo, hi).coerceAtLeast(ff)
    return when (src) {
        is JsonArray -> jsonArray(src.subList(ff, tt))
        is kotlinx.serialization.json.JsonPrimitive -> jsonString(src.content.substring(ff, tt))
    }
}

private fun evalObject(
    o: ObjectConstruct,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> {
    // Each entry produces a (key, value) cross-product; whole object is cartesian.
    val perEntry: List<List<Pair<String, JsonValue>>> =
        o.entries.map { e ->
            val out = mutableListOf<Pair<String, JsonValue>>()
            for (k in e.key.eval(ctx, input)) {
                val ks = k.asStringOrNull() ?: throw JqRuntimeError("object key must be string, got ${k.typeName()}")
                for (v in e.value.eval(ctx, input)) out += ks to v
            }
            out
        }
    return cartesian(
        perEntry,
    ).map { jsonObject(linkedMapOf<String, JsonValue>().apply { for ((k, v) in it) put(k, v) }) }
}

private fun negateNum(v: JsonValue): JsonValue {
    val l = v.asLongOrNull()
    if (l != null) return jsonNumber(-l)
    val d = v.asDoubleOrNull() ?: throw JqRuntimeError("cannot negate ${v.typeName()}")
    return numVal(-d)
}

internal fun numVal(d: Double): JsonValue =
    if (d.isFinite() && d == d.toLong().toDouble() && d > Long.MIN_VALUE.toDouble() && d < Long.MAX_VALUE.toDouble()) {
        jsonNumber(d.toLong())
    } else {
        jsonNumber(d)
    }

private fun evalBinary(
    b: BinaryOp,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> {
    if (b.op == BinOp.And || b.op == BinOp.Or) {
        return b.left.eval(ctx, input).flatMap { l ->
            when (b.op) {
                BinOp.And -> {
                    if (!l.isTruthy()) {
                        sequenceOf(jsonBool(false))
                    } else {
                        b.right.eval(ctx, input).map { jsonBool(it.isTruthy()) }
                    }
                }

                BinOp.Or -> {
                    if (l.isTruthy()) {
                        sequenceOf(jsonBool(true))
                    } else {
                        b.right.eval(ctx, input).map { jsonBool(it.isTruthy()) }
                    }
                }
            }
        }
    }
    if (b.op == BinOp.Alternative) {
        // `a // b` — yield each truthy value from a; if a produces no truthy values, yield b's outputs.
        return sequence {
            var anyTruthy = false
            try {
                for (v in b.left.eval(ctx, input)) {
                    if (v.isTruthy()) {
                        anyTruthy = true
                        yield(v)
                    }
                }
            } catch (_: JqRuntimeError) {
            }
            if (!anyTruthy) yieldAll(b.right.eval(ctx, input))
        }
    }
    return b.left.eval(ctx, input).flatMap { l ->
        b.right.eval(ctx, input).map { r -> applyBinOp(b.op, l, r) }
    }
}

private fun applyBinOp(
    op: BinOp,
    a: JsonValue,
    b: JsonValue,
): JsonValue =
    when (op) {
        BinOp.Add -> addVals(a, b)
        BinOp.Sub -> subVals(a, b)
        BinOp.Mul -> mulVals(a, b)
        BinOp.Div -> divVals(a, b)
        BinOp.Mod -> modVals(a, b)
        BinOp.Eq -> jsonBool(jqEquals(a, b))
        BinOp.Ne -> jsonBool(!jqEquals(a, b))
        BinOp.Lt -> jsonBool(jqCompare(a, b) < 0)
        BinOp.Le -> jsonBool(jqCompare(a, b) <= 0)
        BinOp.Gt -> jsonBool(jqCompare(a, b) > 0)
        BinOp.Ge -> jsonBool(jqCompare(a, b) >= 0)
        else -> error("unreachable: $op")
    }

private fun addVals(
    a: JsonValue,
    b: JsonValue,
): JsonValue {
    if (a.kind() == JsonKind.Null) return b
    if (b.kind() == JsonKind.Null) return a
    return when {
        a is JsonArray && b is JsonArray -> {
            jsonArray(a + b)
        }

        a is JsonObject && b is JsonObject -> {
            jsonObject(a + b)
        }

        a.asStringOrNull() != null && b.asStringOrNull() != null -> {
            jsonString(a.asStringOrNull()!! + b.asStringOrNull()!!)
        }

        isNumber(a) && isNumber(b) -> {
            numericOp(a, b) { x, y -> x + y }
        }

        else -> {
            throw JqRuntimeError("${a.typeName()} and ${b.typeName()} cannot be added")
        }
    }
}

private fun subVals(
    a: JsonValue,
    b: JsonValue,
): JsonValue =
    when {
        a is JsonArray && b is JsonArray -> jsonArray(a.filter { it !in b })
        isNumber(a) && isNumber(b) -> numericOp(a, b) { x, y -> x - y }
        else -> throw JqRuntimeError("${a.typeName()} and ${b.typeName()} cannot be subtracted")
    }

private fun mulVals(
    a: JsonValue,
    b: JsonValue,
): JsonValue {
    val sa = a.asStringOrNull()
    val sb = b.asStringOrNull()
    if (sa != null && isNumber(b)) {
        val n =
            b.asLongOrNull()?.toInt()
                ?: throw JqRuntimeError("string * non-integer")
        return if (n <= 0) jsonNull() else jsonString(sa.repeat(n))
    }
    if (sa != null && sb != null) {
        // string * string is split-join in jq; we'll skip for v1
        throw JqRuntimeError("string * string not supported in v1")
    }
    if (isNumber(a) && isNumber(b)) return numericOp(a, b) { x, y -> x * y }
    if (a is JsonObject && b is JsonObject) return deepMerge(a, b)
    throw JqRuntimeError("${a.typeName()} and ${b.typeName()} cannot be multiplied")
}

private fun deepMerge(
    a: JsonObject,
    b: JsonObject,
): JsonValue {
    val out = LinkedHashMap<String, JsonValue>(a)
    for ((k, v) in b) {
        val prev = out[k]
        out[k] = if (prev is JsonObject && v is JsonObject) deepMerge(prev, v) else v
    }
    return jsonObject(out)
}

private fun divVals(
    a: JsonValue,
    b: JsonValue,
): JsonValue {
    if (isNumber(a) && isNumber(b)) {
        val y = b.asDoubleOrNull()!!
        if (y ==
            0.0
        ) {
            throw JqRuntimeError(
                "${a.typeName()} and ${b.typeName()} cannot be divided because the divisor is zero",
            )
        }
        return numericOp(a, b) { x, yy -> x / yy }
    }
    val sa = a.asStringOrNull()
    val sb = b.asStringOrNull()
    if (sa != null && sb != null) {
        // split-by-separator
        val parts = if (sb.isEmpty()) sa.map { it.toString() } else sa.split(sb)
        return jsonArray(parts.map { jsonString(it) })
    }
    throw JqRuntimeError("${a.typeName()} and ${b.typeName()} cannot be divided")
}

private fun modVals(
    a: JsonValue,
    b: JsonValue,
): JsonValue {
    if (!isNumber(a) ||
        !isNumber(b)
    ) {
        throw JqRuntimeError("${a.typeName()} and ${b.typeName()} cannot be divided (mod)")
    }
    val x = a.asLongOrNull() ?: a.asDoubleOrNull()!!.toLong()
    val y = b.asLongOrNull() ?: b.asDoubleOrNull()!!.toLong()
    if (y == 0L) throw JqRuntimeError("cannot mod by zero")
    return jsonNumber(x % y)
}

internal fun isNumber(v: JsonValue): Boolean = v.kind() == JsonKind.Number

private inline fun numericOp(
    a: JsonValue,
    b: JsonValue,
    op: (Double, Double) -> Double,
): JsonValue {
    val al = a.asLongOrNull()
    val bl = b.asLongOrNull()
    if (al != null && bl != null) {
        // Try Long-exact for + - *
        val ad = al.toDouble()
        val bd = bl.toDouble()
        val r = op(ad, bd)
        return numVal(r)
    }
    val ad = a.asDoubleOrNull() ?: throw JqRuntimeError("not a number: ${a.typeName()}")
    val bd = b.asDoubleOrNull() ?: throw JqRuntimeError("not a number: ${b.typeName()}")
    return numVal(op(ad, bd))
}

internal fun jqEquals(
    a: JsonValue,
    b: JsonValue,
): Boolean {
    val ka = a.kind()
    val kb = b.kind()
    if (ka == JsonKind.Number && kb == JsonKind.Number) {
        return a.asDoubleOrNull() == b.asDoubleOrNull()
    }
    if (ka != kb) return false
    return a == b
}

/**
 * jq's total order: null < false < true < number < string < array < object.
 */
internal fun jqCompare(
    a: JsonValue,
    b: JsonValue,
): Int {
    val ra = orderRank(a)
    val rb = orderRank(b)
    if (ra != rb) return ra.compareTo(rb)
    return when (a.kind()) {
        JsonKind.Null -> 0
        JsonKind.Boolean -> a.asBoolOrNull()!!.compareTo(b.asBoolOrNull()!!)
        JsonKind.Number -> a.asDoubleOrNull()!!.compareTo(b.asDoubleOrNull()!!)
        JsonKind.String -> a.asStringOrNull()!!.compareTo(b.asStringOrNull()!!)
        JsonKind.Array -> compareArrays(a as JsonArray, b as JsonArray)
        JsonKind.Object -> compareObjects(a as JsonObject, b as JsonObject)
    }
}

private fun orderRank(v: JsonValue): Int =
    when (v.kind()) {
        JsonKind.Null -> 0
        JsonKind.Boolean -> if (v.asBoolOrNull() == false) 1 else 2
        JsonKind.Number -> 3
        JsonKind.String -> 4
        JsonKind.Array -> 5
        JsonKind.Object -> 6
    }

private fun compareArrays(
    a: JsonArray,
    b: JsonArray,
): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val c = jqCompare(a[i], b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

private fun compareObjects(
    a: JsonObject,
    b: JsonObject,
): Int {
    val ak = a.keys.sorted()
    val bk = b.keys.sorted()
    val n = minOf(ak.size, bk.size)
    for (i in 0 until n) {
        val c = ak[i].compareTo(bk[i])
        if (c != 0) return c
        val vc = jqCompare(a.getValue(ak[i]), b.getValue(bk[i]))
        if (vc != 0) return vc
    }
    return ak.size.compareTo(bk.size)
}

private fun evalReduce(
    r: Reduce,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> {
    var acc = r.init.eval(ctx, input).firstOrNull() ?: jsonNull()
    for (v in r.source.eval(ctx, input)) {
        val nctx = ctx.withVar(r.varName, v)
        acc = r.update.eval(nctx, acc).lastOrNull() ?: acc
    }
    return sequenceOf(acc)
}

private fun evalForeach(
    f: Foreach,
    ctx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> =
    sequence {
        var state = f.init.eval(ctx, input).firstOrNull() ?: jsonNull()
        for (v in f.source.eval(ctx, input)) {
            val nctx = ctx.withVar(f.varName, v)
            for (next in f.update.eval(nctx, state)) {
                state = next
                if (f.extract != null) {
                    for (out in f.extract.eval(nctx, state)) yield(out)
                } else {
                    yield(state)
                }
            }
        }
    }

/** jq's `tojson`-style encoding (compact). */
internal fun jqEncode(v: JsonValue): String =
    com.accucodeai.kash.json.KashJson
        .encode(v, pretty = false)

private fun callUserFunction(
    fn: JqFunction,
    args: List<JqExpr>,
    callerCtx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> =
    when (fn) {
        is FilterThunk -> {
            // Parameter reference: re-evaluate the bound expression at the caller's
            // *original* lexical environment (thunk.callerCtx), against the current input.
            if (args.isNotEmpty()) throw JqRuntimeError("filter parameter is not callable with arguments")
            fn.expr.eval(fn.callerCtx, input)
        }

        is UserDef -> {
            require(args.size == fn.params.size)
            bindAndEvalUserDef(fn, args, callerCtx, input)
        }
    }

private fun bindAndEvalUserDef(
    fn: UserDef,
    args: List<JqExpr>,
    callerCtx: JqContext,
    input: JsonValue,
): Sequence<JsonValue> {
    // Filter params bind directly as thunks. Value params are evaluated eagerly
    // in the caller's environment and may produce multiple values — we run the
    // body once per cartesian combination of value-param outputs.
    val filterSlots = mutableMapOf<String, FunctionSlot>()
    val valueParamNames = mutableListOf<String>()
    val valueParamStreams = mutableListOf<List<JsonValue>>()

    for ((p, a) in fn.params.zip(args)) {
        when (p) {
            is JqParam.FilterParam -> {
                val slot = FunctionSlot()
                slot.binding = FilterThunk(a, callerCtx)
                filterSlots["${p.name}/0"] = slot
            }

            is JqParam.ValueParam -> {
                valueParamNames += p.name
                valueParamStreams += a.eval(callerCtx, input).toList()
            }
        }
    }

    val funcsForBody = fn.capturedFuncs + filterSlots
    if (valueParamNames.isEmpty()) {
        val bodyCtx = JqContext(vars = callerCtx.vars, funcs = funcsForBody)
        return fn.body.eval(bodyCtx, input)
    }
    return cartesianValues(valueParamStreams).flatMap { combo ->
        var vars = callerCtx.vars
        for ((name, value) in valueParamNames.zip(combo)) vars = vars + (name to value)
        fn.body.eval(JqContext(vars = vars, funcs = funcsForBody), input)
    }
}

private fun cartesianValues(lists: List<List<JsonValue>>): Sequence<List<JsonValue>> =
    sequence {
        if (lists.any { it.isEmpty() }) return@sequence
        val idx = IntArray(lists.size)
        while (true) {
            yield(List(lists.size) { lists[it][idx[it]] })
            var i = lists.size - 1
            while (i >= 0) {
                idx[i]++
                if (idx[i] < lists[i].size) break
                idx[i] = 0
                i--
            }
            if (i < 0) return@sequence
        }
    }
