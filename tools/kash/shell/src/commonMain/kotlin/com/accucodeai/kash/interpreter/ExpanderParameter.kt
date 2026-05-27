package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.interpreter.Expander.ParameterError

// Parameter expansion ($var, ${var:-default}, arrays, spreads) extracted from Expander.

/** Default-value-family ops that explicitly test for "unset" — bypass `set -u`. */
private val NOUNSET_EXEMPT_OPS = setOf("-", ":-", "+", ":+", "=", ":=", "?", ":?")

/**
 * Special parameters that are always considered "set" under `set -u`.
 * `$!` is intentionally NOT here: it's unset until the first background
 * command runs, and bash with `set -u` errors on a bare `$!` reference
 * before any `&` has run (bash man page §Special Parameters).
 */
private val NOUNSET_EXEMPT_SPECIALS = setOf("@", "*", "#", "?", "$", "-", "0")

/** Order an assoc's keys per bash semantics. The internal-table-backed
 *  arrays use their backing table's bucket count: `BASH_CMDS` the 256-bucket
 *  command hash, `BASH_ALIASES` the 64-bucket alias table. Generic
 *  `declare -A` arrays use the 1024-bucket [BashAssocOrder.order]. */
internal fun orderAssocKeys(
    name: String,
    keys: Iterable<String>,
): List<String> =
    when (name) {
        "BASH_CMDS" -> BashAssocOrder.commandHashOrder(keys)
        "BASH_ALIASES" -> BashAssocOrder.aliasHashOrder(keys)
        else -> BashAssocOrder.order(keys)
    }

/**
 * True when [name] is a permissible target of indirect expansion `${!v}`.
 * Accepts plain identifiers, positional/special params, and `name[subscript]`
 * forms (subscript is re-evaluated downstream).
 */
private fun isValidIndirectTarget(name: String): Boolean {
    if (name.isEmpty()) return false
    if (name in setOf("@", "*", "#", "?", "$", "-", "!", "0")) return true
    if (name.all { it.isDigit() }) return true
    val bracket = name.indexOf('[')
    val base = if (bracket >= 0 && name.endsWith(']')) name.substring(0, bracket) else name
    return isAssignableName(base)
}

/**
 * True when [name] is a valid POSIX shell variable name — letter/underscore
 * followed by letter/digit/underscore. Positional (`6`) and special params
 * (`@`, `*`, `#`, `?`, etc.) are NOT assignable; bash rejects them as
 * targets of `${name=val}` / `${name:=val}` with "cannot assign in this way".
 */
private fun isAssignableName(name: String): Boolean {
    if (name.isEmpty()) return false
    val first = name[0]
    if (!(first == '_' || first.isLetter())) return false
    for (i in 1 until name.length) {
        val c = name[i]
        if (!(c == '_' || c.isLetterOrDigit())) return false
    }
    return true
}

/**
 * POSIX `$*` / `${A[*]}` join separator. Three-way rule:
 *   - IFS unset       → " " (single space)
 *   - IFS empty       → "" (no separator)
 *   - otherwise       → IFS[0]
 */
internal fun Expander.firstIfsChar(): String =
    when {
        !env.containsKey("IFS") -> " "
        env["IFS"]!!.isEmpty() -> ""
        else -> env["IFS"]!!.first().toString()
    }

internal fun Expander.appendPart(
    groups: MutableList<MutableList<Fragment>>,
    part: WordPart,
    quoted: Boolean,
) {
    when (part) {
        is WordPart.Literal -> {
            groups.last() += Fragment(part.value, quoted, splittable = false, sourceLiteral = true)
        }

        is WordPart.SingleQuoted -> {
            groups.last() += Fragment(part.value, quoted = true, splittable = false, sourceLiteral = true)
        }

        is WordPart.Escaped -> {
            groups.last() += Fragment(part.value, quoted = true, splittable = false, sourceLiteral = true)
        }

        is WordPart.DoubleQuoted -> {
            // Empty `""` still produces an empty quoted token — needed for `[ -n "" ]` etc.
            if (part.parts.isEmpty()) {
                groups.last() += Fragment("", quoted = true, splittable = false, sourceLiteral = true)
            } else {
                for (p in part.parts) appendPart(groups, p, quoted = true)
            }
        }

        is WordPart.ParameterExpansion -> {
            appendParameterExpansion(groups, part, quoted)
        }

        // Pre-pass result of $(...) and $((...)). Carries split-eligibility:
        // POSIX §2.6.5 splits expansion results (when unquoted), but not
        // source literals — that's why this is a separate WordPart variant.
        is WordPart.ExpandedText -> {
            groups.last() += Fragment(part.value, quoted, splittable = !quoted)
        }

        // Command substitution / arithmetic are resolved into ExpandedText by
        // the interpreter's pre-pass before words reach the Expander.
        is WordPart.CommandSubstitution -> {
            Unit
        }

        is WordPart.ArithmeticExpansion -> {
            Unit
        }

        // Resolved into ExpandedText by Interpreter.preprocessPart before words
        // reach the Expander. A bare ProcessSubstitution here means the
        // preprocessor was bypassed — treat as no-op (matches the
        // CommandSubstitution arm above).
        is WordPart.ProcessSubstitution -> {
            Unit
        }
    }
}

