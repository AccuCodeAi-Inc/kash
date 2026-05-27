package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.parser.isValidAliasName

// Variable-assignment / scope / array machinery extracted from Interpreter.

internal suspend fun Interpreter.applyVarCase(
    name: String,
    value: String,
): String {
    val attrs = varTable.find(name)?.attrs ?: return value
    return when {
        VarAttr.Upper in attrs -> {
            value.uppercase()
        }

        VarAttr.Lower in attrs -> {
            value.lowercase()
        }

        VarAttr.Capitalize in attrs -> {
            if (value.isEmpty()) value else value[0].uppercase() + value.drop(1).lowercase()
        }

        else -> {
            value
        }
    }
}

/**
 * Coerce [rawValue] under integer semantics when [name] has [VarAttr.Integer].
 * For [append], computes `(prior arithmetic value) + (rawValue arithmetic value)`;
 * otherwise just evaluates [rawValue] as an arithmetic expression. Returns the
 * stringified result, or null when the attribute is absent (caller falls back
 * to case-mod / plain-string append).
 */
internal suspend fun Interpreter.applyIntegerAttr(
    name: String,
    rawValue: String,
    priorRaw: String?,
    append: Boolean,
): String? {
    val attrs = varTable.find(name)?.attrs ?: return null
    if (VarAttr.Integer !in attrs) return null
    val rhs = evalArithRaw(rawValue.ifBlank { "0" })
    val total =
        if (append) {
            val prior = priorRaw?.ifBlank { "0" } ?: "0"
            evalArithRaw(prior) + rhs
        } else {
            rhs
        }
    return total.toString()
}

/**
 * Bash special variables whose assignments are silently ignored.
 * `FUNCNAME`/`GROUPS` are dynamic — set by the shell at each call/group lookup,
 * never writable by scripts. The assignment returns success (exit 0) but the
 * value is unchanged.
 */
private val NOASSIGN_VARS = setOf("FUNCNAME", "GROUPS")

/** Scalar assignment, honoring case attributes and array-versus-scalar mode. */
internal suspend fun Interpreter.setScalar(
    name: String,
    rawValue: String,
    append: Boolean,
) {
    if (name in NOASSIGN_VARS) return
    // BASH_XTRACEFD: assignment validates the fd. Bash diagnoses both
    // non-numeric values and fds that aren't currently open & writable
    // with `BASH_XTRACEFD: <value>: invalid value for trace file
    // descriptor`. The assignment still happens (so a later `exec N>...`
    // makes it work), but the diagnostic is emitted at assignment time.
    if (name == "BASH_XTRACEFD" && rawValue.isNotEmpty()) {
        val fd = rawValue.toIntOrNull()
        val invalid =
            fd == null || fd < 0 || process.fdTable[fd] == null
        if (invalid) {
            errSink.writeUtf8(
                "${shellDiagPrefix()}BASH_XTRACEFD: $rawValue: invalid value for trace file descriptor\n",
            )
        }
    }
    val attrs = varTable.find(name)?.attrs
    if (attrs != null && (VarAttr.Indexed in attrs || VarAttr.Associative in attrs)) {
        // `A=foo` on a variable previously declared as an array writes to
        // index 0 (bash's compatibility rule).
        if (VarAttr.Associative in attrs) {
            setAssocElement(name, "0", rawValue, append)
        } else {
            setIndexedElement(name, 0, rawValue, append)
        }
        return
    }
    val intValue = applyIntegerAttr(name, rawValue, env[name], append)
    val finalValue =
        if (intValue != null) {
            intValue
        } else {
            val coerced = applyVarCase(name, rawValue)
            if (append) (env[name] ?: "") + coerced else coerced
        }
    // POSIX §2.14.1: `set -a` (allexport) auto-marks every new
    // assignment for export so child processes see it. Set the Export
    // attribute BEFORE the `env[name] = finalValue` write so the
    // ProcessEnvAdapter mirrors the value into `process.env` — otherwise
    // the adapter's "drop non-exported" rule would suppress it.
    if (allexport) varTable.findOrCreate(name).attrs += VarAttr.Export
    env[name] = finalValue
    // Writing the scalar drops any stale array storage so reads stay coherent.
    indexedArrays.remove(name)
    assocArrays.remove(name)
}

