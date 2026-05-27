package com.accucodeai.kash.tools.jq.builtins

import com.accucodeai.kash.json.JsonKind
import com.accucodeai.kash.json.JsonValue
import com.accucodeai.kash.json.KashJson
import com.accucodeai.kash.json.asArrayOrNull
import com.accucodeai.kash.json.asDoubleOrNull
import com.accucodeai.kash.json.asLongOrNull
import com.accucodeai.kash.json.asObjectOrNull
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
import com.accucodeai.kash.shared.regex.RegexCapture
import com.accucodeai.kash.shared.regex.RegexMatch
import com.accucodeai.kash.tools.jq.JqRuntimeError
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.eval.JqContext
import com.accucodeai.kash.tools.jq.eval.deletePaths
import com.accucodeai.kash.tools.jq.eval.eval
import com.accucodeai.kash.tools.jq.eval.getPath
import com.accucodeai.kash.tools.jq.eval.isNumber
import com.accucodeai.kash.tools.jq.eval.jqCompare
import com.accucodeai.kash.tools.jq.eval.jqEquals
import com.accucodeai.kash.tools.jq.eval.numVal
import com.accucodeai.kash.tools.jq.eval.paths
import com.accucodeai.kash.tools.jq.eval.setPath
import com.accucodeai.kash.tools.jq.regex.compileRegex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal object Builtins {
    fun invoke(
        name: String,
        args: List<JqExpr>,
        ctx: JqContext,
        input: JsonValue,
    ): Sequence<JsonValue> {
        val key = "$name/${args.size}"
        val impl = registry[key] ?: throw JqRuntimeError("$name/${args.size} is not defined")
        return impl(args, ctx, input)
    }

    private val registry: Map<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>> =
        buildMap {
            // ----- zero-arg --------------------------------------------------------
            put0("length") { v ->
                when (v.kind()) {
                    JsonKind.Null -> {
                        jsonNumber(0L)
                    }

                    JsonKind.String -> {
                        jsonNumber(v.asStringOrNull()!!.length.toLong())
                    }

                    JsonKind.Array -> {
                        jsonNumber((v as JsonArray).size.toLong())
                    }

                    JsonKind.Object -> {
                        jsonNumber((v as JsonObject).size.toLong())
                    }

                    JsonKind.Number -> {
                        val d = v.asDoubleOrNull()!!
                        if (d < 0) numVal(-d) else v
                    }

                    else -> {
                        throw JqRuntimeError("${v.typeName()} has no length")
                    }
                }
            }
            put0("utf8bytelength") { v ->
                val s = v.asStringOrNull() ?: throw JqRuntimeError("utf8bytelength requires string")
                jsonNumber(s.encodeToByteArray().size.toLong())
            }
            put0("keys") { v -> jsonArray(keysOf(v, sorted = true).map(::jsonString)) }
            put0("keys_unsorted") { v -> jsonArray(keysOf(v, sorted = false).map(::jsonString)) }
            put0("values") { v ->
                when (v) {
                    is JsonObject -> jsonArray(v.values.toList())
                    is JsonArray -> v
                    else -> throw JqRuntimeError("${v.typeName()} has no values")
                }
            }
            put0("type") { v -> jsonString(v.typeName()) }
            put0("empty") { _ -> null }
            put0("not") { v -> jsonBool(!v.isTruthy()) }
            put0("tostring") { v -> jsonString(v.asStringOrNull() ?: KashJson.encode(v)) }
            put0("tonumber") { v ->
                when {
                    isNumber(v) -> {
                        v
                    }

                    v.asStringOrNull() != null -> {
                        val s = v.asStringOrNull()!!
                        val l = s.toLongOrNull()
                        if (l != null) {
                            jsonNumber(l)
                        } else {
                            jsonNumber(s.toDoubleOrNull() ?: throw JqRuntimeError("$s is not a number"))
                        }
                    }

                    else -> {
                        throw JqRuntimeError("${v.typeName()} cannot be parsed as number")
                    }
                }
            }
            put0("tojson") { v -> jsonString(KashJson.encode(v)) }
            put0("fromjson") { v ->
                val s = v.asStringOrNull() ?: throw JqRuntimeError("fromjson requires string")
                try {
                    KashJson.parse(s)
                } catch (e: Exception) {
                    throw JqRuntimeError("fromjson: ${e.message}")
                }
            }
            put0("ascii_downcase") { v ->
                val s = v.asStringOrNull() ?: throw JqRuntimeError("ascii_downcase requires string")
                jsonString(s.map { if (it in 'A'..'Z') (it.code + 32).toChar() else it }.joinToString(""))
            }
            put0("ascii_upcase") { v ->
                val s = v.asStringOrNull() ?: throw JqRuntimeError("ascii_upcase requires string")
                jsonString(s.map { if (it in 'a'..'z') (it.code - 32).toChar() else it }.joinToString(""))
            }
            put0("reverse") { v ->
                when (v) {
                    is JsonArray -> {
                        jsonArray(v.reversed())
                    }

                    else -> {
                        val s = v.asStringOrNull() ?: throw JqRuntimeError("${v.typeName()} cannot be reversed")
                        jsonString(s.reversed())
                    }
                }
            }
            put0("sort") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("sort: ${v.typeName()} is not an array")
                jsonArray(a.sortedWith(::jqCompare))
            }
            put0("unique") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("unique: ${v.typeName()} is not an array")
                val sorted = a.sortedWith(::jqCompare)
                val out = mutableListOf<JsonValue>()
                for (x in sorted) if (out.isEmpty() || !jqEquals(out.last(), x)) out += x
                jsonArray(out)
            }
            put0("min") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("min: ${v.typeName()} is not an array")
                if (a.isEmpty()) jsonNull() else a.reduce { x, y -> if (jqCompare(x, y) <= 0) x else y }
            }
            put0("max") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("max: ${v.typeName()} is not an array")
                if (a.isEmpty()) jsonNull() else a.reduce { x, y -> if (jqCompare(x, y) >= 0) x else y }
            }
            put0("add") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("add: ${v.typeName()} is not an array")
                if (a.isEmpty()) {
                    jsonNull()
                } else {
                    a.reduce { acc, x ->
                        when {
                            acc is JsonArray && x is JsonArray -> {
                                jsonArray(acc + x)
                            }

                            acc is JsonObject && x is JsonObject -> {
                                jsonObject(acc + x)
                            }

                            acc.asStringOrNull() != null && x.asStringOrNull() != null -> {
                                jsonString(acc.asStringOrNull()!! + x.asStringOrNull()!!)
                            }

                            isNumber(acc) && isNumber(x) -> {
                                numVal((acc.asDoubleOrNull() ?: 0.0) + (x.asDoubleOrNull() ?: 0.0))
                            }

                            else -> {
                                throw JqRuntimeError("add: ${acc.typeName()} + ${x.typeName()}")
                            }
                        }
                    }
                }
            }
            put0("floor") { v -> numericUnary(v) { kotlin.math.floor(it) } }
            put0("ceil") { v -> numericUnary(v) { kotlin.math.ceil(it) } }
            put0("fabs") { v -> numericUnary(v) { kotlin.math.abs(it) } }
            put0("sqrt") { v -> numericUnary(v) { kotlin.math.sqrt(it) } }
            // paths/0 and leaf_paths/0 are streams in jq — emit each path separately.
            this["paths/0"] = { _, _, input -> allPaths(input).map { jsonArray(it) } }
            this["leaf_paths/0"] = { _, _, input -> leafPaths(input).map { jsonArray(it) } }
            put0("to_entries") { v ->
                val o = v.asObjectOrNull() ?: throw JqRuntimeError("to_entries: ${v.typeName()} is not an object")
                jsonArray(o.entries.map { (k, vv) -> jsonObject(linkedMapOf("key" to jsonString(k), "value" to vv)) })
            }
            put0("from_entries") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("from_entries: ${v.typeName()} is not an array")
                val out = linkedMapOf<String, JsonValue>()
                for (e in a) {
                    val o = e as? JsonObject ?: throw JqRuntimeError("from_entries: entry must be object")
                    val keyVal =
                        o["key"] ?: o["k"] ?: o["name"]
                            ?: throw JqRuntimeError("from_entries: missing key")
                    val key =
                        keyVal.asStringOrNull()
                            ?: keyVal.asLongOrNull()?.toString()
                            ?: KashJson.encode(keyVal)
                    val value = o["value"] ?: o["v"] ?: jsonNull()
                    out[key] = value
                }
                jsonObject(out)
            }
            put0("first") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("first: ${v.typeName()} is not an array")
                a.firstOrNull() ?: jsonNull()
            }
            put0("last") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("last: ${v.typeName()} is not an array")
                a.lastOrNull() ?: jsonNull()
            }
            put0("any") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("any: ${v.typeName()} is not an array")
                jsonBool(a.any { it.isTruthy() })
            }
            put0("all") { v ->
                val a = v.asArrayOrNull() ?: throw JqRuntimeError("all: ${v.typeName()} is not an array")
                jsonBool(a.all { it.isTruthy() })
            }

            // recurse/0 — emits self then all descendants (depth-first)
            this["recurse/0"] = { _, _, input ->
                recurseAll(input)
            }
            this["recurse_down/0"] = this["recurse/0"]!!

            // ----- one-arg --------------------------------------------------------
            put1Seq("select") { f, ctx, input ->
                sequence {
                    for (b in f.eval(ctx, input)) if (b.isTruthy()) yield(input)
                }
            }
            put1Seq("map") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("map: ${input.typeName()} is not an array")
                val out = mutableListOf<JsonValue>()
                for (x in a) for (y in f.eval(ctx, x)) out += y
                sequenceOf(jsonArray(out))
            }
            put1Seq("map_values") { f, ctx, input ->
                when (input) {
                    is JsonObject -> {
                        val out = linkedMapOf<String, JsonValue>()
                        for ((k, vv) in input) {
                            val r = f.eval(ctx, vv).firstOrNull()
                            if (r != null) out[k] = r
                        }
                        sequenceOf(jsonObject(out))
                    }

                    is JsonArray -> {
                        val out = mutableListOf<JsonValue>()
                        for (vv in input) {
                            val r = f.eval(ctx, vv).firstOrNull()
                            if (r != null) out += r
                        }
                        sequenceOf(jsonArray(out))
                    }

                    else -> {
                        throw JqRuntimeError("map_values: ${input.typeName()}")
                    }
                }
            }
            put1Seq("with_entries") { f, ctx, input ->
                val o =
                    input.asObjectOrNull() ?: throw JqRuntimeError("with_entries: ${input.typeName()} is not an object")
                val entries =
                    o.entries.map { (k, vv) ->
                        jsonObject(linkedMapOf("key" to jsonString(k), "value" to vv))
                    }
                val transformed = mutableListOf<JsonValue>()
                for (e in entries) for (y in f.eval(ctx, e)) transformed += y
                val out = linkedMapOf<String, JsonValue>()
                for (e in transformed) {
                    val obj = e as? JsonObject ?: throw JqRuntimeError("with_entries: result entry not object")
                    val keyVal =
                        obj["key"] ?: obj["k"] ?: obj["name"]
                            ?: throw JqRuntimeError("with_entries: missing key")
                    val key = keyVal.asStringOrNull() ?: KashJson.encode(keyVal)
                    val value = obj["value"] ?: obj["v"] ?: jsonNull()
                    out[key] = value
                }
                sequenceOf(jsonObject(out))
            }
            put1("has") { v, key ->
                when (v) {
                    is JsonObject -> {
                        val k = key.asStringOrNull() ?: throw JqRuntimeError("has: key must be string for object")
                        jsonBool(k in v)
                    }

                    is JsonArray -> {
                        val i =
                            key.asLongOrNull()?.toInt() ?: throw JqRuntimeError("has: index must be integer for array")
                        jsonBool(i in v.indices)
                    }

                    else -> {
                        throw JqRuntimeError("has: ${v.typeName()}")
                    }
                }
            }
            put1("in") { v, container ->
                when (container) {
                    is JsonObject -> {
                        val s = v.asStringOrNull() ?: throw JqRuntimeError("in: key must be string")
                        jsonBool(s in container)
                    }

                    is JsonArray -> {
                        val i = v.asLongOrNull()?.toInt() ?: throw JqRuntimeError("in: index must be integer")
                        jsonBool(i in container.indices)
                    }

                    else -> {
                        throw JqRuntimeError("in: ${container.typeName()}")
                    }
                }
            }
            put1("contains") { a, b -> jsonBool(jqContains(a, b)) }
            put1("startswith") { a, b ->
                val sa = a.asStringOrNull() ?: throw JqRuntimeError("startswith: ${a.typeName()}")
                val sb = b.asStringOrNull() ?: throw JqRuntimeError("startswith: ${b.typeName()}")
                jsonBool(sa.startsWith(sb))
            }
            put1("endswith") { a, b ->
                val sa = a.asStringOrNull() ?: throw JqRuntimeError("endswith: ${a.typeName()}")
                val sb = b.asStringOrNull() ?: throw JqRuntimeError("endswith: ${b.typeName()}")
                jsonBool(sa.endsWith(sb))
            }
            put1("ltrimstr") { a, b ->
                val sa = a.asStringOrNull() ?: return@put1 a
                val sb = b.asStringOrNull() ?: return@put1 a
                jsonString(if (sa.startsWith(sb)) sa.removePrefix(sb) else sa)
            }
            put1("rtrimstr") { a, b ->
                val sa = a.asStringOrNull() ?: return@put1 a
                val sb = b.asStringOrNull() ?: return@put1 a
                jsonString(if (sa.endsWith(sb)) sa.removeSuffix(sb) else sa)
            }
            put1("split") { a, b ->
                val sa = a.asStringOrNull() ?: throw JqRuntimeError("split: ${a.typeName()}")
                val sb = b.asStringOrNull() ?: throw JqRuntimeError("split: ${b.typeName()}")
                jsonArray((if (sb.isEmpty()) sa.map { it.toString() } else sa.split(sb)).map(::jsonString))
            }
            put1("join") { a, b ->
                val arr = a.asArrayOrNull() ?: throw JqRuntimeError("join: ${a.typeName()} is not an array")
                val sep = b.asStringOrNull() ?: throw JqRuntimeError("join: separator must be string")
                jsonString(
                    arr.joinToString(sep) {
                        when {
                            it.kind() == JsonKind.Null -> ""
                            it.asStringOrNull() != null -> it.asStringOrNull()!!
                            else -> KashJson.encode(it)
                        }
                    },
                )
            }
            put1Seq("sort_by") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("sort_by: ${input.typeName()} is not an array")
                val keyed = a.map { x -> x to (f.eval(ctx, x).firstOrNull() ?: jsonNull()) }
                sequenceOf(jsonArray(keyed.sortedWith(compareBy(::jqCompare) { it.second }).map { it.first }))
            }
            put1Seq("group_by") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("group_by: ${input.typeName()} is not an array")
                val keyed =
                    a
                        .map { x -> x to (f.eval(ctx, x).firstOrNull() ?: jsonNull()) }
                        .sortedWith(compareBy(::jqCompare) { it.second })
                val groups = mutableListOf<MutableList<JsonValue>>()
                var lastKey: JsonValue? = null
                for ((x, k) in keyed) {
                    if (groups.isEmpty() || !jqEquals(lastKey!!, k)) {
                        groups.add(mutableListOf())
                        lastKey = k
                    }
                    groups.last().add(x)
                }
                sequenceOf(jsonArray(groups.map { jsonArray(it) }))
            }
            put1Seq("unique_by") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("unique_by: ${input.typeName()} is not an array")
                val keyed =
                    a
                        .map { x -> x to (f.eval(ctx, x).firstOrNull() ?: jsonNull()) }
                        .sortedWith(compareBy(::jqCompare) { it.second })
                val out = mutableListOf<JsonValue>()
                var lastKey: JsonValue? = null
                for ((x, k) in keyed) {
                    if (out.isEmpty() || !jqEquals(lastKey!!, k)) {
                        out += x
                        lastKey = k
                    }
                }
                sequenceOf(jsonArray(out))
            }
            put1Seq("min_by") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("min_by: ${input.typeName()} is not an array")
                if (a.isEmpty()) {
                    sequenceOf(jsonNull())
                } else {
                    val keyed = a.map { x -> x to (f.eval(ctx, x).firstOrNull() ?: jsonNull()) }
                    sequenceOf(keyed.reduce { acc, e -> if (jqCompare(e.second, acc.second) < 0) e else acc }.first)
                }
            }
            put1Seq("max_by") { f, ctx, input ->
                val a = input.asArrayOrNull() ?: throw JqRuntimeError("max_by: ${input.typeName()} is not an array")
                if (a.isEmpty()) {
                    sequenceOf(jsonNull())
                } else {
                    val keyed = a.map { x -> x to (f.eval(ctx, x).firstOrNull() ?: jsonNull()) }
                    sequenceOf(keyed.reduce { acc, e -> if (jqCompare(e.second, acc.second) > 0) e else acc }.first)
                }
            }
            // recurse(f) — emits self then values from f(self), recursively
            put1Seq("recurse") { f, ctx, input ->
                sequence {
                    val stack = ArrayDeque<JsonValue>()
                    stack.addLast(input)
                    while (stack.isNotEmpty()) {
                        val v = stack.removeLast()
                        yield(v)
                        val children =
                            try {
                                f.eval(ctx, v).toList()
                            } catch (_: JqRuntimeError) {
                                emptyList()
                            }
                        for (c in children.asReversed()) stack.addLast(c)
                    }
                }
            }
            // range(n) — emits 0 .. n-1
            put1Seq("range") { f, ctx, input ->
                sequence {
                    val n =
                        f.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    for (i in 0 until n) yield(jsonNumber(i))
                }
            }

            // path(f) — emit each path that f produces (relative to input)
            put1Seq("path") { f, ctx, input ->
                f.paths(ctx, input).map { p -> jsonArray(p) }
            }

            // paths(f) — paths to values where f is truthy at that location
            put1Seq("paths") { f, ctx, input ->
                walkPathsWhere(input, emptyList()) { f.eval(ctx, it).any { v -> v.isTruthy() } }
            }

            // getpath(p) — return value at path array p inside input
            put1Seq("getpath") { f, ctx, input ->
                f.eval(ctx, input).map { p ->
                    val arr = p as? JsonArray ?: throw JqRuntimeError("getpath: path must be array")
                    getPath(input, arr.toList()) ?: jsonNull()
                }
            }

            // del(p) — delete each path produced by p from input; emit single modified value
            put1Seq("del") { f, ctx, input ->
                val pathList = f.paths(ctx, input).map { it.toList() }.toList()
                sequenceOf(deletePaths(input, pathList))
            }

            // walk(f) — bottom-up: apply f to leaves, then to containers built from transformed children
            put1Seq("walk") { f, ctx, input ->
                fun go(v: JsonValue): JsonValue {
                    val rebuilt: JsonValue =
                        when (v) {
                            is JsonArray -> {
                                jsonArray(v.map { go(it) })
                            }

                            is JsonObject -> {
                                jsonObject(
                                    linkedMapOf<String, JsonValue>().apply {
                                        for ((k, vv) in v) put(k, go(vv))
                                    },
                                )
                            }

                            else -> {
                                v
                            }
                        }
                    return f.eval(ctx, rebuilt).firstOrNull() ?: jsonNull()
                }
                sequenceOf(go(input))
            }

            // first(f) — first output of f applied to input
            put1Seq("first") { f, ctx, input ->
                val r = f.eval(ctx, input).firstOrNull()
                if (r == null) emptySequence() else sequenceOf(r)
            }

            // last(f) — last output of f applied to input
            put1Seq("last") { f, ctx, input ->
                val r = f.eval(ctx, input).lastOrNull()
                if (r == null) emptySequence() else sequenceOf(r)
            }

            // ----- regex (RE2/J) -------------------------------------------------
            // test(re) / test(re; flags) — boolean: does input string contain a match
            put1Seq("test") { f, ctx, input ->
                val s = input.asStringOrNull() ?: throw JqRuntimeError("test: input must be string")
                f.eval(ctx, input).map { p ->
                    val pat = p.asStringOrNull() ?: throw JqRuntimeError("test: pattern must be string")
                    jsonBool(compileRegex(pat, "").containsMatch(s))
                }
            }
            put2Seq("test") { f, g, ctx, input ->
                val s = input.asStringOrNull() ?: throw JqRuntimeError("test: input must be string")
                sequence {
                    for (p in f.eval(ctx, input)) {
                        for (fl in g.eval(ctx, input)) {
                            val pat = p.asStringOrNull() ?: throw JqRuntimeError("test: pattern must be string")
                            val flg = fl.asStringOrNull() ?: throw JqRuntimeError("test: flags must be string")
                            yield(jsonBool(compileRegex(pat, flg).containsMatch(s)))
                        }
                    }
                }
            }

            // match(re) / match(re; flags) — match object(s). With `g` flag emits each match.
            put1Seq("match") { f, ctx, input ->
                matchSeq(f, null, ctx, input)
            }
            put2Seq("match") { f, g, ctx, input ->
                matchSeq(f, g, ctx, input)
            }

            // capture(re) / capture(re; flags) — object of named captures
            put1Seq("capture") { f, ctx, input ->
                matchSeq(f, null, ctx, input).map { matchObjToCaptureObj(it) }
            }
            put2Seq("capture") { f, g, ctx, input ->
                matchSeq(f, g, ctx, input).map { matchObjToCaptureObj(it) }
            }

            // scan(re) — stream of matches: text for no-capture, array of capture texts for capture
            put1Seq("scan") { f, ctx, input ->
                val s = input.asStringOrNull() ?: throw JqRuntimeError("scan: input must be string")
                sequence {
                    for (p in f.eval(ctx, input)) {
                        val pat = p.asStringOrNull() ?: throw JqRuntimeError("scan: pattern must be string")
                        val re = compileRegex(pat, "g")
                        for (m in re.findAll(s)) {
                            if (m.captures.isEmpty()) {
                                yield(jsonString(m.text))
                            } else {
                                yield(jsonArray(m.captures.map { jsonString(it.text ?: "") }))
                            }
                        }
                    }
                }
            }

            // sub(re; repl) / sub(re; repl; flags) — replace first match; repl is a filter.
            put2Seq("sub") { f, repl, ctx, input ->
                subSeq(f, repl, flagsFilter = null, global = false, ctx = ctx, input = input)
            }
            put3Seq("sub") { f, repl, flags, ctx, input ->
                subSeq(f, repl, flagsFilter = flags, global = false, ctx = ctx, input = input)
            }

            // gsub(re; repl) / gsub(re; repl; flags) — replace all matches.
            put2Seq("gsub") { f, repl, ctx, input ->
                subSeq(f, repl, flagsFilter = null, global = true, ctx = ctx, input = input)
            }
            put3Seq("gsub") { f, repl, flags, ctx, input ->
                subSeq(f, repl, flagsFilter = flags, global = true, ctx = ctx, input = input)
            }

            // ----- two-arg --------------------------------------------------------
            // setpath(p; v) — return input with path p set to v
            put2Seq("setpath") { p, v, ctx, input ->
                sequence {
                    for (pv in p.eval(ctx, input)) {
                        val arr = pv as? JsonArray ?: throw JqRuntimeError("setpath: path must be array")
                        for (vv in v.eval(ctx, input)) yield(setPath(input, arr.toList(), vv))
                    }
                }
            }

            // limit(n; f) — take first n outputs of f
            put2Seq("limit") { n, f, ctx, input ->
                sequence {
                    val count =
                        n.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("limit: integer expected")
                    if (count <= 0) return@sequence
                    var emitted = 0L
                    for (v in f.eval(ctx, input)) {
                        yield(v)
                        emitted++
                        if (emitted >= count) break
                    }
                }
            }

            // nth(n; f) — nth output of f (0-indexed)
            put2Seq("nth") { n, f, ctx, input ->
                sequence {
                    val target =
                        n.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("nth: integer expected")
                    if (target < 0) throw JqRuntimeError("nth: negative index")
                    var i = 0L
                    for (v in f.eval(ctx, input)) {
                        if (i == target) {
                            yield(v)
                            return@sequence
                        }
                        i++
                    }
                }
            }

            // until(cond; update) — apply update repeatedly until cond is truthy; emit final value
            put2Seq("until") { cond, update, ctx, input ->
                sequence {
                    var cur = input
                    while (true) {
                        val ok = cond.eval(ctx, cur).firstOrNull()?.isTruthy() ?: false
                        if (ok) {
                            yield(cur)
                            return@sequence
                        }
                        cur = update.eval(ctx, cur).firstOrNull() ?: jsonNull()
                    }
                }
            }

            // while(cond; update) — emit input, then update, then update(update), ... while cond is truthy
            put2Seq("while") { cond, update, ctx, input ->
                sequence {
                    var cur = input
                    while (cond.eval(ctx, cur).firstOrNull()?.isTruthy() == true) {
                        yield(cur)
                        cur = update.eval(ctx, cur).firstOrNull() ?: jsonNull()
                    }
                }
            }

            put2Seq("range") { from, to, ctx, input ->
                sequence {
                    val a =
                        from.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    val b =
                        to.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    for (i in a until b) yield(jsonNumber(i))
                }
            }

            // ----- three-arg ------------------------------------------------------
            put3Seq("range") { from, to, step, ctx, input ->
                sequence {
                    val a =
                        from.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    val b =
                        to.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    val s =
                        step.eval(ctx, input).firstOrNull()?.asLongOrNull()
                            ?: throw JqRuntimeError("range: integer expected")
                    if (s == 0L) throw JqRuntimeError("range: step cannot be zero")
                    if (s > 0) {
                        var i = a
                        while (i < b) {
                            yield(jsonNumber(i))
                            i += s
                        }
                    } else {
                        var i = a
                        while (i > b) {
                            yield(jsonNumber(i))
                            i += s
                        }
                    }
                }
            }
        }

    // ---- registration helpers ----------------------------------------------

    private fun MutableMap<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>>.put0(
        name: String,
        body: (JsonValue) -> JsonValue?,
    ) {
        this["$name/0"] = { _, _, input ->
            val r = body(input)
            if (r == null) emptySequence() else sequenceOf(r)
        }
    }

    private fun MutableMap<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>>.put1(
        name: String,
        body: (JsonValue, JsonValue) -> JsonValue,
    ) {
        this["$name/1"] = { args, ctx, input ->
            args[0].eval(ctx, input).map { a -> body(input, a) }
        }
    }

    private fun MutableMap<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>>.put1Seq(
        name: String,
        body: (JqExpr, JqContext, JsonValue) -> Sequence<JsonValue>,
    ) {
        this["$name/1"] = { args, ctx, input -> body(args[0], ctx, input) }
    }

    private fun MutableMap<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>>.put2Seq(
        name: String,
        body: (JqExpr, JqExpr, JqContext, JsonValue) -> Sequence<JsonValue>,
    ) {
        this["$name/2"] = { args, ctx, input -> body(args[0], args[1], ctx, input) }
    }

    private fun MutableMap<String, (List<JqExpr>, JqContext, JsonValue) -> Sequence<JsonValue>>.put3Seq(
        name: String,
        body: (JqExpr, JqExpr, JqExpr, JqContext, JsonValue) -> Sequence<JsonValue>,
    ) {
        this["$name/3"] = { args, ctx, input -> body(args[0], args[1], args[2], ctx, input) }
    }

    // ---- helpers ------------------------------------------------------------

    private fun keysOf(
        v: JsonValue,
        sorted: Boolean,
    ): List<String> =
        when (v) {
            is JsonObject -> if (sorted) v.keys.sorted() else v.keys.toList()
            is JsonArray -> v.indices.map { it.toString() }
            else -> throw JqRuntimeError("keys: ${v.typeName()} has no keys")
        }

    private fun numericUnary(
        v: JsonValue,
        f: (Double) -> Double,
    ): JsonValue {
        val d = v.asDoubleOrNull() ?: throw JqRuntimeError("not a number: ${v.typeName()}")
        return numVal(f(d))
    }

    private fun recurseAll(v: JsonValue): Sequence<JsonValue> =
        sequence {
            yield(v)
            when (v) {
                is JsonArray -> {
                    for (e in v) yieldAll(recurseAll(e))
                }

                is JsonObject -> {
                    for ((_, e) in v) yieldAll(recurseAll(e))
                }

                else -> {}
            }
        }

    private fun allPaths(v: JsonValue): Sequence<List<JsonValue>> = walkPaths(v, emptyList(), leavesOnly = false)

    private fun leafPaths(v: JsonValue): Sequence<List<JsonValue>> = walkPaths(v, emptyList(), leavesOnly = true)

    private fun walkPaths(
        cur: JsonValue,
        path: List<JsonValue>,
        leavesOnly: Boolean,
    ): Sequence<List<JsonValue>> =
        sequence {
            when (cur) {
                is JsonObject -> {
                    if (cur.isEmpty()) {
                        if (path.isNotEmpty()) yield(path)
                    } else {
                        if (!leavesOnly && path.isNotEmpty()) yield(path)
                        for ((k, vv) in cur) yieldAll(walkPaths(vv, path + jsonString(k), leavesOnly))
                    }
                }

                is JsonArray -> {
                    if (cur.isEmpty()) {
                        if (path.isNotEmpty()) yield(path)
                    } else {
                        if (!leavesOnly && path.isNotEmpty()) yield(path)
                        for ((i, vv) in cur.withIndex()) {
                            yieldAll(
                                walkPaths(vv, path + jsonNumber(i.toLong()), leavesOnly),
                            )
                        }
                    }
                }

                else -> {
                    if (path.isNotEmpty()) yield(path)
                }
            }
        }

    // ---- regex helpers ------------------------------------------------------

    private fun matchSeq(
        patternFilter: JqExpr,
        flagsFilter: JqExpr?,
        ctx: JqContext,
        input: JsonValue,
    ): Sequence<JsonValue> =
        sequence {
            val s = input.asStringOrNull() ?: throw JqRuntimeError("match: input must be string")
            for (p in patternFilter.eval(ctx, input)) {
                val pat = p.asStringOrNull() ?: throw JqRuntimeError("match: pattern must be string")
                val rawFlags = flagsFilter?.eval(ctx, input)?.firstOrNull()?.asStringOrNull() ?: ""
                val global = 'g' in rawFlags
                val flags = rawFlags.filter { it != 'g' }
                val re = compileRegex(pat, flags)
                if (global) {
                    for (m in re.findAll(s)) yield(matchToJson(m))
                } else {
                    re.findFirst(s)?.let { yield(matchToJson(it)) }
                }
            }
        }

    private fun matchToJson(m: RegexMatch): JsonValue {
        val captureArr =
            jsonArray(
                m.captures.map { c ->
                    jsonObject(
                        linkedMapOf(
                            "offset" to (if (c.text == null) jsonNumber(-1L) else jsonNumber(c.offset.toLong())),
                            "length" to jsonNumber(c.length.toLong()),
                            "string" to (c.text?.let { jsonString(it) } ?: jsonNull()),
                            "name" to (c.name?.let { jsonString(it) } ?: jsonNull()),
                        ),
                    )
                },
            )
        return jsonObject(
            linkedMapOf(
                "offset" to jsonNumber(m.offset.toLong()),
                "length" to jsonNumber(m.length.toLong()),
                "string" to jsonString(m.text),
                "captures" to captureArr,
            ),
        )
    }

    private fun matchObjToCaptureObj(matchJson: JsonValue): JsonValue {
        val obj = matchJson as? JsonObject ?: return matchJson
        val caps = obj["captures"] as? JsonArray ?: return jsonObject(emptyMap())
        val out = linkedMapOf<String, JsonValue>()
        for (c in caps) {
            val co = c as? JsonObject ?: continue
            val name = co["name"]?.asStringOrNull() ?: continue
            out[name] = co["string"] ?: jsonNull()
        }
        return jsonObject(out)
    }

    private fun subSeq(
        patternFilter: JqExpr,
        replFilter: JqExpr,
        flagsFilter: JqExpr?,
        global: Boolean,
        ctx: JqContext,
        input: JsonValue,
    ): Sequence<JsonValue> =
        sequence {
            val s = input.asStringOrNull() ?: throw JqRuntimeError("sub/gsub: input must be string")
            for (p in patternFilter.eval(ctx, input)) {
                val pat = p.asStringOrNull() ?: throw JqRuntimeError("sub/gsub: pattern must be string")
                val flags = flagsFilter?.eval(ctx, input)?.firstOrNull()?.asStringOrNull() ?: ""
                val re = compileRegex(pat, flags.filter { it != 'g' })
                val matches =
                    if (global) {
                        re.findAll(s).toList()
                    } else {
                        listOfNotNull(re.findFirst(s))
                    }
                if (matches.isEmpty()) {
                    yield(input)
                    continue
                }

                // For each match, expand replacement: bind named captures as $vars and eval the
                // replacement filter. Use the first string output of the filter.
                val out = StringBuilder()
                var cursor = 0
                for (m in matches) {
                    out.append(s, cursor, m.offset)
                    val nctx = bindCaptures(ctx, m.captures)
                    val r =
                        replFilter.eval(nctx, input).firstOrNull()
                            ?: throw JqRuntimeError("sub/gsub: replacement produced no value")
                    out.append(
                        r.asStringOrNull() ?: KashJson
                            .encode(r),
                    )
                    cursor = m.offset + m.length
                }
                if (cursor < s.length) out.append(s, cursor, s.length)
                yield(jsonString(out.toString()))
            }
        }

    private fun bindCaptures(
        ctx: JqContext,
        captures: List<RegexCapture>,
    ): JqContext {
        var c = ctx
        for (cap in captures) {
            val name = cap.name ?: continue
            c = c.withVar(name, cap.text?.let(::jsonString) ?: jsonNull())
        }
        return c
    }

    private fun walkPathsWhere(
        cur: JsonValue,
        path: List<JsonValue>,
        pred: (JsonValue) -> Boolean,
    ): Sequence<JsonValue> =
        sequence {
            if (path.isNotEmpty() && pred(cur)) yield(jsonArray(path))
            when (cur) {
                is JsonArray -> {
                    for ((i, e) in cur.withIndex()) {
                        yieldAll(walkPathsWhere(e, path + jsonNumber(i.toLong()), pred))
                    }
                }

                is JsonObject -> {
                    for ((k, e) in cur) {
                        yieldAll(walkPathsWhere(e, path + jsonString(k), pred))
                    }
                }

                else -> {}
            }
        }

    internal fun jqContains(
        a: JsonValue,
        b: JsonValue,
    ): Boolean {
        if (a.kind() != b.kind()) return false
        return when {
            a is JsonObject && b is JsonObject -> {
                b.all { (k, vv) -> a[k]?.let { jqContains(it, vv) } == true }
            }

            a is JsonArray && b is JsonArray -> {
                b.all { bv -> a.any { av -> jqContains(av, bv) } }
            }

            a.asStringOrNull() != null && b.asStringOrNull() != null -> {
                a.asStringOrNull()!!.contains(b.asStringOrNull()!!)
            }

            else -> {
                jqEquals(a, b)
            }
        }
    }
}