internal fun Expander.appendParameterExpansion(
    groups: MutableList<MutableList<Fragment>>,
    inputPart: WordPart.ParameterExpansion,
    quoted: Boolean,
) {
    // Deferred operand re-lex for default-value ops. The raw text was
    // captured at outer-parse time by the lexer's
    // [Lexer.extractBracedOperandTextRaw], with the outer dq depth
    // recorded in [outerDqAtLex]. Re-lexing here with the outer dq
    // depth restored is what makes `"${IFS+\}}"` strip the `\` (in
    // dq operand) while `"\}"` keeps it (regular dq) — bash's
    // `default-value RHS expander` does the same.
    // A ParseException from the re-lex (e.g. unbalanced `$(`) is
    // surfaced as a runtime "bad substitution" rather than a fatal
    // parse error, matching the existing __BADSUB__ recovery
    // convention.
    val rawPart: WordPart.ParameterExpansion =
        if (inputPart.rawArg1 != null && inputPart.arg1 == null) {
            // First try the strict re-lex (the bash 5.3 / POSIX-mode
            // behavior, where `${…}` operand parsing recognizes `$(`
            // openers literally even inside what look like sq regions).
            // If that throws — e.g. operand contains an unbalanced
            // `$(` like `${a-'$('\'}` — fall back to the lenient `0`
            // dq depth re-lex that treats `'` as a quote opener. The
            // lenient parse mirrors the bash-3.2 era behavior; the
            // strict-fail-then-lenient ordering lets us match both
            // dialects.
            val isBareDefaultOp = inputPart.op in setOf("-", "+", "=", "?")
            val chunks =
                try {
                    com.accucodeai.kash.parser.Lexer.lexDeferredOperand(
                        inputPart.rawArg1,
                        inputPart.outerDqAtLex,
                        inDefaultValueOp = isBareDefaultOp,
                    )
                } catch (_: com.accucodeai.kash.parser.ParseException) {
                    // Fallback: lenient re-lex with the outer dq depth
                    // preserved but `defaultValueOp = false`. This is
                    // the bash-3.2-quirky behavior (sq opens a quote
                    // region, `\X` keeps `\` for non-dq-special X) that
                    // the older `braces.tests` fixtures expect.
                    try {
                        com.accucodeai.kash.parser.Lexer.lexDeferredOperand(
                            inputPart.rawArg1,
                            inputPart.outerDqAtLex,
                            defaultValueOp = false,
                        )
                    } catch (_: Exception) {
                        throw com.accucodeai.kash.parser.ParseException(
                            "\${${inputPart.parameter}}: bad substitution",
                        )
                    }
                } catch (_: IllegalStateException) {
                    try {
                        com.accucodeai.kash.parser.Lexer.lexDeferredOperand(
                            inputPart.rawArg1,
                            inputPart.outerDqAtLex,
                            defaultValueOp = false,
                        )
                    } catch (_: Exception) {
                        throw com.accucodeai.kash.parser.ParseException(
                            "\${${inputPart.parameter}}: bad substitution",
                        )
                    }
                }
            val parts =
                chunks.map {
                    com.accucodeai.kash.parser
                        .chunkToPart(it)
                }
            inputPart.copy(arg1 = Word(parts), rawArg1 = null)
        } else {
            inputPart
        }
    // Lexer sentinel for an unrecognized `${name<X>...}` operator that
    // bash treats as a runtime "bad substitution" rather than a parse
    // error. Throw ParseException so the InterpreterExpand layer
    // emits the diagnostic and the script continues at the next
    // command boundary.
    if (rawPart.op == "__BADSUB__") {
        val prefix =
            when {
                rawPart.indirect -> "!"
                rawPart.lengthOf -> "#"
                else -> ""
            }
        throw com.accucodeai.kash.parser.ParseException(
            "\${$prefix${rawPart.parameter}}: bad substitution",
        )
    }
    // `${prefix*}` / `${prefix@}` and the `!`-prefixed variants — list every variable
    // name starting with `parameter`. `${#prefix@}` returns the count.
    if (rawPart.nameGlob != null) {
        // bash: indirect-prefix `${!N*}` / `${!@*}` etc. requires the prefix
        // to itself be a valid variable name (or empty). `${!1*}` and
        // `${!@*}` are rejected as bad substitution because positional /
        // special-param names can't carry a prefix-glob meaning.
        if (rawPart.indirect && rawPart.parameter.isNotEmpty() &&
            !isAssignableName(rawPart.parameter.takeWhile { it != ' ' })
        ) {
            throw com.accucodeai.kash.parser.ParseException(
                "\${!${rawPart.parameter}${rawPart.nameGlob}}: bad substitution",
            )
        }
        val matched = env.keys.filter { it.startsWith(rawPart.parameter) }.sorted()
        if (rawPart.lengthOf) {
            groups.last() += Fragment(matched.size.toString(), quoted = quoted, splittable = !quoted)
            return
        }
        if (rawPart.nameGlob == '@' && quoted) {
            if (matched.isNotEmpty()) {
                groups.last() += Fragment(matched.first(), quoted = true, splittable = false)
                for (idx in 1 until matched.size) {
                    groups += mutableListOf(Fragment(matched[idx], quoted = true, splittable = false))
                }
            }
        } else {
            val sep = firstIfsChar()
            groups.last() += Fragment(matched.joinToString(sep), quoted = quoted, splittable = !quoted)
        }
        return
    }
    // `${!name[@]}` / `${!name[*]}` — list the keys/indices of array `name`,
    // NOT an indirect expansion. Indexed-array keys come back as numeric
    // ascending strings; assoc-array keys come back in insertion order
    // (which is how kash's LinkedHashMap-backed assoc storage already
    // iterates them).
    //
    // Bash disambiguates `${!name[@]}` (keys) from `${!name[@]@OP}` (indirect
    // through name[@], then OP) by whether a transform op is present.
    // With an `@OP` transform, the keys interpretation is suppressed and the
    // `[@]` modifies the indirect lookup instead.
    if (rawPart.indirect && (rawPart.subscript == "@" || rawPart.subscript == "*") &&
        rawPart.op == null
    ) {
        val keys: List<String> =
            indexedArrays[rawPart.parameter]?.keys?.sorted()?.map { it.toString() }
                ?: assocArrays[rawPart.parameter]?.let { orderAssocKeys(rawPart.parameter, it.keys) }
                ?: env[rawPart.parameter]?.let { listOf("0") }
                ?: emptyList()
        val atSpread = rawPart.subscript == "@"
        if (atSpread && quoted) {
            if (keys.isNotEmpty()) {
                groups.last() += Fragment(keys.first(), quoted = true, splittable = false)
                for (i in 1 until keys.size) {
                    groups += mutableListOf(Fragment(keys[i], quoted = true, splittable = false))
                }
            }
        } else {
            val sep = firstIfsChar()
            groups.last() += Fragment(keys.joinToString(sep), quoted = quoted, splittable = !quoted)
        }
        return
    }
    // `${!name}` — resolve `name` to a string, then expand *that* as the actual parameter.
    // Nameref quirk: if `name` itself is a nameref binding, bash's `${!name}`
    // returns the *target name* directly (the nameref's raw scalar), with
    // NO further expansion. For a normal scalar `name=bar`, `${!name}`
    // means "expand $bar". The two diverge on namerefs; short-circuit
    // before the recursive lookup so the nameref case emits the raw name.
    if (rawPart.indirect) {
        val rawBinding = nameRefRawTarget(rawPart.parameter)
        if (rawBinding != null) {
            groups.last() += Fragment(rawBinding, quoted = quoted, splittable = !quoted)
            return
        }
    }
    val part: WordPart.ParameterExpansion =
        if (rawPart.indirect) {
            // `${!name[@]@OP}` / `${!name[*]@OP}`: the `[@]`/`[*]` modifies the
            // indirect lookup — for an array `name`, join its elements as a
            // single string and use that as the target name; for scalar
            // `name`, the result is the scalar value. The subscript is then
            // consumed (the OP applies to the de-referenced target as a
            // plain scalar, not `${target[@]}`).
            val resolvedTarget =
                if ((rawPart.subscript == "@" || rawPart.subscript == "*") && rawPart.op != null) {
                    val arr = indexedArrays[rawPart.parameter]
                    val asc = assocArrays[rawPart.parameter]
                    val sep = " "
                    when {
                        arr != null -> arr.entries.sortedBy { it.key }.joinToString(sep) { it.value }
                        asc != null -> asc.values.joinToString(sep)
                        else -> lookup(rawPart.parameter)
                    }
                } else {
                    null
                }
            val indirectName = resolvedTarget ?: lookup(rawPart.parameter)
            if (indirectName.isEmpty()) {
                // bash: when the indirect resolves to nothing AND the form
                // carries a default-value family op (`:-`, `-`, `:+`, `+`,
                // `:=`, `=`, `:?`, `?`), proceed with an unset synthetic
                // parameter so the default-value branch fires. For all
                // other ops / bare reference, emit empty.
                if (rawPart.op in setOf("-", ":-", "+", ":+", "=", ":=", "?", ":?")) {
                    rawPart.copy(parameter = "", indirect = false)
                } else {
                    groups.last() += Fragment("", quoted = quoted, splittable = !quoted)
                    return
                }
            } else if (!isValidIndirectTarget(indirectName)) {
                // bash: the resolved name must itself be a valid shell identifier
                // (or a special/positional parameter). `v=bad-var; echo ${!v}` →
                // `bash: bad-var: invalid variable name` + the command fails,
                // but (unlike `${var?msg}`) does NOT terminate the script.
                pendingDiagnostics += "$indirectName: invalid variable name"
                commandAbortFlag[0] = true
                groups.last() += Fragment("", quoted = quoted, splittable = !quoted)
                return
            } else {
                // bash: the indirect target may carry a `[subscript]` —
                // `xx=arrayA[*]; ${!xx}` ≡ `${arrayA[*]}`. Split off the
                // subscript and store it separately so the spread path
                // sees a proper name + subscript pair.
                // When `resolvedTarget` was used (the `[@]@OP` indirect form),
                // the original subscript has already been consumed; the OP
                // applies to the scalar target with no subscript.
                val bracket = indirectName.indexOf('[')
                if (bracket > 0 && indirectName.endsWith(']')) {
                    rawPart.copy(
                        parameter = indirectName.substring(0, bracket),
                        subscript = indirectName.substring(bracket + 1, indirectName.length - 1),
                        indirect = false,
                    )
                } else if (resolvedTarget != null) {
                    rawPart.copy(parameter = indirectName, subscript = null, indirect = false)
                } else {
                    rawPart.copy(parameter = indirectName, indirect = false)
                }
            }
        } else {
            rawPart
        }
    // Array / positional spread: `${A[@]}`, `${A[*]}`, `$@`, `$*`, and the
    // per-element operator forms `${A[@]^pat}`, `${@^^[aeiou]}`, etc.
    // Length form (`${#A[@]}`) handled below in the single-value branch.
    if (isSpreadSubscript(part) && !part.lengthOf) {
        val elements = spreadElements(part.parameter, part.subscript)
        val atSpread = isAtSpread(part)
        if (part.op == null) {
            emitSpread(groups, elements, quoted, atSpread)
            return
        }
        if (part.op == ":") {
            // bash: `${name[@]:offset:length}` on a SCALAR (not actually
            // an array) is the same as `${name:offset:length}` —
            // substring of the scalar, NOT an empty array slice.
            // (bash man page §Parameter Expansion: array subscripting
            // a scalar treats it as element [0].)
            val isPositionalSpread = part.parameter == "@" || part.parameter == "*"
            if (!isPositionalSpread &&
                part.parameter !in indexedArrays &&
                part.parameter !in assocArrays
            ) {
                val rawValue = lookup(part.parameter)
                val offText = part.arg1?.let { expandOperand(it) } ?: ""
                val lenText = part.arg2?.let { expandOperand(it) }
                val out = substring(rawValue, offText, lenText, part.parameter)
                groups.last() += Fragment(out, quoted = quoted, splittable = !quoted)
                return
            }
            val offsetText = part.arg1?.let { expandOperand(it) } ?: "0"
            val offset = evalSliceArith(offsetText) ?: 0
            // INDEX-based slicing for indexed arrays (bash semantics).
            // On a sparse array `av={1:'one', 3:'three', 5:'five'}`,
            // `${av[@]:1:2}` returns the elements at indices >= 1 in
            // numeric order, taking 2 — `one three`, not the 2nd and
            // 3rd elements of the dense projection. Positional `@`/`*`
            // spreads and assoc arrays keep positional semantics.
            val isIndexedArray = !isPositionalSpread && part.parameter in indexedArrays
            val universe: List<String>
            val start: Int
            val end: Int
            if (isIndexedArray) {
                val arr = indexedArrays.getValue(part.parameter)
                val sortedKeys = arr.keys.sorted()
                // bash treats the offset as the starting INDEX. Negative
                // offset N translates to `max_key + 1 + N` (index from
                // the end), then we collect keys >= that index. The
                // positional-style "count from end of dense list" is
                // wrong for sparse arrays — `${av[@]: -2:2}` on
                // av={1,2,3,5,7} is index 7-1=6, skip unset 6, take
                // av[7] → "seven", not the last 2 dense elements.
                val maxKey = sortedKeys.lastOrNull() ?: 0
                val startIdx = if (offset < 0) maxKey + 1 + offset else offset
                val effOffset =
                    sortedKeys
                        .indexOfFirst { it >= startIdx }
                        .let { if (it < 0) sortedKeys.size else it }
                val len = part.arg2?.let { expandOperand(it) }?.let { evalSliceArith(it) }
                val takeCount =
                    if (len == null) {
                        sortedKeys.size - effOffset
                    } else if (len < 0) {
                        val rawLen = renderArgRawSource(part.arg2) ?: ""
                        pendingDiagnostics += "$rawLen: substring expression < 0"
                        commandAbortFlag[0] = true
                        return
                    } else {
                        len
                    }
                universe =
                    sortedKeys
                        .drop(effOffset)
                        .take(takeCount)
                        .map { arr.getValue(it) }
                start = 0
                end = universe.size
            } else {
                universe = if (isPositionalSpread) listOf(scriptName) + elements else elements
                start =
                    if (offset < 0) {
                        (universe.size + offset).coerceAtLeast(0)
                    } else {
                        offset.coerceAtMost(universe.size)
                    }
                end =
                    part.arg2?.let { expandOperand(it) }?.let { lengthText ->
                        val len = evalSliceArith(lengthText) ?: 0
                        if (len < 0) {
                            if (isPositionalSpread || atSpread) {
                                val rawLen = renderArgRawSource(part.arg2) ?: lengthText.trim()
                                pendingDiagnostics += "$rawLen: substring expression < 0"
                                commandAbortFlag[0] = true
                                return
                            }
                            (universe.size + len).coerceAtLeast(start)
                        } else {
                            (start + len).coerceAtMost(universe.size)
                        }
                    } ?: universe.size
            }
            val sliced = if (start < end) universe.subList(start, end) else emptyList()
            emitSpread(groups, sliced, quoted, atSpread)
            return
        }
        if (part.op in perElementOps) {
            val arg1 = part.arg1?.let { expandOperand(it) } ?: ""
            // Patsub ops (`/`, `//`, `/#`, `/%`) need source-`\X` marking on
            // the replacement; other per-element ops (case mod / transform)
            // take plain text.
            val isPatsub = part.op in setOf("/", "//", "/#", "/%")
            val arg2 =
                if (isPatsub) {
                    part.arg2?.let { expandReplacementOperand(it) }
                } else {
                    part.arg2?.let { expandOperand(it) }
                }
            // Variable-aware `@` transforms (A/a/K/k) on a *named* array
            // are not per-element: they consume the whole variable and
            // emit one value. Bash:
            //   `${arr[@]@A}` == `${arr@A}` == one `declare …` line
            //   `${arr[@]@K}` == `${arr@K}` == one key-value dump
            // Positional spread (`${@@A}`, `${@@K}`) IS per-element —
            // each $1, $2, … gets its own transform.
            if (part.op == "@" && arg1 in setOf("A", "a", "K", "k")) {
                // Variable-aware `@` transforms (A/a/K/k):
                //   `${arr[@]@A}` == `${arr@A}` (one declare line)
                //   `${arr[@]@K}` (@-spread) on a named indexed/assoc
                //     array emits each pair element as a SEPARATE word:
                //     8 args for a 4-pair array. For `$@` / `$*` it's
                //     per-positional @Q joined.
                //   `${@@A}` / `${*@A}` recreates positionals as
                //     `set -- 'p1' 'p2' …` — one value.
                val indexed = indexedArrays[part.parameter]
                val assoc = assocArrays[part.parameter]
                val isArrayKSpread =
                    arg1 in setOf("K", "k") && atSpread && (indexed != null || assoc != null)
                if (isArrayKSpread) {
                    val quote = arg1 == "K"

                    fun bashDqQuote(v: String): String {
                        val sb = StringBuilder("\"")
                        for (c in v) {
                            if (c == '\\' || c == '"' || c == '$' || c == '`') sb.append('\\')
                            sb.append(c)
                        }
                        sb.append('"')
                        return sb.toString()
                    }

                    // Bash's "quote only when needed" key form for `@K`:
                    // unquoted if all chars are alnum/underscore/dot/dash/slash;
                    // otherwise wrapped in dquote-escaped form. Mirrors the
                    // [Interpreter.quoteAssocKey] used by declare-p; inlined
                    // here because Expander isn't an Interpreter receiver.
                    fun keyQuote(k: String): String {
                        if (k.isEmpty()) return "\"\""
                        // Bash's `@K` key-quoting (verified bash 5.3):
                        //   * Standalone `*` or `@` → quoted (they're the
                        //     subscript-wildcard sentinels in a read).
                        //   * Contains a shell-meta char (`;[](){}&|*?$<>`,
                        //     space/tab/newline, quote chars, ctrl) → quoted.
                        //   * Plain `=`, `.`, `-`, `+`, `@`, `#`, `%`, `:`,
                        //     `/` etc. flow through unquoted.
                        if (k == "*" || k == "@") return bashDqQuote(k)
                        val needs =
                            k.any { c ->
                                c in " \t\n;[](){}&|*?$<>\\\"'`" || c.code < 0x20
                            }
                        return if (needs) bashDqQuote(k) else k
                    }
                    val pieces = mutableListOf<String>()
                    if (indexed != null) {
                        for ((k, vv) in indexed.entries.sortedBy { it.key }) {
                            pieces += k.toString()
                            pieces += if (quote) bashDqQuote(vv) else vv
                        }
                    } else if (assoc != null) {
                        // Iterate via BashAssocOrder so `${arr[@]@K}` matches
                        // bash's hash-table iteration order (reverse-insertion
                        // for small arrays), not LinkedHashMap insertion order.
                        // Keys go through `quoteAssocKey` (selective quoting —
                        // simple alphanumeric keys come out bare); values use
                        // `bashDqQuote` (always double-quoted under `@K`).
                        for (k in orderAssocKeys(part.parameter, assoc.keys)) {
                            val vv = assoc[k] ?: ""
                            pieces += if (quote) keyQuote(k) else k
                            pieces += if (quote) bashDqQuote(vv) else vv
                        }
                    }
                    emitSpread(groups, pieces, quoted, atSpread)
                    return
                }
                if ((part.parameter == "@" || part.parameter == "*") &&
                    arg1 in setOf("K", "k")
                ) {
                    // Positional K/k: each element @Q-quoted. `"${@@K}"`
                    // spreads per-element (one word each, like `"$@"`);
                    // `"${*@K}"` joins them space-separated into one word.
                    val quotedPieces =
                        elements.map { applyStringOp(part.op, it, "Q", arg2, part.parameter) }
                    if (part.parameter == "@") {
                        emitSpread(groups, quotedPieces, quoted, atSpread)
                    } else {
                        groups.last() +=
                            Fragment(quotedPieces.joinToString(" "), quoted = quoted, splittable = !quoted)
                    }
                    return
                }
                val rep = elements.firstOrNull() ?: ""
                val whole = applyStringOp(part.op, rep, arg1, arg2, part.parameter)
                groups.last() += Fragment(whole, quoted = quoted, splittable = !quoted)
                return
            }
            val transformed = elements.map { applyStringOp(part.op, it, arg1, arg2, part.parameter) }
            // Bash 5: unquoted `*`-spread with a per-element op (`/`, `^`,
            // `#`, etc.) under IFS=null preserves element boundaries. The
            // per-element transformation makes the spread "$@-like"
            // structurally — bash applies the op per element, and with
            // IFS=null there's no separator to collapse the result into
            // one word, so each transformed element becomes its own
            // field. Same rule for positional `${*/a/x}` and named
            // `${a[*]/a/x}` / assoc `${A[*]/a/x}`. The quoted forms still
            // join (handled in emitSpread). array26.sub: 8 of the 12
            // IFS=null `recho` lines depend on this preserve rule.
            if (!quoted && !atSpread && !inAssignmentRhs) {
                val ifsVal = env["IFS"]
                if (ifsVal != null && ifsVal.isEmpty()) {
                    if (transformed.isNotEmpty()) {
                        groups.last() += Fragment(transformed.first(), quoted = false, splittable = true)
                        for (i in 1 until transformed.size) {
                            groups += mutableListOf(Fragment(transformed[i], quoted = false, splittable = true))
                        }
                    }
                    return
                }
            }
            emitSpread(groups, transformed, quoted, atSpread)
            return
        }
        // Defaults / alternates apply to the spread-as-whole. Treat the
        // spread as "set" when there's at least one element, taking its
        // first value as the representative for `:-`/`:?` emptiness tests.
        // Falls through to existing path with synthetic rawSet/rawValue
        // below; we just join elements as a single value.
    }

    // Plain `$@`, `$*`, `$#`, `$?` — only when no operator/length/subscript applied.
    if (part.op == null && !part.lengthOf && part.subscript == null) {
        when (part.parameter) {
            "@" -> {
                // Bare `$@` / `"$@"` actually flows through the
                // isSpreadSubscript path (`emitSpread`) higher up; this
                // branch is defensive for the no-op-no-subscript case.
                if (quoted && positional.isNotEmpty()) {
                    groups.last() += Fragment(positional.first(), quoted = true, splittable = false)
                    for (idx in 1 until positional.size) {
                        groups += mutableListOf(Fragment(positional[idx], quoted = true, splittable = false))
                    }
                } else if (!quoted && positional.isNotEmpty()) {
                    groups.last() += Fragment(positional.first(), quoted = false, splittable = true)
                    for (idx in 1 until positional.size) {
                        groups += mutableListOf(Fragment(positional[idx], quoted = false, splittable = true))
                    }
                }
                return
            }

            "*" -> {
                val sep = firstIfsChar()
                groups.last() += Fragment(positional.joinToString(sep), quoted = quoted, splittable = !quoted)
                return
            }

            "#" -> {
                groups.last() += Fragment(positional.size.toString(), quoted = quoted, splittable = !quoted)
                return
            }

            "?" -> {
                groups.last() += Fragment(lastExit.toString(), quoted = quoted, splittable = !quoted)
                return
            }

            "!" -> {
                // `$!` is unset until the first background command
                // runs. Under `set -u` bash errors on a bare `$!`
                // reference made before any `&` has run.
                if (lastBgPid == null && nounset) {
                    throw ParameterError("!", "unbound variable")
                }
                groups.last() += Fragment(lastBgPid?.toString() ?: "", quoted = quoted, splittable = !quoted)
                return
            }

            "$" -> {
                // POSIX `$$`: the *shell process's* pid. Sticky across
                // subshells (bash semantics — `(echo $$)` prints the
                // parent shell's pid, not the subshell's). For
                // per-process pid, scripts use `$BASHPID`.
                groups.last() += Fragment(shellPid.toString(), quoted = quoted, splittable = !quoted)
                return
            }

            "-" -> {
                // POSIX `$-`: the set of single-letter shell option flags
                // currently in effect. Bash composes this from the active
                // `set` options at expansion time (so `set -e` flips `e`
                // into the string, `set +e` flips it back out). Order
                // follows bash's emission order. `i` is included when
                // the shell is interactive.
                groups.last() += Fragment(currentDashFlags(), quoted = quoted, splittable = !quoted)
                return
            }

            else -> {
                // For an indexed/assoc array, `${name}` is `${name[0]}`.
                // Fall back to lookup for scalars (and unset vars).
                // POSIX §2.5.2 / `set -u`: an unset bare `$name` / `${name}`
                // is an error here too (same NOUNSET_EXEMPT_SPECIALS rule).
                if (nounset && !isParameterSet(part.parameter) &&
                    part.parameter !in NOUNSET_EXEMPT_SPECIALS
                ) {
                    val diagName =
                        if (part.parameter.all { it.isDigit() } && !part.braced) {
                            "$${part.parameter}"
                        } else {
                            part.parameter
                        }
                    throw ParameterError(diagName, "unbound variable")
                }
                val value =
                    indexedArrays[part.parameter]?.get(0)
                        ?: assocArrays[part.parameter]?.get("0")
                        ?: lookup(part.parameter)
                groups.last() += Fragment(value, quoted = quoted, splittable = !quoted)
                return
            }
        }
    }
    // Decide whether the parameter is "set" (and "non-null" for `:` variants).
    // Bash evaluates the subscript text exactly once per `${name[sub]}` use
    // (subscripts may have side effects like `count++`). Resolve the
    // subscript here, then pass the pre-resolved form into both helpers so
    // arithmetic doesn't run twice.
    // Bash 5 quirk: `${name["{N..M}"]}` inside outer `"..."` applies
    // brace expansion to the subscript text — `letters[{2..6}]` reads
    // letters[2] letters[3] … letters[6] and joins with space. The
    // inner `"..."` doesn't suppress brace expansion the way the
    // outer dquote normally would, because bash's brace scanner treats
    // subscript brackets as a separate context. Only the simple
    // numeric-range form `{N..M}` is handled here; the comma-list form
    // (`{a,b,c}`) inside a subscript would need a string-level brace
    // expander and isn't exercised by the array conformance corpus.
    if (part.subscript != null && part.op == null && !part.lengthOf) {
        val expandedSubs = expandBraceRangeSubscript(part.subscript)
        if (expandedSubs != null && expandedSubs.size > 1) {
            val pieces =
                expandedSubs.map { sub ->
                    val r = resolveSubscriptOnce(part.parameter, sub)
                    subscriptValue(part.parameter, r)
                }
            groups.last() += Fragment(pieces.joinToString(" "), quoted = quoted, splittable = !quoted)
            return
        }
    }
    val resolvedSub = resolveSubscriptOnce(part.parameter, part.subscript, part.rawSubscript != null)

    // An associative-array subscript that expands to empty is a bad
    // subscript in bash (assoc keys can't be empty). The originating text
    // wasn't literally `[]` — that's a parse-time `bad substitution` — but
    // something like `$unset` / `""` / `$a$b` that vanished under
    // expansion. Bash reports it two ways: the length form
    // `${#a[$unset]}` echoes the UNEXPANDED subscript in brackets
    // (`[$unset]: bad array subscript`) and aborts the command; the plain
    // form `${a[$unset]}` uses the var name (`a: bad array subscript`)
    // and the expansion yields empty (the command continues).
    if (part.parameter in assocArrays && resolvedSub != null && resolvedSub.isEmpty() &&
        part.subscript != "@" && part.subscript != "*"
    ) {
        val rawSub = part.rawSubscript ?: part.subscript
        if (!rawSub.isNullOrEmpty()) {
            if (part.lengthOf) {
                pendingDiagnostics += "[$rawSub]: bad array subscript"
                commandAbortFlag[0] = true
                return
            } else {
                pendingDiagnostics += "${part.parameter}: bad array subscript"
                groups.last() += Fragment("", quoted = quoted, splittable = !quoted)
                return
            }
        }
    }

    // Reaching the single-value path with an `@`/`*` resolved subscript means
    // it wasn't a spread (isSpreadSubscript already returned false) — so when
    // the subscript was expansion-introduced (`key=@; ${a[$key]}`) treat it as
    // a literal assoc key, not the spread sentinel.
    val literalAtStar = part.rawSubscript != null && (resolvedSub == "@" || resolvedSub == "*")
    val rawSet = subscriptIsSet(part.parameter, resolvedSub, literalKey = literalAtStar)
    val rawValue = subscriptValue(part.parameter, resolvedSub, literalKey = literalAtStar)

    // subscriptValue/IsSet flag a negative-OOB subscript via lastBadSubscript.
    // Bash formats this two ways: `name: bad array subscript` for a regular
    // `${A[-N]}` read (and the surrounding word/command continues with an
    // empty value), `[-N]: bad array subscript` for the length form
    // `${#A[-N]}` (and the enclosing simple command is aborted — `echo`
    // produces no output).
    if (lastBadSubscript.isNotEmpty()) {
        val sub = lastBadSubscript.removeAt(lastBadSubscript.lastIndex)
        if (part.lengthOf) {
            pendingDiagnostics += "[$sub]: bad array subscript"
            commandAbortFlag[0] = true
            return
        } else {
            pendingDiagnostics += "${part.parameter}: bad array subscript"
        }
    }

    // POSIX §2.5.2 / bash `set -u` (nounset): a reference to an unset
    // parameter outside the `-`/`+`/`=`/`?` default-value family is an
    // error. Specials `$@`/`$*`/`$#` are exempt — they're always set
    // (count may be zero) — and any positional `$N` past `$#` is also
    // *not* exempt. The default-value ops `${var-x}` / `${var+x}` etc.
    // explicitly opt into testing for unset, so they bypass the check
    // even when var is unset. Length (`${#var}`), substring trim
    // (`${var#pat}`), replacement (`${var/p/r}`), case-mod
    // (`${var^^}`), substring (`${var:N}`) all DO trigger nounset
    // when var is unset — bash man page §Parameter Expansion: only
    // the default-value ops `${var-x}`/`${var+x}`/`${var=x}`/`${var?x}`
    // (and their `:` variants) are exempt.
    if (nounset && !rawSet && part.op !in NOUNSET_EXEMPT_OPS &&
        part.parameter !in NOUNSET_EXEMPT_SPECIALS
    ) {
        // bash prints `<name>: unbound variable` and exits non-interactive
        // shell with code 1. For bare `$N` positional refs bash includes the
        // `$` prefix (`$9: unbound variable`); for the braced form `${N}` it
        // strips it (`9: unbound variable`). This distinguishes the two
        // surface forms in the diagnostic.
        // For an indirect lookup `${!ref@OP}` that triggers nounset on the
        // resolved target, bash echoes the *original* indirect form (`!ref`)
        // rather than the resolved target name.
        val diagName =
            if (rawPart.indirect) {
                "!${rawPart.parameter}"
            } else if (part.parameter.all { it.isDigit() } && !part.braced) {
                "$${part.parameter}"
            } else if (part.subscript != null && part.subscript !in setOf("@", "*")) {
                // bash includes the subscript in the unbound diagnostic
                // for indexed/assoc-array element references:
                //   `${narray[4]}` → "narray[4]: unbound variable"
                "${part.parameter}[${part.subscript}]"
            } else {
                part.parameter
            }
        throw ParameterError(diagName, "unbound variable")
    }

    if (part.lengthOf) {
        // `${#x[@]}` / `${#x[*]}` — count of array elements (scalar treated as 1-elem).
        if (part.subscript == "@" || part.subscript == "*") {
            val count = spreadElements(part.parameter, part.subscript).size
            groups.last() += Fragment(count.toString(), quoted = quoted, splittable = !quoted)
            return
        }
        val len =
            when (part.parameter) {
                "@", "*" -> {
                    positional.size.toString()
                }

                "#" -> {
                    positional.size
                        .toString()
                        .length
                        .toString()
                }

                "?" -> {
                    lastExit.toString().length.toString()
                }

                "!" -> {
                    (lastBgPid?.toString() ?: "").length.toString()
                }

                "-" -> {
                    currentDashFlags().length.toString()
                }

                "$" -> {
                    shellPid.toString().length.toString()
                }

                else -> {
                    rawValue.length.toString()
                }
            }
        groups.last() += Fragment(len, quoted = quoted, splittable = !quoted)
        return
    }
    val op = part.op
    // bash: the read-only special parameters `#`, `*`, `@` reject any
    // "modifying" or transform op. Only the value-returning ops
    // (`-`/`:-` default, `?`/`:?` error, `+`/`:+` alternate, `:N:M`
    // substring) are allowed; assign (`=`/`:=`), patsub (`/`...),
    // strip (`#`/`##`/`%`/`%%`), case-mod, and `@OP` raise bad-sub.
    // `${#/}`, `${#%}`, `${#=}` etc. exercise this rule.
    if (part.parameter == "#" &&
        op != null && op !in setOf("-", ":-", "?", ":?", ":")
    ) {
        throw com.accucodeai.kash.parser.ParseException(
            "\${#${op}${if (part.arg1?.parts?.isNotEmpty() == true) "..." else ""}}: bad substitution",
        )
    }
    val operandText: () -> String = { part.arg1?.let { expandOperand(it) } ?: "" }
    val operand2Text: () -> String? = { part.arg2?.let { expandOperand(it) } }
    // Strip-ops (`#`/`##`/`%`/`%%`) operands are glob patterns: quoted
    // spans (`'X'`, `"X"`, `\X`) must match literally, not as glob
    // metachars. `${P%"*"}` strips a literal `*`, not "any tail".
    val patternOperandText: () -> String = { part.arg1?.let { expandPattern(it) } ?: "" }
    // Patsub replacement gets the marked-source expansion so
    // `expandRepl` can distinguish source-`\X` from value-derived `\X`.
    val patsubReplText: () -> String = { part.arg2?.let { expandReplacementOperand(it) } ?: "" }

    // Default-value ops splice the operand's fragment-groups into the outer
    // result so a quoted spread like `"$@"` inside the default retains its
    // field structure — e.g. `${undef-"$@"}` with positional=("") still emits
    // one empty field rather than collapsing to a joined string.
    if (op in listOf("-", ":-", "+", ":+")) {
        val useDefault =
            when (op) {
                "-" -> !rawSet
                ":-" -> !rawSet || rawValue.isEmpty()
                "+" -> rawSet
                ":+" -> rawSet && rawValue.isNotEmpty()
                else -> false
            }
        if (useDefault) {
            val operandGroups =
                part.arg1?.let { spliceOperandFragments(it, quoted) }
                    ?: mutableListOf(mutableListOf())
            groups.last().addAll(operandGroups.first())
            for (i in 1 until operandGroups.size) groups += operandGroups[i]
            // Quoted-context empty default (`"${foo:-}"` with foo unset)
            // must still produce one empty field — POSIX §2.6.2: double-
            // quoted parameter expansions never split into zero fields.
            // Without this, `recho "${foo:-}"` drops the arg entirely.
            if (quoted && operandGroups.size == 1 && operandGroups.first().isEmpty()) {
                groups.last() += Fragment("", quoted = true, splittable = false)
            }
        } else {
            // bash: `${@-default}` / `${*-default}` / `${arr[@]-default}`
            // with the parameter set behaves like the bare spread (`$@` /
            // `${arr[@]}`) — each element is its own field in quoted
            // context. Without this branch, `"${@-}"` with `set -- a b`
            // collapses to one joined-by-IFS field `"a b"`, and the
            // surrounding `for i in "${@-}"` iterates once over the
            // joined string instead of twice over the elements.
            if (isSpreadSubscript(part)) {
                val elements = spreadElements(part.parameter, part.subscript)
                val atSpread = isAtSpread(part)
                emitSpread(groups, elements, quoted, atSpread)
            } else {
                groups.last() += Fragment(rawValue, quoted = quoted, splittable = !quoted)
            }
        }
        return
    }

    val result: String =
        when (op) {
            ":=" -> {
                if (!isAssignableName(part.parameter)) {
                    // bash: `${$N=val}` / `${$@:=val}` etc — recoverable error.
                    // Emit `$<name>: cannot assign in this way` to stderr and
                    // skip the assignment, returning the current rawValue.
                    pendingDiagnostics += "\$${part.parameter}: cannot assign in this way"
                    commandAbortFlag[0] = true
                    rawValue
                } else if (rawSet && rawValue.isNotEmpty()) {
                    rawValue
                } else {
                    val v = operandText()
                    val sub = part.subscript
                    val stored =
                        if (sub != null && sub != "@" && sub != "*") {
                            assignArrayElement(part.parameter, sub, v)
                        } else if (sub != null && part.parameter in assocArrays) {
                            // `${A[@]:=v}` / `${A[*]:=v}` on an assoc array
                            // writes the literal key `@`/`*` (bash quirk —
                            // for assoc, `@` and `*` aren't spread on the
                            // write side of := / =).
                            assignArrayElement(part.parameter, sub, v)
                        } else if (sub != null && part.rawSubscript != null) {
                            // A `@`/`*` subscript that came from EXPANSION
                            // (`key=@; ${A[$key]:=v}`) is a literal key on a
                            // non-associative base — bash arithmetic-evaluates
                            // it, which fails. Verified bash 5.3.
                            pendingDiagnostics +=
                                "$sub: arithmetic syntax error: operand expected (error token is \"$sub\")"
                            commandAbortFlag[0] = true
                            rawValue
                        } else {
                            env[part.parameter] = v
                            v
                        }
                    stored
                }
            }

            "=" -> {
                if (!isAssignableName(part.parameter)) {
                    pendingDiagnostics += "\$${part.parameter}: cannot assign in this way"
                    commandAbortFlag[0] = true
                    rawValue
                } else if (rawSet) {
                    rawValue
                } else {
                    val v = operandText()
                    val sub = part.subscript
                    val stored =
                        if (sub != null && sub != "@" && sub != "*") {
                            assignArrayElement(part.parameter, sub, v)
                        } else if (sub != null && part.parameter in assocArrays) {
                            // `${A[@]=v}` / `${A[*]=v}` on an assoc array
                            // writes the literal key `@`/`*` (bash quirk —
                            // for assoc, `@` and `*` aren't spread on the
                            // write side of := / =).
                            assignArrayElement(part.parameter, sub, v)
                        } else if (sub != null && part.rawSubscript != null) {
                            // A `@`/`*` subscript that came from EXPANSION
                            // (`key=@; ${A[$key]=v}`) is a literal key on a
                            // non-associative base — bash arithmetic-evaluates
                            // it, which fails. Verified bash 5.3.
                            pendingDiagnostics +=
                                "$sub: arithmetic syntax error: operand expected (error token is \"$sub\")"
                            commandAbortFlag[0] = true
                            rawValue
                        } else {
                            env[part.parameter] = v
                            v
                        }
                    stored
                }
            }

            ":?" -> {
                if (rawSet && rawValue.isNotEmpty()) {
                    rawValue
                } else {
                    throw ParameterError(part.parameter, operandText())
                }
            }

            "?" -> {
                if (rawSet) {
                    rawValue
                } else {
                    throw ParameterError(part.parameter, operandText())
                }
            }

            "#" -> {
                stripPrefix(rawValue, patternOperandText(), longest = false)
            }

            "##" -> {
                stripPrefix(rawValue, patternOperandText(), longest = true)
            }

            "%" -> {
                stripSuffix(rawValue, patternOperandText(), longest = false)
            }

            "%%" -> {
                stripSuffix(rawValue, patternOperandText(), longest = true)
            }

            "/" -> {
                patternSubst(rawValue, operandText(), patsubReplText(), all = false, anchor = null)
            }

            "//" -> {
                patternSubst(rawValue, operandText(), patsubReplText(), all = true, anchor = null)
            }

            "/#" -> {
                patternSubst(rawValue, operandText(), patsubReplText(), all = false, anchor = '#')
            }

            "/%" -> {
                patternSubst(rawValue, operandText(), patsubReplText(), all = false, anchor = '%')
            }

            "^" -> {
                caseMod(rawValue, operandText(), upper = true, all = false)
            }

            "^^" -> {
                caseMod(rawValue, operandText(), upper = true, all = true)
            }

            "," -> {
                caseMod(rawValue, operandText(), upper = false, all = false)
            }

            ",," -> {
                caseMod(rawValue, operandText(), upper = false, all = true)
            }

            "~" -> {
                caseToggle(rawValue, operandText(), all = false)
            }

            "~~" -> {
                caseToggle(rawValue, operandText(), all = true)
            }

            "@" -> {
                // Bash: `${unset@OP}` produces empty for value-derived
                // ops (Q, E, U/L/u/c, P), but the declaration-aware ops
                // (A, a, K, k) DO inspect attributes of declared-but-
                // unset variables. `declare -lr VAR1; echo "${VAR1@A}"`
                // emits `declare -rl VAR1` — same as `declare -p`.
                val opLetter = operandText()
                if (!rawSet && opLetter !in setOf("A", "a", "K", "k")) {
                    ""
                } else {
                    transformOp(rawValue, opLetter, part.parameter)
                }
            }

            ":" -> {
                // `${name:}` (empty offset AND no `:length` separator)
                // is a bash bad substitution — the offset arg is required.
                val arg1Empty = part.arg1?.parts?.isEmpty() ?: true
                if (arg1Empty && part.arg2 == null) {
                    throw com.accucodeai.kash.parser.ParseException(
                        "\${${part.parameter}${part.op}}: bad substitution",
                    )
                }
                substring(rawValue, operandText(), operand2Text(), part.parameter)
            }

            else -> {
                rawValue
            }
        }
    groups.last() += Fragment(result, quoted = quoted, splittable = !quoted)
}

