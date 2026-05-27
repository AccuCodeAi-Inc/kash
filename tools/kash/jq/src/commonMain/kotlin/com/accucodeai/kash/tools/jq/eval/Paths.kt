package com.accucodeai.kash.tools.jq.eval

import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.asLongOrNull
import com.accucodeai.kash.json.asStringOrNull
import com.accucodeai.kash.json.isTruthy
import com.accucodeai.kash.json.jsonArray
import com.accucodeai.kash.json.jsonNull
import com.accucodeai.kash.json.jsonNumber
import com.accucodeai.kash.json.jsonObject
import com.accucodeai.kash.json.jsonString
import com.accucodeai.kash.json.typeName
import com.accucodeai.kash.tools.jq.JqRuntimeError
import com.accucodeai.kash.tools.jq.ast.Comma
import com.accucodeai.kash.tools.jq.ast.ErrorExpr
import com.accucodeai.kash.tools.jq.ast.FieldAccess
import com.accucodeai.kash.tools.jq.ast.FuncCall
import com.accucodeai.kash.tools.jq.ast.Identity
import com.accucodeai.kash.tools.jq.ast.IfThenElse
import com.accucodeai.kash.tools.jq.ast.Index
import com.accucodeai.kash.tools.jq.ast.IndexKind
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.ast.Optional
import com.accucodeai.kash.tools.jq.ast.Pipe
import com.accucodeai.kash.tools.jq.ast.RecursiveDescent
import com.accucodeai.kash.tools.jq.ast.TryCatch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Path-producing interpretation of jq expressions.
 *
 * A jq filter can be used both as a value-producer (the [eval] side) and a
 * path-producer when it appears on the left of `=`, `|=`, `del(...)`, etc.
 * Only filters that name locations in the input can produce paths — literals,
 * arithmetic, and constructors cannot.
 *
 * Each path is a `List<JsonValue>` whose elements are strings (for object
 * keys) and integers (for array indices), matching jq's wire format for paths.
 */
internal fun JqExpr.paths(
    ctx: JqContext,
    input: JsonValue,
): Sequence<List<JsonValue>> =
    when (this) {
        Identity -> {
            sequenceOf(emptyList())
        }

        RecursiveDescent -> {
            allLocations(input, emptyList())
        }

        is FieldAccess -> {
            source.paths(ctx, input).map { p -> p + jsonString(name) }
        }

        is Index -> {
            sequence {
                for (p in source.paths(ctx, input)) {
                    val cur = getPath(input, p) ?: jsonNull()
                    when (val k = index) {
                        IndexKind.Iterate -> {
                            when (cur) {
                                is JsonArray -> for (i in 0 until cur.size) yield(p + jsonNumber(i.toLong()))
                                is JsonObject -> for (key in cur.keys) yield(p + jsonString(key))
                                is JsonNull -> if (!optional) throw JqRuntimeError("Cannot iterate over null")
                                else -> if (!optional) throw JqRuntimeError("Cannot iterate over ${cur.typeName()}")
                            }
                        }

                        is IndexKind.At -> {
                            for (key in k.key.eval(ctx, input)) {
                                yield(p + key)
                            }
                        }

                        is IndexKind.Slice -> {
                            // path-producing slice is uncommon; surface the slice as a single path with
                            // a {start, end} marker — but jq doesn't allow assigning to slices in v1 scope.
                            throw JqRuntimeError("slice paths are not supported")
                        }
                    }
                }
            }
        }

        is Pipe -> {
            sequence {
                for (pa in left.paths(ctx, input)) {
                    val sub = getPath(input, pa) ?: jsonNull()
                    for (pb in right.paths(ctx, sub)) yield(pa + pb)
                }
            }
        }

        is Comma -> {
            left.paths(ctx, input) + right.paths(ctx, input)
        }

        is Optional -> {
            sequence {
                try {
                    yieldAll(body.paths(ctx, input))
                } catch (_: JqRuntimeError) {
                }
            }
        }

        is TryCatch -> {
            sequence {
                try {
                    yieldAll(body.paths(ctx, input))
                } catch (_: JqRuntimeError) {
                }
            }
        }

        is IfThenElse -> {
            sequence {
                for (c in cond.eval(ctx, input)) {
                    var picked: JqExpr = elseBranch
                    if (c.isTruthy()) {
                        picked = thenBranch
                    } else {
                        for ((ec, et) in elifBranches) {
                            if (ec.eval(ctx, input).any { it.isTruthy() }) {
                                picked = et
                                break
                            }
                        }
                    }
                    yieldAll(picked.paths(ctx, input))
                }
            }
        }

        is FuncCall -> {
            when (name) {
                // `select(f)` passes the input path through when f is truthy.
                "select" -> {
                    if (args.size == 1) {
                        sequence {
                            if (args[0].eval(ctx, input).any { it.isTruthy() }) yield(emptyList())
                        }
                    } else {
                        throw JqRuntimeError("select/${args.size} is not a path expression")
                    }
                }

                // Path-producing identity-style builtins fall through to "not a path".
                else -> {
                    throw JqRuntimeError("$name is not a path expression")
                }
            }
        }

        is ErrorExpr -> {
            // Trigger the error during path evaluation too — keeps behavior aligned with eval.
            val msg = message?.eval(ctx, input)?.firstOrNull()?.asStringOrNull() ?: "error"
            throw JqRuntimeError(msg)
        }

        else -> {
            throw JqRuntimeError("expression is not a path expression")
        }
    }