internal suspend fun Interpreter.setIndexedElement(
    name: String,
    index: Int,
    rawValue: String,
    append: Boolean,
) {
    if (name in NOASSIGN_VARS) return
    // bash rejects negative subscripts on indexed-array writes with
    // 'name[N]: bad array subscript' and skips the write. Applies
    // uniformly to direct writes (`a[-2]=v`) and array-literal labels
    // (`d=([-65]=negative ...)`).
    if (index < 0) {
        errSink.writeUtf8("${shellDiagPrefix()}$name[$index]: bad array subscript\n")
        lastExit = 1
        return
    }
    // Bash promotes a prior scalar value to index 0 when the variable
    // first becomes an indexed array — `a=abcde; a[2]=bdef` ends with
    // a=([0]="abcde" [2]="bdef"), not just ([2]="bdef").
    val priorScalar =
        if (name !in indexedArrays && name !in assocArrays) env[name] else null
    val arr = indexedArrays.getOrPut(name) { mutableMapOf() }
    if (priorScalar != null && 0 !in arr) arr[0] = priorScalar
    val prior = arr[index]
    val intValue = applyIntegerAttr(name, rawValue, prior, append)
    val newValue =
        if (intValue != null) {
            intValue
        } else {
            val coerced = applyVarCase(name, rawValue)
            if (append && prior != null) prior + coerced else coerced
        }
    arr[index] = newValue
    env.remove(name)
    varTable.findOrCreate(name).let {
        it.attrs += VarAttr.Indexed
        it.attributeOnly = false
    }

    // bash semantic: assigning to `DIRSTACK[N]` mutates the directory
    // stack itself. Index 0 corresponds to the cwd; index N>=1 maps to
    // dirStack[N-1]. We mirror the assignment back here so subsequent
    // `dirs`/`pushd`/`popd` see the change.
    if (name == "DIRSTACK") {
        when {
            index == 0 -> {
                cwd = newValue
            }

            index - 1 < dirStack.size -> {
                dirStack[index - 1] = newValue
            }

            else -> {
                while (dirStack.size < index - 1) dirStack.add(cwd)
                dirStack.add(newValue)
            }
        }
    }
}

internal suspend fun Interpreter.setAssocElement(
    name: String,
    key: String,
    rawValue: String,
    append: Boolean,
) {
    val arr = assocArrays.getOrPut(name) { linkedMapOf() }
    val prior = arr[key]
    val intValue = applyIntegerAttr(name, rawValue, prior, append)
    arr[key] =
        if (intValue != null) {
            intValue
        } else {
            val coerced = applyVarCase(name, rawValue)
            if (append && prior != null) prior + coerced else coerced
        }
    env.remove(name)
    varTable.findOrCreate(name).let {
        it.attrs += VarAttr.Associative
        it.attributeOnly = false
    }
}

/**
 * Store [value] into the element `name[sub]` of a shell variable, deciding
 * indexed vs associative storage the same way `read NAME[sub]` does. Backs
 * the [com.accucodeai.kash.api.CommandContext.setArrayElement] callback so
 * out-of-module builtins (`printf -v NAME[sub]`) can write array elements.
 * The subscript text is already expanded by the caller; for indexed arrays
 * it's arith-evaluated (bad arith falls back to index 0, matching read's
 * lenient behavior).
 */
internal suspend fun Interpreter.setBuiltinArrayElementTarget(
    name: String,
    sub: String,
    value: String,
) {
    if (varTable.find(name)?.isAssoc == true) {
        setAssocElement(name, sub, value, append = false)
    } else {
        val idx =
            sub.toIntOrNull() ?: try {
                evalArithRaw(sub.ifBlank { "0" }).toInt()
            } catch (_: Throwable) {
                0
            }
        setIndexedElement(name, idx, value, append = false)
    }
}

/** `NAME=(a b c)` form — replaces existing storage (or appends starting at end+1 when `+=`). */
internal suspend fun Interpreter.setIndexedArray(
    name: String,
    rawElements: List<String>,
    append: Boolean,
) {
    val coerced = rawElements.map { applyVarCase(name, it) }
    val arr =
        if (append) {
            indexedArrays.getOrPut(name) { mutableMapOf() }
        } else {
            mutableMapOf<Int, String>().also { indexedArrays[name] = it }
        }
    val startIndex = if (append && arr.isNotEmpty()) (arr.keys.max() + 1) else 0
    for ((i, v) in coerced.withIndex()) {
        arr[startIndex + i] = v
    }
    env.remove(name)
    varTable.findOrCreate(name).attrs += VarAttr.Indexed
}