internal fun Expander.expandOperand(word: Word): String {
    // Default-value operands (`${var:=word}`, `${var:?word}`, etc.) get
    // tilde expansion applied to their leading literal — bash extends
    // tilde expansion past assignment RHS to all parameter-expansion
    // default words.
    val tilded = applyTilde(word, assignmentContext = false)
    val nested = expandToFragments(tilded)
    // `expandToFragments` of `$@` emits one group per element (so direct
    // `recho $@` preserves positional boundaries — see emitSpread).
    // When that same operand feeds a `${var-$@}` / `${var=$@}` default,
    // we collapse the groups back into a single scalar joined by a
    // SPACE — bash's documented (and "inconsistent" per posixexp4.sub)
    // behavior for $@ inside an assignment-default RHS, regardless of
    // IFS. `$*` already arrives as one group joined by IFS so the join
    // here is a no-op for it.
    return nested.joinToString(" ") { group -> group.joinToString("") { it.text } }
}

/**
 * Expand a patsub replacement operand (the `repl` in
 * `${var//pat/repl}`) into a String, marking source-derived
 * backslashes with [Sentinels.CTLESC] so [patternSubst.expandRepl]
 * can tell source-`\X` apart from value-derived `\X`. Mirrors bash's
 * combined effect of expanding the replacement (`RHS string expander`,
 * ) and then running `replacement quoter`
 * on the result.
 *
 * The marking rule: a `WordPart.Escaped(X)` came from source `\X`,
 * so emit `CTLESC X`. A literal `\` in a `Literal` or `SingleQuoted`
 * is also source-derived (the lexer kept it because dq/sq retention),
 * so prefix each such `\` with CTLESC. Value-derived backslashes
 * (from variable expansion) flow through unmarked.
 */