/**
 * Read the value at [path] in [root], returning null if any segment is missing
 * (jq's `getpath` returns `null` for missing paths rather than failing).
 */
internal fun getPath(
    root: JsonValue,
    path: List<JsonValue>,
): JsonValue? {
    var cur: JsonValue = root
    for (seg in path) {
        cur =
            when (cur) {
                is JsonObject -> {
                    val k = seg.asStringOrNull() ?: return null
                    cur[k] ?: return jsonNull()
                }

                is JsonArray -> {
                    val i = seg.asLongOrNull()?.toInt() ?: return null
                    val ix = if (i < 0) i + cur.size else i
                    if (ix in 0 until cur.size) cur[ix] else return jsonNull()
                }

                is JsonNull -> {
                    return jsonNull()
                }

                else -> {
                    return null
                }
            }
    }
    return cur
}

/**
 * Return a new value with [path] set to [newValue] inside [root]. Missing
 * intermediate objects/arrays are auto-created (mirroring jq).
 */
internal fun setPath(
    root: JsonValue,
    path: List<JsonValue>,
    newValue: JsonValue,
): JsonValue {
    if (path.isEmpty()) return newValue
    val head = path.first()
    val rest = path.drop(1)
    return when {
        head.asStringOrNull() != null -> {
            val key = head.asStringOrNull()!!
            val obj: JsonObject =
                when (root) {
                    is JsonObject -> root
                    is JsonNull -> JsonObject(emptyMap())
                    else -> throw JqRuntimeError("Cannot index ${root.typeName()} with \"$key\"")
                }
            val child = obj[key] ?: jsonNull()
            val updated = setPath(child, rest, newValue)
            val newMap = LinkedHashMap<String, JsonValue>(obj)
            newMap[key] = updated
            jsonObject(newMap)
        }

        head.asLongOrNull() != null -> {
            val i = head.asLongOrNull()!!.toInt()
            val arr: JsonArray =
                when (root) {
                    is JsonArray -> root
                    is JsonNull -> JsonArray(emptyList())
                    else -> throw JqRuntimeError("Cannot index ${root.typeName()} with $i")
                }
            val ix = if (i < 0) i + arr.size else i
            if (ix < 0) throw JqRuntimeError("Out of range negative array index")
            val padded =
                MutableList<JsonValue>(maxOf(arr.size, ix + 1)) { idx ->
                    if (idx < arr.size) arr[idx] else jsonNull()
                }
            val child = padded[ix]
            padded[ix] = setPath(child, rest, newValue)
            jsonArray(padded)
        }

        else -> {
            throw JqRuntimeError("path segment must be string or number, got ${head.typeName()}")
        }
    }
}