/**
 * Per-element provenance: `Label` is a literally-typed `[K]=V` form
 * from the array literal source; `Value` is a plain word (possibly
 * the result of glob expansion or `$var` splitting). Bash recognizes
 * `[K]=V` label syntax ONLY when typed literally — a glob that
 * happens to produce `[3]=abcde` is NOT re-parsed as a label.
 */
internal sealed class ArrayLiteralElem {
    data class Label(
        val raw: String,
    ) : ArrayLiteralElem()

    data class Value(
        val text: String,
    ) : ArrayLiteralElem()
}

internal suspend fun Interpreter.setIndexedArrayLiteral(
    name: String,
    rawElements: List<ArrayLiteralElem>,
    append: Boolean,
) {
    // Append-onto-prior-scalar promotes the scalar to index 0 (bash:
    // `s=foo; s+=(x)` → s=([0]="foo" [1]="x")). Plain `s=(x)` replaces.
    val priorScalar =
        if (append && name !in indexedArrays && name !in assocArrays) env[name] else null
    if (!append) {
        indexedArrays.remove(name)
    }
    val arr = indexedArrays.getOrPut(name) { mutableMapOf() }
    if (priorScalar != null && 0 !in arr) arr[0] = priorScalar
    varTable.findOrCreate(name).let {
        it.attrs += VarAttr.Indexed
        it.attributeOnly = false
    }
    env.remove(name)
    var next = if (arr.isNotEmpty()) arr.keys.max() + 1 else 0
    for (elem in rawElements) {
        val raw =
            when (elem) {
                is ArrayLiteralElem.Label -> elem.raw
                is ArrayLiteralElem.Value -> elem.text
            }
        // Only literally-typed `[K]=V` syntax is recognized as a label.
        // Glob-expanded words shaped like `[3]=abcde` are stored as-is.
        val parsed =
            if (elem is ArrayLiteralElem.Label) parseIndexedElement(raw) else null
        if (parsed == null) {
            setIndexedElement(name, next, raw, append = false)
            next++
        } else {
            val (idxText, value, elemAppend) = parsed
            // bash rejects bracketed subscripts in indexed-array literals:
            //   - `[]=v`     → "[]=v: bad array subscript"
            //   - `[*]=v`    → "[*]=v: cannot assign to non-numeric index"
            //   - `[-65]=v`  → "[-65]=v: bad array subscript" (negative)
            // The error string echoes the full element (subscript + `=` +
            // value), not just the subscript.
            // bash stops processing the array-literal on the first
            // bad-element error, leaving whatever was already written
            // in place — that's why `d=([1]=ok [bad]=...)` ends with
            // d=([1]=ok) and not the bad-element diagnostic line N
            // suppressing the [1] write.
            if (idxText.isBlank()) {
                errSink.writeUtf8("${shellDiagPrefix()}$raw: bad array subscript\n")
                lastExit = 1
                break
            }
            if (idxText == "*" || idxText == "@") {
                errSink.writeUtf8("${shellDiagPrefix()}$raw: cannot assign to non-numeric index\n")
                lastExit = 1
                break
            }
            // array_expand_once security baseline: do not re-evaluate
            // a `\$(...)` substring in an array-literal subscript label.
            // `a=( [\$subscript]=hi )` with $subscript a cmdsub-bearing
            // literal must NOT run the inner command.
            if ("\$(" in idxText) {
                errSink.writeUtf8(
                    "${shellDiagPrefix()}$idxText: arithmetic syntax error: operand expected (error token is \"$idxText\")\n",
                )
                lastExit = 1
                break
            }
            val rawIdx =
                try {
                    evalArithRaw(idxText).toInt()
                } catch (_: Throwable) {
                    errSink.writeUtf8("${shellDiagPrefix()}$raw: bad array subscript\n")
                    lastExit = 1
                    break
                }
            // Bash: `arr+=( [-N]=v )` (and `arr=( [-N]=v )` when the
            // array already has content) translates against the current
            // max key; only flag bad when translation drops below zero.
            val idx =
                if (rawIdx < 0) {
                    val maxKey = arr.keys.maxOrNull()
                    val t = if (maxKey != null) maxKey + 1 + rawIdx else rawIdx
                    if (t < 0) {
                        errSink.writeUtf8("${shellDiagPrefix()}$raw: bad array subscript\n")
                        lastExit = 1
                        break
                    }
                    t
                } else {
                    rawIdx
                }
            setIndexedElement(name, idx, value, append = elemAppend)
            next = idx + 1
        }
    }
}