internal fun Expander.expandReplacementOperand(word: Word): String {
    val tilded = applyTilde(word, assignmentContext = false)
    val sb = StringBuilder()
    for (part in tilded.parts) emitForReplacement(sb, part)
    return sb.toString()
}

private fun Expander.emitForReplacement(
    sb: StringBuilder,
    part: WordPart,
) {
    when (part) {
        is WordPart.Literal -> {
            appendMarkedSourceLiteral(sb, part.value)
        }

        is WordPart.SingleQuoted -> {
            appendMarkedSourceLiteral(sb, part.value)
        }

        is WordPart.Escaped -> {
            // Source `\X` — emit CTLESC marker so expandRepl knows
            // this was a source-derived escape.
            sb.append(Sentinels.CTLESC)
            sb.append(part.value)
        }

        is WordPart.DoubleQuoted -> {
            // Bash: characters inside `"..."` in a patsub replacement are
            // literal — `&` is not the match marker, `\X` is preserved as
            // `\X` (no patsub_replacement expansion). Mark them with
            // CTLESC so [patternSubst]'s stage A pipeline preserves them
            // as literals rather than activating the `&` rule.
            for (p in part.parts) emitDqReplacement(sb, p)
        }

        else -> {
            // Value-derived content: expand normally, no CTLESC.
            sb.append(expandOperand(Word(listOf(part))))
        }
    }
}