/**
 * Apply [transform] (a value-to-value function) at [path] inside [root].
 * If [transform] returns null, the path is deleted instead.
 */
internal fun updatePath(
    root: JsonValue,
    path: List<JsonValue>,
    transform: (JsonValue) -> JsonValue?,
): JsonValue {
    val current = getPath(root, path) ?: jsonNull()
    val next = transform(current)
    return if (next == null) deletePath(root, path) else setPath(root, path, next)
}

/**
 * Return a new value with [path] removed from [root]. Missing paths are a
 * no-op.
 */
internal fun deletePath(
    root: JsonValue,
    path: List<JsonValue>,
): JsonValue {
    if (path.isEmpty()) return jsonNull()
    if (path.size == 1) {
        val head = path[0]
        return when {
            root is JsonObject && head.asStringOrNull() != null -> {
                jsonObject(root.toMap() - head.asStringOrNull()!!)
            }

            root is JsonArray && head.asLongOrNull() != null -> {
                val i = head.asLongOrNull()!!.toInt()
                val ix = if (i < 0) i + root.size else i
                if (ix !in 0 until root.size) {
                    root
                } else {
                    jsonArray(root.toMutableList().also { it.removeAt(ix) })
                }
            }

            else -> {
                root
            }
        }
    }
    // Recurse into head, replacing child with del(rest, child).
    val head = path[0]
    val rest = path.drop(1)
    return when {
        root is JsonObject && head.asStringOrNull() != null -> {
            val key = head.asStringOrNull()!!
            val child = root[key] ?: return root
            val updated = deletePath(child, rest)
            val m = LinkedHashMap<String, JsonValue>(root)
            m[key] = updated
            jsonObject(m)
        }

        root is JsonArray && head.asLongOrNull() != null -> {
            val i = head.asLongOrNull()!!.toInt()
            val ix = if (i < 0) i + root.size else i
            if (ix !in 0 until root.size) return root
            val updated = deletePath(root[ix], rest)
            val list = root.toMutableList()
            list[ix] = updated
            jsonArray(list)
        }

        else -> {
            root
        }
    }
}

/**
 * Delete a list of paths from [root]. Paths are sorted in reverse so that
 * deleting earlier siblings doesn't shift the indices of later ones.
 */
internal fun deletePaths(
    root: JsonValue,
    paths: List<List<JsonValue>>,
): JsonValue {
    val sorted = paths.sortedWith(PathComparator.reversed())
    var cur = root
    for (p in sorted) cur = deletePath(cur, p)
    return cur
}

/** Lexicographic comparison on path segments (strings come after numbers, like jq). */
private object PathComparator : Comparator<List<JsonValue>> {
    override fun compare(
        a: List<JsonValue>,
        b: List<JsonValue>,
    ): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = compareSeg(a[i], b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }

    private fun compareSeg(
        a: JsonValue,
        b: JsonValue,
    ): Int {
        val la = a.asLongOrNull()
        val lb = b.asLongOrNull()
        if (la != null && lb != null) return la.compareTo(lb)
        if (la != null) return -1
        if (lb != null) return 1
        return (a.asStringOrNull() ?: "").compareTo(b.asStringOrNull() ?: "")
    }
}

/** All paths in a value (used by `..` as a path producer). */
private fun allLocations(
    v: JsonValue,
    path: List<JsonValue>,
): Sequence<List<JsonValue>> =
    sequence {
        yield(path)
        when (v) {
            is JsonArray -> {
                for ((i, e) in v.withIndex()) yieldAll(allLocations(e, path + jsonNumber(i.toLong())))
            }

            is JsonObject -> {
                for ((k, e) in v) yieldAll(allLocations(e, path + jsonString(k)))
            }

            else -> {}
        }
    }