/**
 * Returns `(subscript, value, append)` when [raw] starts with a bracketed
 * label like `[N]=v` / `[N]+=v`, else null. Subscript text is returned
 * unevaluated — caller decides if it's integer-arith or a string key.
 */
internal suspend fun Interpreter.parseIndexedElement(raw: String): Triple<String, String, Boolean>? {
    if (!raw.startsWith("[")) return null
    val close = raw.indexOf(']')
    if (close <= 0 || close + 1 >= raw.length) return null
    val after = raw[close + 1]
    return when {
        after == '=' -> {
            Triple(raw.substring(1, close), raw.substring(close + 2), false)
        }

        after == '+' && close + 2 < raw.length && raw[close + 2] == '=' -> {
            Triple(raw.substring(1, close), raw.substring(close + 3), true)
        }

        else -> {
            null
        }
    }
}

/**
 * Execute an assignment that's NOT a prefix to a command (`A=foo` standalone,
 * `A=(a b c)`, `AA[k]=v`). Honors case attributes and array vs scalar state.
 *
 * If the target is in [Interpreter.readonlyVars] the assignment is a no-op,
 * a bash-style diagnostic goes to stderr, and `$?` is set to 1 — matches
 * bash, where readonly-violation reports the error but doesn't abort
 * non-special-builtin execution.
 */