private fun Expander.emitDqReplacement(
    sb: StringBuilder,
    part: WordPart,
) {
    when (part) {
        is WordPart.Literal -> {
            for (c in part.value) {
                if (c == '\\' || c == '&') sb.append(Sentinels.CTLESC)
                sb.append(c)
            }
        }

        is WordPart.SingleQuoted -> {
            appendMarkedSourceLiteral(sb, part.value)
        }

        is WordPart.Escaped -> {
            sb.append(Sentinels.CTLESC)
            sb.append(part.value)
        }

        is WordPart.DoubleQuoted -> {
            for (p in part.parts) emitDqReplacement(sb, p)
        }

        else -> {
            // Value-derived (parameter / cmdsub / arith): inside DQ in a
            // patsub replacement, `&` and `\` from the expanded value are
            // literal too — bash preserves them as `\&` in the result of
            // `"${var//pat/"$r2"}"` when `r2='x\&y'`.
            val expanded = expandOperand(Word(listOf(part)))
            for (c in expanded) {
                if (c == '\\' || c == '&') sb.append(Sentinels.CTLESC)
                sb.append(c)
            }
        }
    }
}

private fun appendMarkedSourceLiteral(
    sb: StringBuilder,
    s: String,
) {
    for (c in s) {
        if (c == '\\') sb.append(Sentinels.CTLESC)
        sb.append(c)
    }
}

/**
 * Expand a default-value operand into fragment groups, with the outer
 * quote-state seeded so literals inside an unquoted default stay splittable
 * while a nested `"$@"` retains its per-element quoted spread.
 *
 * Tilde expansion fires on unquoted operands (bash extends tilde-expansion
 * past assignment RHS to `${var:-~}` etc.). When the operand is consumed in
 * a quoted outer context — `"${var:-~}"` — the leading `~` stays literal.
 */
internal fun Expander.spliceOperandFragments(
    word: Word,
    outerQuoted: Boolean,
): MutableList<MutableList<Fragment>> {
    val w = if (outerQuoted) word else applyTilde(word, assignmentContext = false)
    val groups = mutableListOf<MutableList<Fragment>>(mutableListOf())
    // Mark "we're inside a default-value operand" so emitSpread can
    // apply bash's `$@` collapse rule (per [Expander.inDefaultValueOperand]).
    val savedOperandFlag = inDefaultValueOperand
    inDefaultValueOperand = true
    try {
        for (p in w.parts) appendPart(groups, p, quoted = outerQuoted)
    } finally {
        inDefaultValueOperand = savedOperandFlag
    }
    // POSIX §2.6.2: the operand of `${var:+word}` / `${var:-word}` etc., when
    // consumed in an unquoted outer context, is itself subject to field
    // splitting. Mark every unquoted fragment as splittable so a literal
    // space inside the operand acts as a separator the same way a space
    // produced by a `$var` expansion does. Quoted fragments (including
    // empty `""` placeholders) stay unsplittable and continue to anchor
    // empty fields across splits.
    if (!outerQuoted) {
        for (g in groups) {
            for (i in g.indices) {
                val f = g[i]
                if (!f.quoted && !f.splittable) {
                    g[i] = f.copy(splittable = true)
                }
            }
        }
    }
    return groups
}

internal fun Expander.isParameterSet(name: String): Boolean {
    val n = name.toIntOrNull()
    if (n != null) {
        return n == 0 || n - 1 in positional.indices
    }
    if (name == "@" || name == "*") return positional.isNotEmpty()
    // Single-char specials with derived values: `$!` is "set" only
    // after a background job has been recorded; the other specials
    // (`?`, `#`, `$`, `-`) are always set. So `${!?}` with no `&` yet
    // must error under `set -u`, but `:& echo ${!?}` must succeed
    // because the `&` set `$!`.
    if (name == "!") return lastBgPid != null
    if (name == "?" || name == "#" || name == "$" || name == "-") return true
    if (name in indexedArrays) return indexedArrays.getValue(name).isNotEmpty()
    if (name in assocArrays) return assocArrays.getValue(name).isNotEmpty()
    return env.containsKey(name)
}

/**
 * Strip a top-level `$(( … ))` wrapper from [text], leaving just the
 * inner arithmetic expression. Allows array subscripts like
 * `${A[$(( 0 ))]}` to reach ArithEval as just `0` instead of
 * `$(( 0 ))` (which bash arithmetic doesn't recognize as a token).
 * Returns [text] unchanged when there's no balanced wrapper.
 */
private fun unwrapArithSubst(text: String): String {
    val t = text.trim()
    if (t.length < 5 || !t.startsWith("\$((") || !t.endsWith("))")) return text
    return t.substring(3, t.length - 2)
}