internal suspend fun Interpreter.applyBareAssignment(a: InlineAssignment) {
    // Readonly diagnostic fires AFTER subscript validation for the
    // Indexed case — bash reports `name[bad-sub]: bad array subscript`
    // for `c[-2]=4` even when c is readonly. Scalar / Array always
    // emit `name: readonly variable` upfront.
    if (a !is InlineAssignment.Indexed && varTable.find(a.name)?.isReadonly == true) {
        errSink.writeUtf8("${shellDiagPrefix()}${a.name}: readonly variable\n")
        lastExit = 1
        return
    }
    when (a) {
        is InlineAssignment.Scalar -> {
            val v = expandAssignmentValue(a.value)
            setScalar(a.name, v, a.append)
        }

        is InlineAssignment.Array -> {
            // bash: unquoted spread inside `(...)` word-splits — so
            // `arrayB=(${arrayA[*]})` produces multiple elements, not
            // one joined string. Each element-word goes through full
            // arg expansion (word-splitting + glob suppression for
            // assignment context) so the spread fans out properly.
            // Labelled elements `[k]=v` go through scalar-style
            // expansion to keep their literal `[k]=` prefix intact.
            val braceOn = shoptOptions["braceexpand"] != false
            // Assoc-array compound assignment SUPPRESSES word splitting on
            // each element — `declare -A v=( $foo $bar )` with foo='1 2'
            // and bar='4 5' produces `v=(["1 2"]="4 5")`, ONE pair. Verified
            // bash 5.3. For indexed arrays the elements split normally.
            val isAssocCompound = varTable.find(a.name)?.isAssoc == true
            // array_expand_once security baseline: when a `[label]=value`
            // element's bracketed text contains a CommandSubstitution
            // chunk, the cmdsub must NOT be re-executed during the
            // array-literal apply step (the user data already survived
            // one expansion pass to get here). Track such elements and
            // refuse the entire compound assignment with an arith error.
            var anyCmdsubInLabel = false
            val raws =
                a.elements.flatMap { word ->
                    val firstPart = word.parts.firstOrNull()
                    val startsLabel =
                        firstPart is com.accucodeai.kash.ast.WordPart.Literal &&
                            firstPart.value.startsWith("[")
                    if (startsLabel) {
                        // Scan for cmdsub inside the bracketed subscript
                        // portion (between `[` and the first unquoted `]`).
                        // If found, mark and emit the bash-shape arith
                        // error; don't expand this element.
                        var sawCmdsubBeforeClose = false
                        var pastClose = false
                        for (p in word.parts) {
                            if (pastClose) continue
                            if (p is com.accucodeai.kash.ast.WordPart.Literal) {
                                if (']' in p.value) pastClose = true
                            } else if (p is com.accucodeai.kash.ast.WordPart.CommandSubstitution) {
                                sawCmdsubBeforeClose = true
                            }
                        }
                        if (sawCmdsubBeforeClose) {
                            anyCmdsubInLabel = true
                            // Render a representative subscript text for
                            // the diagnostic — reconstruct from the source
                            // parts up to the first `]`.
                            val sb = StringBuilder()
                            var seenClose = false
                            for (p in word.parts) {
                                if (seenClose) break
                                when (p) {
                                    is com.accucodeai.kash.ast.WordPart.Literal -> {
                                        val v = p.value
                                        val cb = v.indexOf(']')
                                        sb.append(if (cb < 0) v else v.substring(0, cb))
                                        if (cb >= 0) seenClose = true
                                    }

                                    is com.accucodeai.kash.ast.WordPart.CommandSubstitution -> {
                                        sb
                                            .append('$')
                                            .append('(')
                                            .append(p.rawText)
                                            .append(')')
                                    }

                                    else -> { /* skip */ }
                                }
                            }
                            val subText = sb.toString().removePrefix("[")
                            errSink.writeUtf8(
                                "${shellDiagPrefix()}$subText: arithmetic syntax error: operand expected (error token is \"$subText\")\n",
                            )
                            emptyList()
                        } else {
                            listOf(ArrayLiteralElem.Label(expandAssignmentValue(word)))
                        }
                    } else {
                        // Array literal elements undergo brace expansion the
                        // same way command-line words do — `days=({Mon,Tues}day)`
                        // yields two elements, not one.
                        // For INDEXED arrays, elements expand exactly like
                        // command-line words: word-split AND pathname-expanded
                        // (`x=(*)` globs the cwd, `x=($foo)` splits) — so use
                        // the full `expandArg`.
                        // For ASSOC arrays, bash suppresses BOTH word splitting
                        // and globbing — `$foo` with foo='1 2' arrives as ONE
                        // element and `dict=(* star)` keeps the literal `*` key
                        // — so use scalar-style `expandSingle` there.
                        val brace = if (braceOn) expandBraces(word, enabled = true) else listOf(word)
                        if (isAssocCompound) {
                            brace.map { bw -> ArrayLiteralElem.Value(expandSingle(bw)) }
                        } else {
                            brace.flatMap { bw -> expandArg(bw).map { ArrayLiteralElem.Value(it) } }
                        }
                    }
                }
            if (anyCmdsubInLabel) {
                // Reject the whole compound assignment — bash leaves the
                // array empty (or unchanged on append).
                lastExit = 1
                if (!a.append) {
                    indexedArrays.remove(a.name)
                    varTable.findOrCreate(a.name).let {
                        it.attrs += VarAttr.Indexed
                        it.attributeOnly = false
                    }
                }
                return
            }
            val attrs = varTable.find(a.name)?.attrs
            if (attrs != null && VarAttr.Associative in attrs) {
                // `assoc=( ... )` — bash dispatches on the FIRST element:
                //   * first is `[k]=v` (Label): label-mode. Every element
                //     must be a label. The first positional emits
                //         NAME: VAL: must use subscript when assigning
                //                    associative array
                //     sets exit 1, and ABORTS the rest of the compound.
                //   * first is bare (Value): positional-pair mode. Treat
                //     all elements as positionals — including any literal
                //     `[k]=v` text, which becomes a key (not a label).
                //     Pairs (k,v); a trailing unpaired key gets `""`.
                // Verified against bash 5.3 directly.
                if (!a.append) varTable.find(a.name)?.assocOrNull?.clear()
                if (raws.isNotEmpty() && raws[0] is ArrayLiteralElem.Label) {
                    for ((idx, elem) in raws.withIndex()) {
                        when (elem) {
                            is ArrayLiteralElem.Label -> {
                                val (k, v, elemAppend) = parseAssocElement(elem.raw, fallbackIndex = idx)
                                // Only the ELEMENT's own `[k]+=v` appends to the
                                // existing a[k]. The whole-compound `a+=( ... )`
                                // append is realized by NOT clearing the array
                                // (see above) — it must NOT turn each `[k]=v`
                                // element into an append. So `v1+=( [k]='new' )`
                                // REPLACES a[k] (bash 5.3, assoc12.sub), it does
                                // not concatenate onto the prior value.
                                setAssocElement(a.name, k, v, append = elemAppend)
                            }

                            is ArrayLiteralElem.Value -> {
                                errSink.writeUtf8(
                                    "${shellDiagPrefix()}${a.name}: ${elem.text}: must use subscript when assigning associative array\n",
                                )
                                lastExit = 1
                                break
                            }
                        }
                    }
                } else {
                    var i = 0
                    while (i < raws.size) {
                        val elem = raws[i]
                        val k =
                            when (elem) {
                                is ArrayLiteralElem.Value -> elem.text

                                // First element wasn't a Label, so a later
                                // `[k]=v`-shaped token is treated by bash as
                                // a literal key. Use its raw text verbatim.
                                is ArrayLiteralElem.Label -> elem.raw
                            }
                        // Bash: an empty key in positional-pair assoc
                        // compound errors `"": bad array subscript` and
                        // skips that pair (no write). Verified bash 5.3
                        // (assoc11.sub line 34).
                        if (k.isEmpty()) {
                            errSink.writeUtf8(
                                "${shellDiagPrefix()}\"\": bad array subscript\n",
                            )
                            lastExit = 1
                            i += 2
                            continue
                        }
                        val nextElem = raws.getOrNull(i + 1)
                        val v =
                            when (nextElem) {
                                is ArrayLiteralElem.Value -> nextElem.text
                                is ArrayLiteralElem.Label -> nextElem.raw
                                null -> ""
                            }
                        // Positional pairs are plain key=value writes (replace),
                        // even under a whole-compound `+=` — the compound append
                        // only suppresses the array clear, it doesn't make each
                        // pair an element-level append.
                        setAssocElement(a.name, k, v, append = false)
                        i += 2
                    }
                }
            } else {
                setIndexedArrayLiteral(a.name, raws, a.append)
            }
        }

        is InlineAssignment.Indexed -> {
            val sub = expandAssignmentValue(a.subscript)
            val v = expandAssignmentValue(a.value)
            // array_expand_once security baseline: refuse to re-evaluate
            // a subscript text that contains literal `$(...)`. Without
            // this, `a[\$subscript]=hi` (with $subscript holding the
            // literal string `\$(echo INJECTION...)`) would arith-evaluate
            // the subscript and execute the embedded cmdsub.
            //
            // INDEXED arrays only: their subscript is arithmetic, so a
            // surviving `$(...)` is the injection vector. An ASSOCIATIVE
            // subscript is a literal string key — never evaluated, never
            // executed — so bash stores it verbatim (`x='$(date >&2)';
            // a[$x]=5` keys on the literal text). Skip the rejection there.
            if (varTable.find(a.name)?.isAssoc != true && "\$(" in sub) {
                errSink.writeUtf8(
                    "${shellDiagPrefix()}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                )
                lastExit = 1
                return
            }
            // `NAME[sub]=(...)` parses OK but is a runtime error — bash:
            // "NAME[sub]: cannot assign list to array member". The lexer
            // marks the assignment with [listAttempt] so we fire the
            // diagnostic here instead of polluting the parse path with
            // a syntax error.
            if (a.listAttempt) {
                errSink.writeUtf8("${shellDiagPrefix()}${a.name}[$sub]: cannot assign list to array member\n")
                lastExit = 1
                return
            }
            // Literal `a[]=v` (empty brackets, no quotes) is a bash bad
            // subscript error. Distinguish from `a[""]=v` (arith-evaluates
            // "" → 0) by looking at the AST: parts.isEmpty() means literal
            // empty brackets, parts has the quoted-empty content otherwise.
            if (a.subscript.parts.isEmpty()) {
                errSink.writeUtf8("${shellDiagPrefix()}${a.name}[]: bad array subscript\n")
                lastExit = 1
                return
            }
            // bash: `BASH_ALIASES[name]=value` writes the alias table.
            if (a.name == "BASH_ALIASES") {
                writeBashAlias(sub, v)
                return
            }
            val attrs = varTable.find(a.name)?.attrs
            if (attrs != null && VarAttr.Associative in attrs) {
                // Bash 5.3 accepts `assoc[*]=v` / `assoc[@]=v` as
                // assignments with the literal string key `*` or `@`
                // (NOT the wildcard-expand sentinel that those keys
                // mean in *reads*). assoc.tests line 53 — comment
                // "TEST - no longer errors" — covers this. Verified
                // against bash 5.3 directly.
                if (varTable.find(a.name)?.isReadonly == true) {
                    errSink.writeUtf8("${shellDiagPrefix()}${a.name}: readonly variable\n")
                    lastExit = 1
                    return
                }
                setAssocElement(a.name, sub, v, a.append)
            } else {
                // bash subscript validation for indexed arrays:
                //   - `*` / `@`       → `name[*]: bad array subscript`
                //   - negative result → `name[N]: bad array subscript`
                // (Empty subscript `a[""]` evaluates as arith → 0 in bash,
                // matching kash's prior fallback; only the literal `a[]`
                // path errors, and that fails earlier as "command not found"
                // because the lexer rejects empty brackets as an assignment.)
                if (sub == "*" || sub == "@") {
                    errSink.writeUtf8("${shellDiagPrefix()}${a.name}[$sub]: bad array subscript\n")
                    lastExit = 1
                    return
                }
                // (Readonly check moved to after subscript validation below.)
                // bash: indexed-array subscripts are arithmetic expressions —
                // `a[7+8]=x`, `a[i+1]=x` all evaluate the bracketed text as
                // math, not just string-to-int. Try ArithEval; on parse
                // failure leave kash's prior silent-fallback-to-0 behavior
                // alone so we don't double-emit diagnostics that some
                // callers (notably `a[b[c]d]=e` in arith.tests) already
                // produce upstream.
                val rawIdx =
                    sub.toIntOrNull() ?: try {
                        ArithEval(sub.ifBlank { "0" }, env, arithStore, nounset = nounset).evaluate().toInt()
                    } catch (_: Throwable) {
                        0
                    }
                // Bash: a negative write subscript translates against the
                // current max key (`max + 1 + idx`); only flag bad when
                // the translation drops below zero. Without an existing
                // array, no translation is possible — any negative idx
                // is bad.
                val idx =
                    if (rawIdx < 0) {
                        val existing = indexedArrays[a.name]
                        val maxKey = existing?.keys?.maxOrNull()
                        val translated = if (maxKey != null) maxKey + 1 + rawIdx else rawIdx
                        if (translated < 0) {
                            errSink.writeUtf8("${shellDiagPrefix()}${a.name}[$rawIdx]: bad array subscript\n")
                            lastExit = 1
                            return
                        }
                        translated
                    } else {
                        rawIdx
                    }
                // Readonly check deferred to here — bash reports
                // subscript errors first.
                if (varTable.find(a.name)?.isReadonly == true) {
                    errSink.writeUtf8("${shellDiagPrefix()}${a.name}: readonly variable\n")
                    lastExit = 1
                    return
                }
                setIndexedElement(a.name, idx, v, a.append)
            }
        }
    }
}

/**
 * Parse one element of an associative-array literal. A `[key]=value` pair
 * splits into the subscript and the assigned value; anything else is taken
 * as a plain value and gets [fallbackIndex] as its key (matching the bash
 * fallback when an unlabelled element appears in an assoc literal).
 */
internal suspend fun Interpreter.parseAssocElement(
    raw: String,
    fallbackIndex: Int,
): Triple<String, String, Boolean> {
    if (!raw.startsWith("[")) return Triple(fallbackIndex.toString(), raw, false)
    // Find the LAST `]=` — bash matches the bracket-close that's
    // immediately followed by `=`, so keys containing literal `]`
    // (e.g. quoted `["version[agent]"]=v` → post-quote-removal
    // `[version[agent]]=v`) parse with the right boundary. Also
    // detect the `]+=` append form so `assoc+=([k]+=v)` appends v
    // to the existing a[k] instead of writing the literal `+=v`.
    val closePlus = raw.lastIndexOf("]+=")
    if (closePlus > 0) {
        return Triple(raw.substring(1, closePlus), raw.substring(closePlus + 3), true)
    }
    val closeEq = raw.lastIndexOf("]=")
    if (closeEq > 0) {
        return Triple(raw.substring(1, closeEq), raw.substring(closeEq + 2), false)
    }
    return Triple(fallbackIndex.toString(), raw, false)
}

/**
 * Bash's `BASH_ALIASES['name']=value` writes the runtime alias table.
 * Invalid names produce bash-format diagnostic on stderr and *don't*
 * touch the table — matching `alias.right` line 67's expected output.
 */
internal suspend fun Interpreter.writeBashAlias(
    name: String,
    value: String,
) {
    if (!isValidAliasName(name)) {
        errSink.writeUtf8("${shellDiagPrefix()}`$name': invalid alias name\n")
        return
    }
    aliases[name] = value
    aliasVersion++
    syncBashAliases()
}

/** Mark a variable as declared with the given attributes (no value change). */
internal suspend fun Interpreter.declareAttrs(
    name: String,
    attrs: Set<VarAttr>,
) {
    val tableEntry = varTable.findOrCreate(name)
    val newlyInteger = VarAttr.Integer in attrs && VarAttr.Integer !in tableEntry.attrs
    val newlyExported = VarAttr.Export in attrs && VarAttr.Export !in tableEntry.attrs
    // Bash refuses to convert between indexed and associative arrays —
    // skip the conflicting attribute so the existing storage stays
    // intact. (The caller is responsible for emitting the appropriate
    // "cannot convert" diagnostic; here we just protect the data.)
    val wantIndexed = VarAttr.Indexed in attrs
    val wantAssoc = VarAttr.Associative in attrs
    val isAssoc = tableEntry.isAssoc
    val isIndexed = tableEntry.isIndexed
    val conflictAssocToIndexed = wantIndexed && isAssoc
    val conflictIndexedToAssoc = wantAssoc && isIndexed
    // Readonly is added separately AFTER applyBareAssignment runs; don't
    // propagate it from the attr parameter here or the readonly check
    // would fire on the very write we're about to do.
    for (a in attrs) {
        if (a == VarAttr.Readonly) continue
        if (a == VarAttr.Indexed && conflictAssocToIndexed) continue
        if (a == VarAttr.Associative && conflictIndexedToAssoc) continue
        tableEntry.attrs += a
    }
    // `export NAME` (no value) on an already-set scalar promotes its
    // current value into `process.env` so child processes and
    // in-process builtins like `printenv` see it. Without this the
    // value remains shell-local — the ProcessEnvAdapter only mirrors
    // names that were exported when the value was written.
    if (newlyExported && tableEntry.isScalar) {
        tableEntry.scalarOrNull?.let { process.env[name] = it }
    }
    // Mark associative variables with an empty assoc map so later reads see
    // them as set arrays; same for explicit `-a` declarations. Bash also
    // promotes a prior scalar to index 0 when `declare -a` is applied to
    // an already-set variable (`a=abcde; declare -a a` → a[0]=abcde).
    // Note: `Variable.value != Unset` already discriminates assigned-empty
    // from just-declared, so we don't need a separate flag.
    if (VarAttr.Associative in attrs && name !in assocArrays) {
        val priorScalar = env[name]
        assocArrays[name] =
            linkedMapOf<String, String>().also {
                // Bash promotes a prior scalar to assoc key "0"
                // (`x=foo; declare -A x` → x[0]="foo"). Matches the
                // analogous `declare -a` promotion below. Verified
                // bash 5.3.
                if (priorScalar != null) it["0"] = priorScalar
            }
        env.remove(name)
        // No prior scalar means truly attribute-only; with a promoted
        // scalar the array is considered set.
        if (priorScalar == null) tableEntry.attributeOnly = true
    } else if (VarAttr.Indexed in attrs && name !in indexedArrays) {
        val priorScalar = env[name]
        indexedArrays[name] =
            mutableMapOf<Int, String>().also {
                if (priorScalar != null) it[0] = priorScalar
            }
        env.remove(name)
        // No prior scalar means truly attribute-only; with a promoted
        // scalar (`a=foo; declare -a a`) the array is considered set.
        if (priorScalar == null) tableEntry.attributeOnly = true
    }
    // Adding the integer attribute to an already-set var reinterprets the
    // current value as an arithmetic expression (`b=4+1; typeset -i b` → 5).
    if (newlyInteger) {
        env[name]?.let { env[name] = evalArithRaw(it.ifBlank { "0" }).toString() }
        varTable.find(name)?.indexedOrNull?.let { arr ->
            for ((k, v) in arr.toMap()) arr[k] = evalArithRaw(v.ifBlank { "0" }).toString()
        }
        varTable.find(name)?.assocOrNull?.let { arr ->
            for ((k, v) in arr.toMap()) arr[k] = evalArithRaw(v.ifBlank { "0" }).toString()
        }
    }
}