/**
 * Pre-evaluate an indexed-array subscript's arithmetic exactly once,
 * so the surrounding `subscriptIsSet` + `subscriptValue` pair doesn't
 * double-fire side effects (e.g. `${days[count++]}` must increment
 * `count` once, not twice). Returns the subscript text rewritten to
 * a literal integer when the eval succeeded; otherwise returns the
 * original text so the per-callee error paths still fire.
 *
 * Special subscripts `@` / `*` (and null) are passed through unchanged.
 * Assoc arrays read the subscript as a string key — also pass through.
 */
internal fun Expander.resolveSubscriptOnce(
    name: String,
    sub: String?,
    alreadyExpanded: Boolean = false,
): String? {
    if (sub == null || sub == "@" || sub == "*") return sub
    // Assoc arrays use the raw key text; no arith eval. Bash strips
    // outer single/double quotes from the subscript text before
    // looking up the key — `${x["0"]}`, `${x['key']}`, `${x[key]}`
    // all resolve to the same key. We do a minimal pass that removes
    // unescaped `'`/`"` chars; embedded escaped quotes survive
    // (`${x[\"a\"]}` should keep the literal `"` in the key).
    // Verified bash 5.3 directly.
    //
    // [alreadyExpanded] is set when the subscript text was a `$`/backtick
    // form that the interpreter pre-expanded (preprocessPart →
    // expandSingle, which already did bash-correct quote removal). In that
    // case quotes that survive came from the EXPANSION (data), not the
    // source — e.g. `b="80's"; ${a[$b]}` keys on `80's`, the apostrophe
    // intact — so a second strip here would wrongly mangle the key. Only
    // the literal-source path (no pre-expansion) needs the strip.
    if (name in assocArrays) {
        if (alreadyExpanded) return sub
        val sb = StringBuilder()
        var i = 0
        while (i < sub.length) {
            val c = sub[i]
            if (c == '\\' && i + 1 < sub.length) {
                sb.append(sub[i + 1])
                i += 2
            } else if (c == '"' || c == '\'') {
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
    val cleaned = unwrapArithSubst(sub.filter { it != '"' && it != '\'' })
    // Bash quirk: unquoted blank subscript (`a[ ]`) arith-evaluates to 0,
    // but a quoted blank (`a[' ']`, `a["$v"]` with $v whitespace) is an
    // arith error — the quotes preserved a token that arith parses to
    // empty. Detect by comparing cleaned-vs-raw length: a vanished
    // character means quotes were present.
    if (cleaned.isBlank() && !sub.isBlank() && cleaned.length != sub.length) {
        pendingDiagnostics +=
            "$sub: arithmetic syntax error: operand expected (error token is \"$sub\")"
        commandAbortFlag[0] = true
        return null
    }
    if (cleaned.toIntOrNull() != null) return cleaned
    val idx =
        try {
            ArithEval(cleaned.ifBlank { "0" }, env).evaluate().toInt()
        } catch (_: Throwable) {
            return sub
        }
    return idx.toString()
}

internal fun Expander.subscriptValue(
    name: String,
    sub: String?,
    // True when [sub] came from expanding a `$`-bearing subscript, so an
    // `@`/`*` value is a literal assoc KEY, not the spread sentinel. The
    // single-value caller only reaches here when the part wasn't a spread.
    literalKey: Boolean = false,
): String {
    // Array path takes priority over scalar — bash variables aren't both.
    indexedArrays[name]?.let { arr ->
        return when (sub) {
            null -> {
                arr[0] ?: ""
            }

            "@", "*" -> {
                arr.entries.sortedBy { it.key }.joinToString(firstIfsChar()) { it.value }
            }

            else -> {
                // array_expand_once security baseline: a `\$(...)`
                // surviving in the subscript text is data that must NOT
                // be re-evaluated (running the cmdsub via arith preexpand
                // would execute shell code the user supplied as the key).
                if ("\$(" in sub) {
                    pendingDiagnostics +=
                        "$sub: arithmetic syntax error: operand expected (error token is \"$sub\")"
                    commandAbortFlag[0] = true
                    return ""
                }
                // bash evaluates indexed-array subscripts as arithmetic
                // (so `${A[i+1]}` and `${A[NDIRS-2]}` work). Fall back to
                // empty on eval error, matching `${A[bogus]}` behavior.
                // Parameter expansions in the subscript (`${A["$idx"]}`)
                // are handled by the calling expander before we reach
                // here, but quotes and bare `$` references must be
                // stripped so ArithEval sees a clean expression.
                // `$(( … ))` arithmetic-substitution in the subscript
                // text (`${A[$(( 0 ))]}`) is also unwrapped here so the
                // inner expression reaches ArithEval directly.
                val cleaned =
                    unwrapArithSubst(sub.filter { it != '"' && it != '\'' })
                // Bash quirk: an UNQUOTED blank subscript (`a[ ]`) is
                // arith-zero, but a QUOTED blank (`a[' ']` or `a["$v"]`
                // where $v is whitespace) is an arith error. Detect by
                // comparing pre/post quote-strip: if the raw text had
                // non-blank content (quotes) that vanished, that's the
                // arith-error shape.
                if (cleaned.isBlank() && !sub.isBlank() && cleaned.length != sub.length) {
                    pendingDiagnostics +=
                        "$sub: arithmetic syntax error: operand expected (error token is \"$sub\")"
                    return ""
                }
                val idx =
                    cleaned.toIntOrNull() ?: try {
                        // Blank subscript (`b[   ]`) is bash arithmetic for 0.
                        ArithEval(cleaned.ifBlank { "0" }, env).evaluate().toInt()
                    } catch (_: Throwable) {
                        // bash surfaces an arith-syntax-error diagnostic
                        // for subscripts that look like incomplete arith
                        // (e.g. `\${b[~]}` — bare bitwise-NOT operator).
                        // Detect by scanning the cleaned text: a token
                        // composed purely of arith-operator characters
                        // is the failure shape we want to surface; bare
                        // identifiers / brace patterns / mixed text
                        // stay silent as before (bash treats them as 0).
                        val operatorChars = "+-*/%~!&|^<>="
                        val opsOnly =
                            cleaned.isNotEmpty() &&
                                cleaned.all { it == ' ' || it == '\t' || it in operatorChars }
                        if (opsOnly) {
                            pendingDiagnostics +=
                                "$cleaned: arithmetic syntax error: operand expected (error token is \"$cleaned\")"
                            commandAbortFlag[0] = true
                        }
                        return ""
                    }
                // Negative indices count from the end (bash semantics).
                // When the translation goes below zero, bash emits
                // `name: bad array subscript` and the expansion is empty.
                val effIdx =
                    if (idx < 0) {
                        val maxKey = arr.keys.maxOrNull()
                        if (maxKey == null) {
                            lastBadSubscript += sub
                            return ""
                        }
                        val translated = maxKey + 1 + idx
                        if (translated < 0) {
                            lastBadSubscript += sub
                            return ""
                        }
                        translated
                    } else {
                        idx
                    }
                arr[effIdx] ?: ""
            }
        }
    }
    assocArrays[name]?.let { arr ->
        return when (sub) {
            null -> {
                arr["0"] ?: ""
            }

            "@", "*" -> {
                if (literalKey) {
                    arr[sub] ?: ""
                } else {
                    orderAssocKeys(name, arr.keys).joinToString(firstIfsChar()) {
                        arr[it]
                            ?: ""
                    }
                }
            }

            else -> {
                arr[sub] ?: ""
            }
        }
    }
    if (sub == null) {
        // `$*`/`$@` aren't kept in the env table; their value is the joined
        // positional parameters. Bash's `${*-x}` / `${@-x}` and other default
        // forms read this joined value, so handle them here rather than
        // letting `lookup` fall through to `env["*"]` (which is always "").
        if (name == "*" || name == "@") {
            val sep = firstIfsChar()
            return positional.joinToString(sep)
        }
        return lookup(name)
    }
    val scalar = lookup(name)
    return when (sub) {
        "@", "*", "0" -> {
            scalar
        }

        else -> {
            // Scalar is exposed as a 1-element indexed array at index 0.
            // Subscript text can be an arithmetic expression (`$(( 0 ))`),
            // quoted ("0"), or a bare number — unify by stripping and
            // evaluating just like the indexed-array branch.
            val cleaned = unwrapArithSubst(sub.filter { it != '"' && it != '\'' })
            val idx =
                cleaned.toIntOrNull() ?: try {
                    ArithEval(cleaned.ifBlank { "0" }, env).evaluate().toInt()
                } catch (_: Throwable) {
                    return ""
                }
            // Bash: a negative subscript on a scalar (or unset variable)
            // translates to `len-1+idx`; with len ≤ 1 this is always
            // negative, so bash emits `name: bad array subscript`.
            if (idx < 0) {
                lastBadSubscript += sub
                return ""
            }
            if (idx == 0) scalar else ""
        }
    }
}

internal fun Expander.subscriptIsSet(
    name: String,
    sub: String?,
    // See [subscriptValue]: an expanded `@`/`*` is a literal assoc key.
    literalKey: Boolean = false,
): Boolean {
    if (sub == null) return isParameterSet(name)
    if (literalKey && (sub == "@" || sub == "*")) {
        return assocArrays[name]?.containsKey(sub) ?: false
    }
    // Strip quotes and unwrap `$(( … ))` so `${A["1"]}`-style and
    // `${A[$(( 0 ))]}`-style subscripts arith-evaluate cleanly.
    val cleanedSub = unwrapArithSubst(sub.filter { it != '"' && it != '\'' })
    indexedArrays[name]?.let { arr ->
        return when (sub) {
            "@", "*" -> {
                arr.isNotEmpty()
            }

            else -> {
                val idx =
                    cleanedSub.toIntOrNull() ?: try {
                        ArithEval(cleanedSub.ifBlank { "0" }, env).evaluate().toInt()
                    } catch (_: Throwable) {
                        return false
                    }
                idx in arr
            }
        }
    }
    assocArrays[name]?.let { arr ->
        return when (sub) {
            "@", "*" -> arr.isNotEmpty()
            else -> sub in arr
        }
    }
    if (!isParameterSet(name)) return false
    return when (sub) {
        "@", "*", "0" -> {
            true
        }

        else -> {
            // Scalar treated as a 1-element indexed array — sub must
            // arithmetic-evaluate to 0 to refer to that element.
            val idx =
                cleanedSub.toIntOrNull() ?: try {
                    ArithEval(cleanedSub.ifBlank { "0" }, env).evaluate().toInt()
                } catch (_: Throwable) {
                    return false
                }
            idx == 0
        }
    }
}

/**
 * Gather elements for a spread expansion. Caller decides what the spread
 * means contextually (e.g. `${A[@]}` straight vs `${A[@]^pat}` per-element
 * transform). Order matches bash:
 *   - positional in numeric order
 *   - indexed arrays in numeric-index order
 *   - assoc arrays in insertion order
 *   - scalars exposed as 1-element list
 */
internal fun Expander.spreadElements(
    name: String,
    sub: String?,
): List<String> {
    if (name == "@" || name == "*") return positional
    indexedArrays[name]?.let { arr ->
        return arr.entries.sortedBy { it.key }.map { it.value }
    }
    assocArrays[name]?.let { arr ->
        return orderAssocKeys(name, arr.keys).map { arr[it] ?: "" }
    }
    // Scalar fallback: a set scalar becomes a 1-element spread; unset is empty.
    val v = lookup(name)
    if (v.isNotEmpty() || env.containsKey(name)) return listOf(v)
    return emptyList()
}

// `@` / `*` are the array-spread sentinels ONLY when they appear literally
// in the source subscript. A subscript that EXPANDED to `@`/`*` (e.g.
// `key=@; ${a[$key]}`) is a literal string-key lookup, not a spread — bash
// distinguishes the two, so we require [rawSubscript] to be null (no
// pre-expansion happened) before treating `@`/`*` as a spread.
internal fun Expander.isSpreadSubscript(part: WordPart.ParameterExpansion): Boolean =
    (part.rawSubscript == null && (part.subscript == "@" || part.subscript == "*")) ||
        (part.subscript == null && (part.parameter == "@" || part.parameter == "*"))

internal fun Expander.isAtSpread(part: WordPart.ParameterExpansion): Boolean =
    (part.rawSubscript == null && part.subscript == "@") ||
        (part.subscript == null && part.parameter == "@")

internal fun Expander.emitSpread(
    groups: MutableList<MutableList<Fragment>>,
    values: List<String>,
    quoted: Boolean,
    atSpread: Boolean,
) {
    if (atSpread && quoted) {
        // `"${A[@]}"` and `"$@"` — each element is its own field, never split.
        if (values.isNotEmpty()) {
            groups.last() += Fragment(values.first(), quoted = true, splittable = false)
            for (i in 1 until values.size) {
                groups += mutableListOf(Fragment(values[i], quoted = true, splittable = false))
            }
        } else if (!inDefaultValueOperand) {
            // bash: a dq `"$@"`/`"${A[@]}"` that resolved to zero elements
            // absorbs the enclosing dq field — but only at the TOP level
            // of the word. Inside a default-value operand
            // (`"${foo:-$@}"`), the `${...}` is a single expansion that
            // produces the empty string in dq context, so the outer dq
            // still emits one empty field. The marker fragment blocks
            // post-split empty-anchor emission only for the top-level
            // adjacent-empty case (`"$xxx$@"` → no field).
            groups.last() += Fragment("", quoted = true, splittable = false, emptySpread = true)
        }
        return
    }
    val ifsValue = env["IFS"]
    val ifsIsNull = ifsValue != null && ifsValue.isEmpty()
    if (atSpread && !quoted) {
        // Bash treats `$@` inside a default-value operand differently
        // from a bare `$@`:
        //   - IFS empty/null  → preserve per-element boundaries.
        //   - IFS non-empty   → collapse to a single space-joined
        //                       string (does NOT re-split on IFS
        //                       chars).
        // This asymmetry is documented in bash man page §Parameter
        // Expansion (the "When in doubt..." paragraph on `$@` in word
        // contexts).
        if (inDefaultValueOperand && !ifsIsNull) {
            // Collapse to one space-joined field (no further IFS-split).
            groups.last() +=
                Fragment(values.joinToString(" "), quoted = false, splittable = false)
            return
        }
        // Direct `$@` / `${A[@]}` (and operand context with null IFS):
        // preserve per-element boundaries. Each element is its own field,
        // each field independently field-splittable on IFS.
        if (values.isNotEmpty()) {
            groups.last() += Fragment(values.first(), quoted = false, splittable = true)
            for (i in 1 until values.size) {
                groups += mutableListOf(Fragment(values[i], quoted = false, splittable = true))
            }
        }
        return
    }
    // `${A[*]}` / `$*` joining. POSIX §2.5.2: unset IFS → space;
    // empty IFS → empty (no separator); otherwise first char. So
    // `set -- ${foo=$*}` under `IFS=` yields the concatenation of all
    // positionals (e.g. "12" from `1` and `2`).
    //
    // BUT inside a default-value operand under IFS-null, bash treats
    // unquoted `$*` like `$@` (per-positional spread), not as a
    // joined string.
    if (!quoted && !atSpread && inDefaultValueOperand && ifsIsNull) {
        // `$*` under null IFS inside default-value operand → per-element.
        if (values.isNotEmpty()) {
            groups.last() += Fragment(values.first(), quoted = false, splittable = true)
            for (i in 1 until values.size) {
                groups += mutableListOf(Fragment(values[i], quoted = false, splittable = true))
            }
        }
        return
    }
    val sep = firstIfsChar()
    groups.last() += Fragment(values.joinToString(sep), quoted = quoted, splittable = !quoted)
}

/**
 * Render a `Word` operand back as source-like text for diagnostics that
 * quote the original expression. Walks `ExpandedText.rawSource` (populated
 * by `Interpreter.preprocessPart` for arith / cmdsub) and falls back to
 * literal-only words. Returns null when the word contains parts that have
 * no faithful source rendering (e.g. parameter expansions, double-quoted
 * regions) — diagnostic call sites fall back to the expanded value in
 * that case.
 */
internal fun renderArgRawSource(word: Word?): String? {
    if (word == null) return null
    val sb = StringBuilder()
    for (part in word.parts) {
        when (part) {
            is WordPart.Literal -> sb.append(part.value)
            is WordPart.SingleQuoted -> sb.append('\'').append(part.value).append('\'')
            is WordPart.Escaped -> sb.append('\\').append(part.value)
            is WordPart.ExpandedText -> sb.append(part.rawSource ?: return null)
            else -> return null
        }
    }
    val out = sb.toString().trim()
    return out.ifEmpty { null }
}

/**
 * Bash brace-range expansion (`{N..M}` / `{N..M..S}`) applied to a
 * subscript text. Returns the list of expanded subscript strings, or
 * `null` if the text doesn't match a single brace-range form. Stripping
 * surrounding quote chars first because bash's subscript context strips
 * `"..."` / `'...'` from numeric subscripts before resolution.
 *
 * Only the numeric-range form is handled — the comma-list form (`{a,b}`)
 * inside subscripts would need a string-level brace expander.
 */
internal fun expandBraceRangeSubscript(sub: String): List<String>? {
    val stripped = sub.filter { it != '"' && it != '\'' }
    val open = stripped.indexOf('{')
    val close = stripped.lastIndexOf('}')
    if (open < 0 || close < open) return null
    val prefix = stripped.substring(0, open)
    val body = stripped.substring(open + 1, close)
    val suffix = stripped.substring(close + 1)
    val dotdot = body.indexOf("..")
    if (dotdot < 0) return null
    val a = body.substring(0, dotdot).toIntOrNull() ?: return null
    val rest = body.substring(dotdot + 2)
    val secondDot = rest.indexOf("..")
    val (b, step) =
        if (secondDot < 0) {
            (rest.toIntOrNull() ?: return null) to 1
        } else {
            val bv = rest.substring(0, secondDot).toIntOrNull() ?: return null
            val sv = rest.substring(secondDot + 2).toIntOrNull() ?: return null
            bv to
                sv.let {
                    if (it == 0) {
                        return null
                    } else if (it < 0) {
                        -it
                    } else {
                        it
                    }
                }
        }
    val out = ArrayList<String>()
    if (a <= b) {
        var n = a
        while (n <= b) {
            out += "$prefix$n$suffix"
            n += step
        }
    } else {
        var n = a
        while (n >= b) {
            out += "$prefix$n$suffix"
            n -= step
        }
    }
    return out
}
