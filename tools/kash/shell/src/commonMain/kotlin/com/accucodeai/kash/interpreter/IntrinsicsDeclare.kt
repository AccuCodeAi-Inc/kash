package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.InlineAssignment
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.Parser

/**
 * Re-parse `value` (whose form is `(elements)`) as an array literal under
 * the current alias / posix / extglob state, then apply it as a real
 * indexed-array assignment to [name]. Used by the declare-builtin lane
 * when the structured assignment ended up as Scalar/Indexed but the
 * value's text matches an array-literal shape — bash's "`-a` reparses
 * quoted parens" behavior. Falls back to literal storage if the
 * synthesized parse fails (defensive — shouldn't happen for valid input).
 */
internal suspend fun Interpreter.reparseAndApplyArrayLiteral(
    name: String,
    value: String,
    append: Boolean,
): Boolean {
    val synthetic = "__kash_reparse_lhs__=$value"
    val script =
        try {
            Parser(synthetic, aliasResolver, posixMode = posixModeRuntime).parseScript()
        } catch (_: ParseException) {
            return false
        }
    val stmt = script.statements.firstOrNull() ?: return false
    val cmd =
        stmt.pipelines
            .firstOrNull()
            ?.commands
            ?.firstOrNull() as? SimpleCommand
            ?: return false
    val arr =
        cmd.assignments.firstOrNull() as? InlineAssignment.Array ?: return false
    applyBareAssignment(
        InlineAssignment.Array(
            name = name,
            elements = arr.elements,
            append = append,
        ),
    )
    // `declare -a/-A NAME=(...)` (or quoted `NAME="(...)"`) is an ASSIGNMENT,
    // so the variable is no longer attribute-only — an empty literal must
    // still print with `=()` (`declare -A e=()`), unlike a bare `declare -A e`
    // declaration. Verified bash 5.3.
    varTable.find(name)?.attributeOnly = false
    return true
}

/**
 * Bash refuses to convert an existing array between indexed and associative.
 * Returns the human-readable direction (`"indexed to associative"` /
 * `"associative to indexed"`) when [newAttrs] would flip [varName]'s current
 * kind, else null. Callers emit their own (invocation-shape-specific)
 * `NAME: cannot convert <dir> array` diagnostic and skip the assignment.
 */
internal fun Interpreter.arrayKindConversion(
    varName: String,
    newAttrs: Set<VarAttr>,
): String? {
    val existing = varTable.find(varName) ?: return null
    return when {
        existing.isAssoc && VarAttr.Indexed in newAttrs -> "associative to indexed"
        existing.isIndexed && VarAttr.Associative in newAttrs -> "indexed to associative"
        else -> null
    }
}

// Variable intrinsics (set/shift/unset/export/declare) + declare-p formatting.

internal suspend fun Interpreter.runSetIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty()) {
        // POSIX `set` (no args): dump all variables sorted, name=value form
        // with bash-style single-quoting. Used by scripts that grep `set`
        // output (e.g. posix2.tests's `set | sed -n 's:^VAR=::p'`).
        val names = (env.keys + varTable.visibleNames()).distinct().sorted()
        val sb = StringBuilder()
        for (n in names) {
            val v = varTable.find(n)
            when {
                v?.isAssoc == true -> {
                    val arr = v.assocOrNull.orEmpty()
                    // Bash's `set` output for arrays uses the same
                    // double-quote `[k]="v"` format as `declare -p`
                    // (with a trailing space inside the closing paren).
                    // Verified bash 5.3 directly.
                    val entries =
                        orderAssocKeys(n, arr.keys).joinToString(" ") { k ->
                            "[${quoteAssocKey(k)}]=${quoteDeclareValue(arr[k] ?: "")}"
                        }
                    sb
                        .append(n)
                        .append("=(")
                        .append(entries)
                        .append(if (arr.isEmpty()) ")\n" else " )\n")
                }

                v?.isIndexed == true -> {
                    val arr = v.indexedOrNull.orEmpty()
                    val entries =
                        arr.entries.sortedBy { it.key }.joinToString(" ") { (k, value) ->
                            "[$k]=${quoteDeclareValue(value)}"
                        }
                    sb
                        .append(n)
                        .append("=(")
                        .append(entries)
                        .append(")\n")
                }

                else -> {
                    sb
                        .append(n)
                        .append('=')
                        .append(shellSingleQuote(env[n] ?: ""))
                        .append('\n')
                }
            }
        }
        stdio.stdout.writeUtf8(sb.toString())
        return 0
    }
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "--") {
            positional = args.drop(i + 1)
            return 0
        }
        if ((a == "-o" || a == "+o") && i + 1 < args.size) {
            val on = a == "-o"
            val name = args[i + 1]
            // Restricted shell: `set +o restricted` (and `+r`) is forbidden
            // once `set -r` is in effect. Bash also forbids it before -r is
            // set ("invalid option name") because it cannot be unset.
            if (name == "restricted") {
                if (on) {
                    enableRestricted()
                } else {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}set: restricted: invalid option name\n")
                    return 1
                }
                i += 2
                continue
            }
            when (name) {
                "posix" -> {
                    posixModeRuntime = on
                }

                "nounset" -> {
                    nounset = on
                }

                "pipefail" -> {
                    pipefail = on
                }

                "errexit" -> {
                    errexit = on
                }

                "noclobber" -> {
                    noclobber = on
                }

                "xtrace" -> {
                    xtrace = on
                }

                "monitor" -> {
                    monitor = on
                }

                "errtrace" -> {
                    errtrace = on
                }

                "functrace" -> {
                    functrace = on
                }

                "emacs" -> {
                    options.emacsMode = on
                    if (on) options.viMode = false
                }

                "vi" -> {
                    options.viMode = on
                    if (on) options.emacsMode = false
                }
            }
            i += 2
            continue
        }
        if (a.startsWith("-") || a.startsWith("+")) {
            // POSIX `set [-+]<flag>` short-flag toggle. Each letter
            // maps to one shell option (see [ShellOptions]). Flags
            // we don't implement emit a one-time stderr warning so
            // users know the request was dropped — matches the
            // OVERFITPASS discipline of failing loudly rather than
            // silently no-op'ing.
            val on = a.startsWith("-")
            for (j in 1 until a.length) {
                when (a[j]) {
                    'B' -> {
                        shoptOptions["braceexpand"] = on
                    }

                    'u' -> {
                        nounset = on
                    }

                    'e' -> {
                        errexit = on
                    }

                    'C' -> {
                        noclobber = on
                    }

                    'x' -> {
                        xtrace = on
                    }

                    'm' -> {
                        monitor = on
                    }

                    'E' -> {
                        // `set -E` / `set +E` — errtrace. Causes the
                        // ERR trap to be inherited by shell functions,
                        // command substitutions, and subshells.
                        errtrace = on
                    }

                    'T' -> {
                        // `set -T` / `set +T` — functrace. Causes the
                        // DEBUG and RETURN traps to be inherited.
                        functrace = on
                    }

                    'a' -> {
                        // POSIX §2.14.1: `set -a` (allexport).
                        allexport = on
                    }

                    'f' -> {
                        // POSIX §2.14.1: `set -f` (noglob).
                        noglob = on
                    }

                    'v' -> {
                        // POSIX §2.14.1: `set -v` (verbose).
                        verbose = on
                    }

                    'n' -> {
                        // POSIX §2.14.1: `set -n` (noexec).
                        noexec = on
                    }

                    'r' -> {
                        if (on) {
                            enableRestricted()
                        } else {
                            // set +r is illegal — restricted is sticky.
                            stdio.stderr.writeUtf8("${shellDiagPrefix()}set: +r: invalid option\n")
                            stdio.stderr.writeUtf8(
                                "set: usage: set [-abefhkmnptuvxBCEHPT] [-o option-name] [--] [-] [arg ...]\n",
                            )
                            return 2
                        }
                    }

                    'h', 'H' -> {
                        // `set -h` (hashall) is always-on in kash.
                        // `set -H` (histexpand) is a no-op outside
                        // interactive shells per bash man page. Both
                        // accept silently — matches bash semantics
                        // exactly for the non-interactive case.
                    }

                    'b', 'k', 'p', 't' -> {
                        // POSIX §2.14.1 documents these; kash doesn't
                        // implement them (job-control notify, command-
                        // style-assignment, priv mode, exit-after-one-
                        // cmd). Warn so scripts depending on them
                        // aren't silently mis-behaving.
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}set: -${a[j]}: unsupported flag (ignored)\n",
                        )
                    }
                }
            }
            i++
            continue
        }
        positional = args.drop(i)
        return 0
    }
    return 0
}

/**
 * Bash-style single-quoting used by `set` output. Outputs the value raw if
 * it contains only "safe" chars (alphanumerics + a small punctuation set,
 * with `#` and `~` *not* at position 0). Otherwise wraps in `'…'` with
 * embedded `'` written as `'\''`, then strips empty leading/trailing `''`
 * so that a value of just `'` collapses to `\'` (matching the bash test
 * expectation for `SQUOTE="'"`).
 */
internal fun shellSingleQuote(v: String): String {
    if (v.isEmpty()) return "''"
    if (!needsShellQuoting(v)) return v
    val sb = StringBuilder("'")
    for (c in v) {
        if (c == '\'') {
            sb.append("'\\''")
        } else {
            sb.append(c)
        }
    }
    sb.append('\'')
    var s = sb.toString()
    while (s.startsWith("''")) s = s.substring(2)
    while (s.endsWith("''")) s = s.substring(0, s.length - 2)
    return s.ifEmpty { "''" }
}

private fun needsShellQuoting(v: String): Boolean {
    if (v.isEmpty()) return true
    val first = v[0]
    // `~` and `#` need quoting only when they start the value (tilde-expansion
    // and comment-introducer respectively). Mid-word they're harmless.
    if (first == '~' || first == '#') return true
    for (c in v) {
        if (c.isLetterOrDigit()) continue
        if (c in "_-+=.,/:#") continue
        return true
    }
    return false
}

internal fun Interpreter.enableRestricted() {
    restricted = true
    // Bash makes PATH, SHELL, ENV, BASH_ENV readonly when entering rbash.
    for (name in listOf("PATH", "SHELL", "ENV", "BASH_ENV")) {
        varTable.findOrCreate(name).attrs += VarAttr.Readonly
    }
}

internal suspend fun Interpreter.runShiftIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val n = args.firstOrNull()?.toIntOrNull() ?: 1
    if (n < 0) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}shift: $n: shift count out of range\n")
        return 1
    }
    if (n > positional.size) return 1
    positional = positional.drop(n)
    return 0
}

internal suspend fun Interpreter.runUnsetIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var unsetFunctions = false
    var unsetNamerefOnly = false
    var sawDoubleDash = false
    for (a in args) {
        if (!sawDoubleDash) {
            if (a == "--") {
                sawDoubleDash = true
                continue
            }
            if (a == "-f") {
                unsetFunctions = true
                continue
            }
            if (a == "-v") {
                unsetFunctions = false
                continue
            }
            if (a == "-n") {
                // `unset -n NAME` removes the nameref *binding* without
                // following the chain — distinct from `unset NAME` which
                // would unset the *target*. Bash matches this semantics.
                unsetNamerefOnly = true
                continue
            }
            // POSIX/bash: unknown short flag — diagnose with usage and exit 2.
            if (a.startsWith("-") && a.length > 1 && a != "-") {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}unset: $a: invalid option\n" +
                        "unset: usage: unset [-f] [-v] [-n] [name ...]\n",
                )
                return 2
            }
        }
        if (unsetFunctions) {
            functions.remove(a)
        } else {
            // `unset NAME[sub]` — bash treats this as "remove the element
            // with subscript [sub] from array NAME". `unset NAME[@]` /
            // `unset NAME[*]` clears all elements (but leaves the array
            // attribute in place, the same way `NAME=()` does).
            val lb = a.indexOf('[')
            val rb = a.lastIndexOf(']')
            if (lb > 0 && rb == a.length - 1) {
                val name = a.substring(0, lb)
                val sub = a.substring(lb + 1, rb)
                // Readonly array: `unset NAME[sub]` refused with the
                // same `cannot unset: readonly variable` diagnostic
                // as whole-name `unset NAME`. Verified bash 5.3.
                if (varTable.find(name)?.isReadonly == true) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}unset: $name: cannot unset: readonly variable\n",
                    )
                    lastExit = 1
                    continue
                }
                // array_expand_once security baseline: refuse to
                // re-evaluate a `\$(...)` substring in the subscript.
                // bash only emits the diagnostic when the named array
                // actually exists — if NAME is unset, `unset NAME[sub]`
                // is a silent no-op even with a cmdsub-bearing sub.
                if ("\$(" in sub) {
                    val nameExists =
                        indexedArrays[name] != null || assocArrays[name] != null
                    if (nameExists) {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                        )
                        lastExit = 1
                    }
                    continue
                }
                indexedArrays[name]?.let { arr ->
                    when (sub) {
                        "@", "*" -> {
                            arr.clear()
                        }

                        else -> {
                            val rawIdx =
                                sub.toIntOrNull() ?: try {
                                    ArithEval(
                                        sub.ifBlank { "0" },
                                        env,
                                        arithStore,
                                        nounset = nounset,
                                    ).evaluate().toInt()
                                } catch (_: Throwable) {
                                    null
                                }
                            if (rawIdx != null) {
                                // Bash: negative subscript on unset translates
                                // against the current max key; only `[-N]: bad
                                // array subscript` (note: no NAME prefix in the
                                // unset diagnostic) when it drops below zero.
                                val idx =
                                    if (rawIdx < 0) {
                                        val maxKey = arr.keys.maxOrNull()
                                        val t = if (maxKey != null) maxKey + 1 + rawIdx else rawIdx
                                        if (t < 0) {
                                            stdio.stderr.writeUtf8(
                                                "${shellDiagPrefix()}unset: [$rawIdx]: bad array subscript\n",
                                            )
                                            lastExit = 1
                                            continue
                                        }
                                        t
                                    } else {
                                        rawIdx
                                    }
                                arr.remove(idx)
                            }
                        }
                    }
                    continue
                }
                assocArrays[name]?.let { arr ->
                    // On an associative array `@`/`*` are LITERAL keys, not the
                    // whole-array sentinel they are for indexed arrays — so
                    // `unset 'A[@]'` removes the `@` key (a no-op when absent),
                    // it does NOT clear the array. Verified bash 5.3. (Only
                    // `unset A` or `A=()` clears.)
                    //
                    // bash gates the subscript expansion on `assoc_expand_once`:
                    // OFF (default) re-expands the subscript once more (so
                    // `unset 'a[$x]'` removes the key named by x's value); ON
                    // uses the subscript text as-is, so the literal `$x` key is
                    // removed. Verified bash 5.3 (assoc9.sub: `unset
                    // 'assoc[$var]'` only clears the literal `$var` key once the
                    // shopt is set).
                    val key =
                        if (shoptOptions["assoc_expand_once"] == true) {
                            sub
                        } else {
                            expandUnsetAssocKey(sub)
                        }
                    arr.remove(key)
                    continue
                }
                // Subscripted unset on a non-array (typically a scalar):
                // bash treats `scalar[0]` as the scalar itself, so
                // `unset scalar[0]` silently unsets the scalar. Any
                // other subscript on a scalar emits "unset: NAME: not
                // an array variable" and skips. Only fire when NAME
                // exists; an unset NAME is silently a no-op for both
                // `unset NAME` and `unset NAME[sub]`.
                if (varTable.find(name)?.isSet == true) {
                    val subInt =
                        sub.toIntOrNull() ?: try {
                            ArithEval(
                                sub.ifBlank { "0" },
                                env,
                                arithStore,
                                nounset = nounset,
                            ).evaluate().toInt()
                        } catch (_: Throwable) {
                            null
                        }
                    if (subInt == 0) {
                        env.remove(name)
                        indexedArrays.remove(name)
                        assocArrays.remove(name)
                        varTable.unset(name)
                        continue
                    } else {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}unset: $name: not an array variable\n",
                        )
                        lastExit = 1
                        continue
                    }
                }
                // No matching array — fall through and unset by full name.
            }
            // Bash refuses to unset a readonly variable — both the
            // whole `unset NAME` form and the element-wise
            // `unset NAME[sub]` form (handled above for arrays) error
            // with `unset: NAME: cannot unset: readonly variable` and
            // return exit 1 without touching the storage. Verified
            // against bash 5.3 directly.
            if (varTable.find(a)?.isReadonly == true) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}unset: $a: cannot unset: readonly variable\n",
                )
                lastExit = 1
                continue
            }
            // Bash nameref semantics: `unset NAME` on a nameref unsets
            // the *target* (the var the nameref points at), NOT the
            // nameref itself. `unset -n NAME` removes the binding by
            // name instead, leaving the target alone.
            val nameToUnset =
                if (unsetNamerefOnly) {
                    a
                } else {
                    val raw = varTable.find(a)
                    if (raw != null && VarAttr.NameRef in raw.attrs) {
                        // Follow ONE level — bash's `unset` doesn't deep-
                        // chase nameref chains; it unsets the directly-
                        // pointed target.
                        raw.scalarOrNull ?: a
                    } else {
                        a
                    }
                }
            // Bash's `unset NAME` clears the variable *and* its attributes,
            // so a later `NAME=...` reads as a fresh scalar (not the integer
            // / case-mod / array form that `typeset` may have set).
            env.remove(nameToUnset)
            indexedArrays.remove(nameToUnset)
            assocArrays.remove(nameToUnset)
            varTable.unset(nameToUnset)
        }
    }
    return 0
}

/**
 * Expand a deferred subscript from an `unset` array-element form to its
 * literal assoc key. The whole arg is typically single-quoted (e.g. the
 * subscript text is a bare `$x`) so the shell left it unexpanded; bash's
 * unset then expands the subscript itself (parameter/arith/quote-removal,
 * no split/glob) before removing the element. We mirror the assignment
 * path (`expandAssignmentValue`, which is what created the key) by parsing
 * the subscript as a command word. Only dollar/backtick-bearing text is
 * expanded — a plain literal key (including shell metacharacters) is
 * removed verbatim, matching the old behavior and avoiding a re-parse of
 * metacharacter-laden literal keys. A literal command substitution opener
 * is already rejected by the array_expand_once check before we reach here.
 */
internal suspend fun Interpreter.expandUnsetAssocKey(sub: String): String {
    if (sub.indexOf('$') < 0 && sub.indexOf('`') < 0) return sub
    return try {
        val word =
            Parser("echo $sub", aliasResolver, posixMode = posixModeRuntime)
                .parseScript()
                .statements
                .firstOrNull()
                ?.pipelines
                ?.firstOrNull()
                ?.commands
                ?.firstOrNull()
                ?.let { it as? SimpleCommand }
                ?.args
                ?.firstOrNull()
        if (word != null) expandAssignmentValue(word) else sub
    } catch (_: Throwable) {
        sub
    }
}

internal suspend fun Interpreter.runExportIntrinsic(args: List<String>): Int {
    for (a in args) {
        if (a.startsWith("-")) continue
        val parsed = parseAssignmentOperand(a) ?: continue
        setScalar(parsed.name, parsed.value, parsed.append)
    }
    return 0
}

/**
 * Split a `NAME=VALUE` / `NAME+=VALUE` operand from `declare`, `export`,
 * `readonly`, etc. Returns null for bare names. The `=` matched is the
 * first one in the string (so `a=b=c` is name=`a`, value=`b=c`).
 */
internal data class AssignmentOperand(
    val name: String,
    val value: String,
    val append: Boolean,
)

internal suspend fun Interpreter.parseAssignmentOperand(operand: String): AssignmentOperand? {
    val eq = operand.indexOf('=')
    if (eq < 0) return null
    val append = eq > 0 && operand[eq - 1] == '+'
    val nameEnd = if (append) eq - 1 else eq
    if (nameEnd == 0) return null
    return AssignmentOperand(operand.substring(0, nameEnd), operand.substring(eq + 1), append)
}

internal suspend fun Interpreter.runDeclareIntrinsic(
    args: List<String>,
    forceLocal: Boolean,
    stdio: Stdio,
    /**
     * When true, the assignment(s) target the GLOBAL scope regardless of
     * whether we're called from inside a function. This matches bash:
     * `readonly` and `export` (the standalone builtins) always operate on
     * the global var table — only `local`/`declare`/`typeset` honor the
     * "in-function ⇒ local" rule.
     */
    forceGlobal: Boolean = false,
    /**
     * Builtin name to use as a prefix in error diagnostics. Bash emits
     * `readonly: NAME: readonly variable` when the violation surfaces
     * through the `readonly`/`export` builtin, vs. the bare
     * `NAME: readonly variable` when it's a regular bare assignment.
     */
    callerName: String? = null,
): Int {
    val attrs = mutableSetOf<VarAttr>()
    var readonly = false
    var global = false
    // `-ft NAME` marks a function for trap inheritance (DEBUG/RETURN/
    // ERR are visible inside its body). `+ft NAME` clears the mark.
    // We only act on the trace bit when `-f`/`-F` is also present —
    // that's the bash convention: `declare -ft` operates on functions,
    // not variables.
    var functionMode = false
    var functionNameOnly = false // -F: print names only, not bodies
    var traceSet = false // remembered to know whether `t` appeared
    var clearTrace = false // +ft / +t
    var printMode = false
    // Attrs to *remove* from the target binding — driven by `+` flags
    // that have a meaningful "clear" semantics. Currently just `+n`
    // (clear nameref). Most attrs ignore the `+` form; for those we
    // simply never add the corresponding entry here.
    val clearAttrs: MutableSet<VarAttr> = mutableSetOf()
    // Tracks whether the option string included `+a`/`+A` — used to fire
    // bash's "declare: NAME: readonly variable" diagnostic for readonly
    // arrays even though the attribute clear is itself silently rejected.
    var triedClearArray = false
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a == "--") {
            i++
            break
        }
        val isMinus = a.startsWith("-") && a.length >= 2
        val isPlus = a.startsWith("+") && a.length >= 2
        if (!isMinus && !isPlus) break
        for (ch in a.drop(1)) {
            when (ch) {
                'u' -> {
                    if (isPlus) clearAttrs += VarAttr.Upper else attrs += VarAttr.Upper
                }

                'l' -> {
                    if (isPlus) clearAttrs += VarAttr.Lower else attrs += VarAttr.Lower
                }

                'c' -> {
                    if (isPlus) clearAttrs += VarAttr.Capitalize else attrs += VarAttr.Capitalize
                }

                'i' -> {
                    if (isPlus) clearAttrs += VarAttr.Integer else attrs += VarAttr.Integer
                }

                'a' -> {
                    // bash silently rejects `+a` — you can't remove the
                    // indexed-array attribute from a variable once set.
                    // Track the attempt so the readonly-variable check can
                    // still fire (`declare +a NAME` on a readonly NAME is
                    // a "declare: NAME: readonly variable" error).
                    if (isPlus) triedClearArray = true else attrs += VarAttr.Indexed
                }

                'A' -> {
                    if (isPlus) triedClearArray = true else attrs += VarAttr.Associative
                }

                'x' -> {
                    attrs += VarAttr.Export
                }

                'r' -> {
                    readonly = true
                    attrs += VarAttr.Readonly
                }

                'g' -> {
                    global = true
                }

                'f' -> {
                    functionMode = true
                }

                'F' -> {
                    functionMode = true
                    functionNameOnly = true
                }

                't' -> {
                    if (isPlus) clearTrace = true else traceSet = true
                }

                'p' -> {
                    printMode = true
                }

                'n' -> {
                    // `-n` sets nameref; `+n` clears it. Track both so
                    // the operand loop can apply the right side.
                    if (isPlus) clearAttrs += VarAttr.NameRef else attrs += VarAttr.NameRef
                }

                else -> {
                    stdio.stderr.writeUtf8("declare: -$ch: invalid option\n")
                    return 2
                }
            }
        }
        i++
    }

    // `declare -ft NAME...` / `declare +ft NAME...` — function trace
    // flag toggle. Operands are function names; we accept names that
    // haven't been defined yet (bash does too — the flag is recorded
    // and applies when the function is later defined and called).
    if (functionMode && (traceSet || clearTrace)) {
        for (j in i until args.size) {
            val operand = args[j]
            if (clearTrace) tracedFunctions.remove(operand) else tracedFunctions += operand
        }
        return 0
    }

    // `declare -f NAME...` / `declare -F NAME...` / `declare -pf NAME...`
    // — print function definition(s). `export -f` shares the same -f flag
    // but means "mark for export", not "print"; gate on callerName.
    // Body printing goes through [AstPrinter.functionBody]; correctness
    // is verified by the round-trip tests in AstPrinterRoundTripTest
    // (parse → print → parse → print → byte-equal).
    if (functionMode && callerName == "readonly") {
        // `readonly -f NAME...` — mark functions read-only. With no
        // operands, bash lists every readonly function as
        // `declare -fr NAME` followed by the function body, matching
        // its declare -f listing format but filtered to the readonly
        // subset.
        var exit = 0
        if (i >= args.size) {
            // Bash's `readonly -f` listing format: function body first
            // (verbatim `declare -f` form) then `declare -fr NAME` to
            // record the readonly attribute.
            for (n in readonlyFunctions.sorted()) {
                val fn = functions[n] ?: continue
                stdio.stdout.writeUtf8(AstPrinter.functionBody(fn))
                stdio.stdout.writeUtf8("declare -fr $n\n")
            }
        } else {
            for (n in args.drop(i)) {
                if (n !in functions) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}readonly: $n: not found\n")
                    exit = 1
                    continue
                }
                readonlyFunctions += n
            }
        }
        return exit
    }

    if (functionMode && callerName != "export") {
        // `-x` filters the listing to exported functions only. With no
        // operands and `-x`, bash prints only exported names; with
        // operands and `-x`, it still prints the named functions (the
        // filter only applies to "list everything" mode).
        val filterExported = VarAttr.Export in attrs
        val names: List<String> =
            if (i >= args.size) {
                val all = functions.keys.sorted()
                if (filterExported) all.filter { it in exportedFunctions } else all
            } else {
                args.drop(i)
            }
        var exit = 0
        for (n in names) {
            val fn = functions[n]
            if (fn == null) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}declare: $n: not found\n")
                exit = 1
            } else if (functionNameOnly) {
                // `declare -F NAME`: just the name. `declare -F` (no
                // operands): `declare -f NAME` per function (bash form).
                if (i >= args.size) {
                    stdio.stdout.writeUtf8("declare -f $n\n")
                } else {
                    stdio.stdout.writeUtf8("$n\n")
                }
            } else {
                stdio.stdout.writeUtf8(AstPrinter.functionBody(fn))
            }
        }
        return exit
    }

    // `export -f NAME...` — mark functions for export so subshells started
    // via `sh -c` / `${THIS_SH} -c` see them. The fork-and-exec path in
    // runShellScript copies these functions across before clearing.
    if (functionMode && callerName == "export") {
        var exit = 0
        for (n in args.drop(i)) {
            // Bash rejects function names that can't be encoded as a
            // BASH_FUNC_NAME%% env var — `=` is the env name/value
            // separator and `/` is the path separator. Hyphens etc.
            // are fine — `foo-a` exports cleanly. This is the
            // CVE-2014-6271 (shellshock) post-fix discipline.
            if ('=' in n || '/' in n) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}export: $n: cannot export\n")
                exit = 1
                continue
            }
            if (n !in functions) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}export: $n: not a function\n")
                exit = 1
                continue
            }
            exportedFunctions += n
        }
        return exit
    }

    // `declare -p [NAME...]` — print declarations. Bash format:
    //   declare -- NAME="value"           # scalar
    //   declare -a NAME=([0]="v0" [1]="v1")
    //   declare -A NAME=([key]="v")
    // Attribute letters between `--`/`-a`/`-A` and NAME stack (e.g.
    // `declare -ari NAME=...`). Without operands, dump every defined
    // variable in alphabetical order.
    if (printMode) {
        val names: List<String> =
            if (i >= args.size) {
                // Bash filters by attribute when no operands are given:
                // `declare -pa` shows only indexed arrays, `declare -pA`
                // only associative arrays, etc. Without any attr, dump
                // every defined variable. Synthesize the dynamic
                // bash-builtin arrays so the filtered output matches.
                val synthArrays =
                    listOf("BASH_ARGC", "BASH_ARGV", "BASH_LINENO", "BASH_SOURCE", "DIRSTACK", "FUNCNAME")
                val all =
                    (env.keys + indexedArrays.keys + assocArrays.keys + varTable.visibleNames() + synthArrays)
                        .distinct()
                        .sorted()
                if (attrs.isEmpty()) all else filterDeclareList(all, attrs)
            } else {
                args.drop(i)
            }
        var exit = 0
        for (n in names) {
            val line = formatDeclareP(n)
            if (line == null) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}declare: $n: not found\n")
                exit = 1
            } else {
                stdio.stdout.writeUtf8(line + "\n")
            }
        }
        return exit
    }
    val inFunction = varTable.scopeDepth > 0
    val makeLocal = !forceGlobal && (forceLocal || (inFunction && !global))
    val hasArrayFlag = VarAttr.Indexed in attrs || VarAttr.Associative in attrs

    if (forceLocal && !inFunction) {
        stdio.stderr.writeUtf8("local: can only be used in a function\n")
        return 1
    }
    // `declare -a` / `declare -A` / `declare -ar` with no operands AND no
    // structured array assignments — bash lists every variable matching
    // the attribute flags (same shape as `declare -pa`). The structured
    // check matters because `typeset -A foo=(...)` arrives with the
    // foo=(...) assign in [currentArgAssignments] and an empty positional
    // args[] — don't shortcut to "list mode" then.
    if (i >= args.size &&
        (attrs.isNotEmpty() || readonly) &&
        currentArgAssignments.isEmpty()
    ) {
        val synthArrays =
            listOf("BASH_ARGC", "BASH_ARGV", "BASH_LINENO", "BASH_SOURCE", "DIRSTACK", "FUNCNAME")
        val all =
            (env.keys + indexedArrays.keys + assocArrays.keys + varTable.visibleNames() + synthArrays)
                .distinct()
                .sorted()
        val effAttrs = attrs + if (readonly) setOf(VarAttr.Readonly) else emptySet()
        // POSIX-mode `readonly -a` (and friends): bash prints
        // `readonly -a NAME=…` instead of `declare -ar NAME=…` so the
        // output is itself a valid POSIX-shell input. Outside posix
        // mode, even the `readonly` builtin prints `declare -ar`.
        val useReadonlyForm = callerName == "readonly" && posixModeRuntime
        for (n in filterDeclareList(all, effAttrs)) {
            val line = formatDeclareP(n, useReadonlyForm = useReadonlyForm) ?: continue
            stdio.stdout.writeUtf8(line + "\n")
        }
        return 0
    }
    // Structured array/indexed assignments arrived via [currentArgAssignments]
    // (the lexer recognized `NAME=(...)` past the cmd-name slot because we're
    // an assignment builtin). Apply them inline; their names are removed from
    // the string-arg loop below by tracking them in [structuredNames].
    val structuredNames = mutableSetOf<String>()
    for (a in currentArgAssignments) {
        val varName = a.name
        // array_expand_once security baseline: a bracketed subscript
        // whose expanded text contains literal `$(...)` (e.g.
        // `declare -i a["\$subscript"]=42` with $subscript itself
        // holding a `\$(...)` value) must NOT be re-evaluated.
        if (a is InlineAssignment.Indexed) {
            val subText = expandAssignmentValue(a.subscript)
            if ("\$(" in subText) {
                // Apply attribute flags before bailing so e.g.
                // `declare -i a["\$subscript"]=42` still leaves `a`
                // typed as integer even though the write itself is
                // refused — bash applies the attr regardless of the
                // value-write outcome.
                if (attrs.isNotEmpty()) declareAttrs(a.name, attrs)
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}$subText: arithmetic syntax error: operand expected (error token is \"$subText\")\n",
                )
                lastExit = 1
                structuredNames += a.name
                continue
            }
            // `declare A[\$k]=X` with k=`]` expands to a subscript text
            // of `]`, which means the resulting operand would be
            // `A[]]=X` — bash's quote-aware bracket parser closes on
            // the FIRST `]`, leaving the second `]` as garbage and
            // rejecting the operand entirely. We surface the same
            // diagnostic so the assignment is skipped.
            if (subText == "]") {
                val v = expandAssignmentValue(a.value)
                val maybeAppend = if (a.append) "+=" else "="
                val syntheticOperand = "${a.name}[$subText]$maybeAppend$v"
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}${callerName ?: "declare"}: `$syntheticOperand': not a valid identifier\n",
                )
                lastExit = 1
                structuredNames += a.name
                continue
            }
            // Bash rejects `declare NAME[SUB]=VAL` when the
            // post-quote-removal subscript has unbalanced brackets, but the
            // two shapes behave differently:
            //   - a premature CLOSE (`foo]bar`, depth drops below 0) is
            //     rejected ALWAYS — even with `assoc_expand_once` ON
            //     (`typeset foo["foo]bar"]=bax` → not a valid identifier).
            //   - an unclosed OPEN (`foo[bar`, depth ends > 0) is rejected
            //     only when the shopt is OFF; with it ON the subscript is
            //     parsed once and `foo[bar` is a valid literal assoc key, so
            //     the element is stored.
            // Diagnostic format matches bash 5.3:
            //   declare: `NAME[<subtext>]=VAL': not a valid identifier
            // Apply attribute flags but skip the write.
            run {
                var d = 0
                var prematureClose = false
                for (ch in subText) {
                    when (ch) {
                        '[' -> {
                            d++
                        }

                        ']' -> {
                            d--
                            if (d < 0) {
                                prematureClose = true
                                break
                            }
                        }
                    }
                }
                val unclosedOpen = d > 0
                val expandOnce = shoptOptions["assoc_expand_once"] == true
                val unbalanced = prematureClose || (unclosedOpen && !expandOnce)
                if (unbalanced) {
                    val v = expandAssignmentValue(a.value)
                    val maybeAppend = if (a.append) "+=" else "="
                    val syntheticOperand = "${a.name}[$subText]$maybeAppend$v"
                    if (attrs.isNotEmpty()) declareAttrs(a.name, attrs)
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}${callerName ?: "declare"}: `$syntheticOperand': not a valid identifier\n",
                    )
                    lastExit = 1
                    structuredNames += a.name
                    return@run
                }
            }
            if (a.name in structuredNames) continue
        }
        // `readonly -a r='(7)'`: lexer sees the unquoted `r=`, so it lands
        // here as InlineAssignment.Scalar with value `(7)`. Bash routes the
        // value through the builtin's positional-arg lane (quotes around
        // the parens block array-word recognition), where `-a`/`-A` then
        // parses the RHS as an array literal. Defer to the string-arg loop
        // — the AstBuilder already added a textual `r=(7)` fallback to args.
        if (hasArrayFlag && a is InlineAssignment.Scalar) {
            val v = expandAssignmentValue(a.value)
            if (v.startsWith("(") && v.endsWith(")") && v.length >= 2) {
                continue
            }
        }
        // `declare -a a; declare a='(1 2 3)'`: the second declare has no
        // `-a` flag, but `a` already carries Indexed/Assoc attrs from the
        // first call, so bash reparses the parenthesised RHS as an array
        // literal. Only the `declare`/`typeset` builtin does this; the
        // `readonly` builtin (and `export`) keep the quoted scalar — see
        // attr.tests `readonly 'c=(3)'` → `c=([0]="(3)")`.
        if (!hasArrayFlag && a is InlineAssignment.Scalar &&
            (callerName == "declare" || callerName == "typeset" || callerName == null)
        ) {
            val existing = varTable.find(a.name)
            val existingIsIndexed = existing?.isIndexed == true
            val existingIsAssoc = existing?.isAssoc == true
            val isReadonly = existing?.isReadonly == true
            if ((existingIsIndexed || existingIsAssoc) && !isReadonly) {
                val v = expandAssignmentValue(a.value)
                if (v.startsWith("(") && v.endsWith(")") && v.length >= 2) {
                    structuredNames += a.name
                    // Apply attribute clears and new attrs BEFORE the
                    // reparse so per-element case-mod (e.g. `-l`) and
                    // integer coercion fire on the new RHS.
                    if (clearAttrs.isNotEmpty()) {
                        varTable.find(a.name)?.attrs?.removeAll(clearAttrs)
                    }
                    if (attrs.isNotEmpty()) declareAttrs(a.name, attrs)
                    // Both indexed and associative re-lex the `(...)` body and
                    // delegate to the single bare-assignment array-literal path
                    // (which routes by the variable's attrs, preserving label
                    // vs literal-key, empty-key errors, and glob/split
                    // suppression). The var already carries its array attr here.
                    reparseAndApplyArrayLiteral(a.name, v, a.append)
                    continue
                }
            }
        }
        // `declare -a NAME[sub]='(elems)'` (or any quoting that makes the
        // value start with `(` and end with `)`): bash reparses the value as
        // an array literal under -a/-A and DROPS the subscript. e.g.
        // `declare -a e[10]='(test)'` → `e=([0]="test")`, not
        // `e[10]="(test)"`. The subscript is a sizing hint, ignored.
        if (hasArrayFlag && a is InlineAssignment.Indexed && VarAttr.Associative !in attrs) {
            val v = expandAssignmentValue(a.value)
            if (v.startsWith("(") && v.endsWith(")") && v.length >= 2) {
                structuredNames += a.name
                if (makeLocal) shadowLocal(a.name)
                if (attrs.isNotEmpty()) declareAttrs(a.name, attrs)
                val ok = reparseAndApplyArrayLiteral(a.name, v, a.append)
                if (!ok) {
                    // Defensive fallback: store the literal value.
                    setIndexedArrayLiteral(a.name, listOf(ArrayLiteralElem.Value(v)), a.append)
                }
                val arr = varTable.find(a.name)?.indexedOrNull
                if (arr != null && arr.isEmpty()) {
                    varTable.find(a.name)?.declaredEmptyViaBuiltin = true
                }
                if (readonly) varTable.findOrCreate(a.name).attrs += VarAttr.Readonly
                continue
            }
        }
        structuredNames += varName
        if (varTable.find(varName)?.isReadonly == true) {
            // Bash inserts the active function name as a middle prefix
            // when `readonly -a`/`export -a` surfaces a readonly
            // violation through the assignment-word lane, producing
            // `<file>: line N: <fn>: <var>: readonly variable`.
            // Without `-a`/`-A`, no function prefix.
            val fnPrefix =
                if (hasArrayFlag && functionNameStack.isNotEmpty()) {
                    "${functionNameStack.last()}: "
                } else {
                    ""
                }
            stdio.stderr.writeUtf8("${shellDiagPrefix()}$fnPrefix$varName: readonly variable\n")
            lastExit = 1
            continue
        }
        // Bash refuses to convert between indexed and associative arrays.
        // Emit the diagnostic and SKIP the value assignment so the
        // existing variable keeps its prior contents.
        val direction = arrayKindConversion(varName, attrs)
        if (direction != null) {
            val inFunction = functionNameStack.isNotEmpty()
            val fnPrefix =
                if (inFunction) "${functionNameStack.last()}: " else ""
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}$fnPrefix$varName: cannot convert $direction array\n",
            )
            // Bash emits a second line with the builtin-name prefix ONLY
            // inside a function (the inner-then-outer error pair). Outside
            // a function, just one line.
            if (inFunction) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}${callerName ?: "declare"}: $varName: cannot convert $direction array\n",
                )
            }
            lastExit = 1
            continue
        }
        if (makeLocal) shadowLocal(varName)
        // Apply attribute clears (`+i`, `+u`, `+l`, `+c`, `+n`) BEFORE
        // the value assignment so the cleared attribute doesn't run on
        // the new RHS (e.g. `declare +i arr=(hello world)` must NOT
        // arith-coerce "hello" to 0).
        if (clearAttrs.isNotEmpty()) {
            varTable.find(varName)?.let { v ->
                v.attrs.removeAll(clearAttrs)
            }
        }
        if (attrs.isNotEmpty()) declareAttrs(varName, attrs)
        // `declare a[N]='(elems)'`: a quoted-parens value sent to an
        // indexed-element assignment is bash's "quoted compound array
        // assignment" form — bash treats the parens literally (no
        // compound-expand) but emits a deprecation warning naming the
        // operand. We don't have the original quoting attached here so
        // we recover it from the assignment: if the value text starts
        // with `(` and ends with `)`, bash would have warned.
        if (a is InlineAssignment.Indexed) {
            val existingArr = varTable.find(varName)
            val alreadyArray = existingArr?.isIndexed == true || existingArr?.isAssoc == true
            if (!alreadyArray) {
                val v = expandAssignmentValue(a.value)
                if (v.startsWith("(") && v.endsWith(")") && v.length >= 2) {
                    val sub = expandAssignmentValue(a.subscript)
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}warning: $varName[$sub]=$v: quoted compound array assignment deprecated\n",
                    )
                }
            }
        }
        if (VarAttr.NameRef in attrs && a is InlineAssignment.Scalar) {
            val v = expandAssignmentValue(a.value)
            varTable.findOrCreate(varName).value = VariableValue.Scalar(v)
        } else {
            // applyBareAssignment continues past readonly violations on its own;
            // we've already filtered those above so the call here always writes.
            // Bash treats these as non-fatal even for the special builtin.
            applyBareAssignment(a)
            // bash's `${var@A}` omits `=()` for declare-builtin empty arrays.
            if (a is InlineAssignment.Array && a.elements.isEmpty()) {
                varTable.find(varName)?.declaredEmptyViaBuiltin = true
            }
        }
        if (readonly && varTable.find(varName)?.isReadonly != true) {
            varTable.findOrCreate(varName).attrs += VarAttr.Readonly
        }
    }
    // Pre-merge `NAME=(...)` operands split by word-splitting. When `$l`
    // expands unquoted to `( foo )`, bash sees `declare -a foo=$l` as the
    // args `["foo=(", "foo", ")"]` and recombines them into a single
    // `foo=(foo)` operand. Track paren depth: an operand ending with `=(`
    // (or having an unbalanced `(`) consumes following operands until
    // paren-balance is restored.
    val mergedArgs = mutableListOf<String>()
    var j = i
    while (j < args.size) {
        val operand = args[j]
        val openIdx = operand.indexOf("=(")
        var depth = 0
        if (openIdx >= 0) {
            for (k in openIdx + 1 until operand.length) {
                when (operand[k]) {
                    '(' -> depth++
                    ')' -> depth--
                }
            }
        }
        if (depth > 0) {
            val sb = StringBuilder(operand)
            j++
            while (j < args.size && depth > 0) {
                val next = args[j]
                sb.append(' ').append(next)
                for (c in next) {
                    when (c) {
                        '(' -> depth++
                        ')' -> depth--
                    }
                }
                j++
            }
            mergedArgs += sb.toString()
        } else {
            mergedArgs += operand
            j++
        }
    }
    for (operand in mergedArgs) {
        // AstBuilder emits `NAME[…]=value` as a textual word fallback for
        // IndexedAssignTok (`a[2]=x`) so non-builtin commands still see the
        // operand. Assignment builtins like declare have the structured
        // assignment in [currentArgAssignments] and must skip the fallback
        // here — otherwise it would create a phantom variable literally
        // named `NAME[…]`.
        if ("[…]=" in operand) continue
        // `declare -r []=asdf`: an operand whose "name" begins with `[`
        // has no identifier at all — bash rejects it as "not a valid
        // identifier" before any other parsing. The diagnostic quotes the
        // FULL operand (`[]=asdf`), not just the bracket portion.
        if (operand.startsWith("[")) {
            val bi = callerName ?: "declare"
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}$bi: `$operand': not a valid identifier\n",
            )
            lastExit = 1
            continue
        }
        // `declare A[]]=X`: bash's subscript parser closes on the FIRST
        // unquoted `]` after `[`, leaving the second `]` as trailing
        // garbage that breaks identifier validity. Reject if the first
        // `]` after `[` is followed by anything other than the optional
        // `+` of the `+=` append form before the assignment `=`.
        val opLb = operand.indexOf('[')
        val opFirstRb = if (opLb > 0) operand.indexOf(']', opLb + 1) else -1
        val opEq = operand.indexOf('=')
        val opNameEnd = if (opEq >= 0) opEq else operand.length
        if (opLb > 0 && opFirstRb > 0 && opFirstRb < opNameEnd - 1 &&
            !(opFirstRb == opNameEnd - 2 && operand[opNameEnd - 1] == '+')
        ) {
            val bi = callerName ?: "declare"
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}$bi: `$operand': not a valid identifier\n",
            )
            lastExit = 1
            continue
        }
        // Bash also rejects `declare NAME[SUB]=VAL` when SUB itself
        // contains an unbalanced `[` (e.g. `myarray["foo[bar"]=bleh`
        // — even though the inner `[` was quoted, bash's identifier
        // validator scans the post-quote-removal operand and sees
        // `myarray[foo[bar]=bleh` as unbalanced). Same shape for
        // trailing-garbage cases like `foo[foo]bar]=bax`. Verified
        // bash 5.3 directly.
        if (opLb > 0 && opEq > 0 && opEq > opLb) {
            // Find the matching `]` for the outermost `[` via depth
            // tracking. If we don't land exactly at `=` (modulo `+=`),
            // or the inner content has a stray `[`, the identifier is
            // invalid.
            var depth = 0
            var k = opLb
            var matchedClose = -1
            while (k < opNameEnd) {
                when (operand[k]) {
                    '[' -> {
                        depth++
                    }

                    ']' -> {
                        depth--
                        if (depth == 0) {
                            matchedClose = k
                            break
                        }
                    }
                }
                k++
            }
            // Either the bracket never closed before `=`, or the
            // close landed before `=`/`+=` with trailing junk.
            val expectedEnd =
                if (operand[opNameEnd - 1] == '+') opNameEnd - 2 else opNameEnd - 1
            if (matchedClose < 0 || matchedClose != expectedEnd) {
                val bi = callerName ?: "declare"
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}$bi: `$operand': not a valid identifier\n",
                )
                lastExit = 1
                continue
            }
        }
        val parsed = parseAssignmentOperand(operand)
        // `declare -a b[256]` / `declare -r c[100]`: bash ignores the
        // subscript for type-declaration purposes; the var is `b` / `c`,
        // not `b[256]`. Strip the bracket suffix when no `=` is present,
        // and ALSO add the Indexed attr — the subscript syntax implies
        // indexed-array typing even if `-a` wasn't explicitly given.
        val rawName = parsed?.name ?: operand
        val hadSubscript: Boolean
        val varName =
            if (parsed == null) {
                val lb = rawName.indexOf('[')
                val rb = rawName.lastIndexOf(']')
                if (lb > 0 && rb == rawName.length - 1) {
                    hadSubscript = true
                    rawName.substring(0, lb)
                } else {
                    hadSubscript = false
                    rawName
                }
            } else {
                hadSubscript = false
                rawName
            }
        val effAttrsForOp = if (hadSubscript) attrs + VarAttr.Indexed else attrs
        // `readonly NAME[sub]` is rejected by bash: subscript syntax names
        // a single array element, not a variable, so it's not a valid
        // identifier for the `readonly` builtin. (`declare -a NAME[sub]`
        // accepts and strips the subscript as a sizing hint; that path
        // above already handled it.)
        if (hadSubscript && callerName == "readonly" && parsed == null) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}readonly: `$rawName': not a valid identifier\n",
            )
            lastExit = 1
            continue
        }
        // The string-arg loop also sees the textual fallback emitted by the
        // AstBuilder for trailing `NAME=value` tokens (so non-builtin paths
        // still observe the operand). Skip names already handled structurally.
        if (varName in structuredNames) continue
        if (varTable.find(varName)?.isReadonly == true &&
            (parsed != null || clearAttrs.isNotEmpty() || triedClearArray)
        ) {
            // For the quoted/positional-arg lane, bash prefixes with the
            // builtin name (`readonly:` / `export:`) instead of the function
            // name, but ONLY when `-a`/`-A` is in effect. Attribute-clear
            // operations (`declare +a NAME`) also report through the
            // builtin name since the user explicitly invoked declare.
            val biPrefix =
                if ((hasArrayFlag || clearAttrs.isNotEmpty() || triedClearArray) &&
                    !callerName.isNullOrEmpty()
                ) {
                    "$callerName: "
                } else if (clearAttrs.isNotEmpty() || triedClearArray) {
                    "declare: "
                } else {
                    ""
                }
            stdio.stderr.writeUtf8("${shellDiagPrefix()}$biPrefix$varName: readonly variable\n")
            lastExit = 1
            continue
        }
        // Bash refuses `declare +a NAME` / `declare +A NAME` when NAME
        // already names an array — neither indexed nor associative
        // arrays can be demoted to a scalar through the attr-clear
        // form. The diagnostic is
        //   declare: NAME: cannot destroy array variables in this way
        // and exit is 1 with the array left intact. Verified against
        // bash 5.3 directly.
        if (triedClearArray) {
            val existing = varTable.find(varName)
            if (existing?.isIndexed == true || existing?.isAssoc == true) {
                val bi = callerName ?: "declare"
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}$bi: $varName: cannot destroy array variables in this way\n",
                )
                lastExit = 1
                continue
            }
        }
        // Bash refuses to convert an existing array between indexed and
        // associative — including a bare `declare -A NAME` / `declare -a
        // NAME` (no value) when NAME already names the other array kind.
        // Checked against the currently-visible variable BEFORE shadowing,
        // so a function-local `a` (declared `-a` earlier in the same
        // function) is the one consulted, not an outer-scope `a`. The
        // bare-name lane emits a single builtin-prefixed line and leaves
        // the variable's kind unchanged. Verified bash 5.3 (assoc10.sub).
        val direction = arrayKindConversion(varName, effAttrsForOp)
        if (direction != null) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}${callerName ?: "declare"}: $varName: cannot convert $direction array\n",
            )
            lastExit = 1
            continue
        }
        if (makeLocal) shadowLocal(varName)
        // Persist attributes BEFORE writing the value so applyVarCase sees them.
        if (effAttrsForOp.isNotEmpty()) declareAttrs(varName, effAttrsForOp)
        // Apply attribute clears (`+n`, future `+r`/`+x`/... as needed).
        // `+n` is the only one that has runtime effect today; the rest
        // pass through unchanged since their `+` form just toggles a
        // listing mode in bash too.
        if (clearAttrs.isNotEmpty()) {
            varTable.find(varName)?.let { v ->
                v.attrs.removeAll(clearAttrs)
            }
        }
        // `declare "NAME[sub]=value"` (string-arg lane, quoted): the
        // assignment parser sees a flat name "NAME[sub]". Detect the
        // bracket suffix and route to the proper element write so we
        // store into the array's map, not a phantom `NAME[sub]` env entry.
        if (parsed != null) {
            val name = parsed.name
            val lb = name.indexOf('[')
            val rb = name.lastIndexOf(']')
            if (lb > 0 && rb == name.length - 1) {
                val base = name.substring(0, lb)
                val sub = name.substring(lb + 1, rb)
                val v = parsed.value
                // `array_expand_once` security baseline: a literal `$(`
                // surviving in the subscript text means the user wrote
                // (or another arg quoted) a command substitution that
                // must NOT be evaluated by the assignment-builtin
                // subscript path. Bash diagnoses with an arithmetic
                // syntax error on the literal text and skips the write.
                if ("\$(" in sub) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                    )
                    lastExit = 1
                    continue
                }
                if (effAttrsForOp.isNotEmpty()) declareAttrs(base, effAttrsForOp)
                val existing = varTable.find(base)
                if (existing?.isAssoc == true ||
                    VarAttr.Associative in effAttrsForOp
                ) {
                    if ((sub == "*" || sub == "@") &&
                        shoptOptions["assoc_expand_once"] != true
                    ) {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}$base: [$sub]: bad array subscript\n",
                        )
                        lastExit = 1
                        continue
                    }
                    setAssocElement(base, sub, v, parsed.append)
                } else {
                    val idx =
                        if (sub.isBlank()) {
                            0
                        } else {
                            sub.toIntOrNull() ?: try {
                                evalArithRaw(sub).toInt()
                            } catch (_: Throwable) {
                                stdio.stderr.writeUtf8(
                                    "${shellDiagPrefix()}$base: bad array subscript\n",
                                )
                                lastExit = 1
                                -1
                            }
                        }
                    if (idx >= 0) setIndexedElement(base, idx, v, parsed.append)
                }
                if (readonly) varTable.findOrCreate(base).attrs += VarAttr.Readonly
                continue
            }
        }
        if (parsed != null) {
            // `readonly -a 'NAME=(elem...)'` (quoted): bash parses the
            // parenthesised RHS as an array literal even though the quote
            // suppressed assignment-word recognition. Same for `-A`. Without
            // `-a`/`-A`, the value is a literal scalar.
            val v = parsed.value
            // Reparse `(...)` when either `-a`/`-A` is in this call, OR
            // the variable already carries Indexed/Assoc attrs and the
            // caller is declare/typeset/local (NOT readonly — see
            // attr.tests's `readonly 'c=(3)'` behavior).
            val existingForReparse = varTable.find(varName)
            val existingIsAssocForReparse = existingForReparse?.isAssoc == true
            val existingIsIndexedForReparse = existingForReparse?.isIndexed == true
            val existingIsArrayNotRO =
                (existingIsAssocForReparse || existingIsIndexedForReparse) && !existingForReparse.isReadonly &&
                    (callerName == "declare" || callerName == "typeset" || callerName == null)
            if ((hasArrayFlag || existingIsArrayNotRO) &&
                v.startsWith("(") && v.endsWith(")") && v.length >= 2
            ) {
                if (VarAttr.Associative in attrs || existingIsAssocForReparse) {
                    // Mark the var associative (getOrPut sets the attr) so the
                    // shared array-literal path routes to assoc handling, then
                    // delegate — same single code path as the indexed case.
                    assocArrays.getOrPut(varName) { linkedMapOf() }
                    reparseAndApplyArrayLiteral(varName, v, parsed.append)
                    if (varTable.find(varName)?.assocOrNull?.isEmpty() == true) {
                        varTable.find(varName)?.declaredEmptyViaBuiltin = true
                    }
                } else {
                    val ok = reparseAndApplyArrayLiteral(varName, v, parsed.append)
                    if (!ok) {
                        setIndexedArrayLiteral(varName, listOf(ArrayLiteralElem.Value(v)), parsed.append)
                    }
                    val arr = varTable.find(varName)?.indexedOrNull
                    if (arr != null && arr.isEmpty()) {
                        varTable.find(varName)?.declaredEmptyViaBuiltin = true
                    }
                }
            } else if (VarAttr.NameRef in effAttrsForOp) {
                // `declare -n NAME=TARGET` binds the nameref. The scalar
                // we store IS the target name; we must NOT follow the
                // (already-set, if reseating an existing nameref) chain
                // here. Bypass setScalar's env[]= path — which routes
                // through [ProcessEnvAdapter] and would re-resolve.
                varTable.findOrCreate(varName).value = VariableValue.Scalar(v)
            } else {
                setScalar(varName, v, append = parsed.append)
            }
        } else if (makeLocal && varName !in indexedArrays && varName !in assocArrays) {
            env.remove(varName)
        }
        if (readonly) {
            varTable.findOrCreate(varName).attrs += VarAttr.Readonly
        }
    }
    return 0
}

/**
 * Render one variable's bash-style `declare -p` line, or null if the
 * name isn't a known variable / array / attribute holder. Format:
 *
 *   declare -- NAME="value"              scalar
 *   declare -rx NAME="value"             scalar with attrs
 *   declare -a NAME=([0]="v0" [1]="v1")  indexed
 *   declare -A NAME=([k]="v")            associative
 */
internal fun Interpreter.formatDeclareP(
    name: String,
    forTransformA: Boolean = false,
    /** When true, render the leading token as `readonly` instead of
     *  `declare` — bash's POSIX-mode `readonly` listing format
     *  (output is itself a valid POSIX shell input). The Readonly
     *  attribute letter is dropped from the flags since it's implied
     *  by the leading `readonly`. */
    useReadonlyForm: Boolean = false,
): String? {
    val attrs = varTable.find(name)?.attrs ?: emptySet()
    // Dynamic bash arrays — synthesized at read time. BASH_ARGC, BASH_ARGV,
    // and FUNCNAME are presented as `declare -a NAME` (no value) at top
    // level (no function on the stack); BASH_LINENO/BASH_SOURCE/DIRSTACK
    // expose their synthesized contents.
    when (name) {
        "BASH_ARGC", "BASH_ARGV" -> {
            return "declare -a $name=()"
        }

        "FUNCNAME" -> {
            // Top-level: declared but unset (no `=`). Inside a function,
            // the synthetic-callstack view supplies elements.
            if (functionNameStack.isEmpty()) return "declare -a FUNCNAME"
        }

        "BASH_LINENO" -> {
            if (functionNameStack.isEmpty()) {
                // BASH_LINENO[0] is the line where the current script was
                // sourced/invoked; at top level bash exposes "0".
                return "declare -a BASH_LINENO=([0]=\"0\")"
            }
        }

        "BASH_SOURCE" -> {
            if (functionNameStack.isEmpty()) {
                return "declare -a BASH_SOURCE=([0]=\"${quoteDeclareValue(dollarZero).removeSurrounding("\"")}\")"
            }
        }

        "DIRSTACK" -> {
            return "declare -a DIRSTACK=()"
        }
    }
    val tableEntry = varTable.find(name)
    val isArr = tableEntry?.isIndexed == true || VarAttr.Indexed in attrs
    val isAssoc = tableEntry?.isAssoc == true || VarAttr.Associative in attrs
    // For namerefs, `declare -p` reports the TARGET NAME stored in the
    // var's own scalar — must NOT follow the chain. env[] goes through
    // [ProcessEnvAdapter] which auto-follows; read raw via tableEntry.
    val scalarValue =
        if (tableEntry != null && VarAttr.NameRef in tableEntry.attrs) {
            tableEntry.scalarOrNull
        } else {
            env[name]
        }
    if (!isArr && !isAssoc && scalarValue == null && attrs.isEmpty()) return null

    val arrayLetter =
        when {
            isAssoc -> "A"
            isArr -> "a"
            else -> ""
        }
    val extraFlags =
        buildString {
            // Bash flag ordering: i r x l u c n (after the optional a/A
            // array-type letter). Verified against new-exp10 fixture which
            // shows `ir`, `arl`, `ai` — matches bash's print_flags order.
            if (VarAttr.Integer in attrs) append('i')
            // POSIX-mode readonly form: leading `readonly` already implies
            // the Readonly attribute, so the `r` letter is dropped.
            if (VarAttr.Readonly in attrs && !useReadonlyForm) append('r')
            if (VarAttr.Export in attrs) append('x')
            if (VarAttr.Lower in attrs) append('l')
            if (VarAttr.Upper in attrs) append('u')
            if (VarAttr.Capitalize in attrs) append('c')
            if (VarAttr.NameRef in attrs) append('n')
        }
    // bash emits the array-type flag (`a`/`A`) before the other attribute
    // letters, e.g. `declare -ai foo` not `declare -ia foo`. Scalars with no
    // attributes use `--`; with attributes the array letter is omitted.
    val combined = arrayLetter + extraFlags
    val flags = if (combined.isEmpty()) "--" else "-$combined"
    val leadKeyword = if (useReadonlyForm) "readonly" else "declare"

    return when {
        isAssoc -> {
            val arr = tableEntry?.assocOrNull.orEmpty()
            // Bash walks the hash table in bucket order when printing assoc
            // arrays, then closes with a trailing space inside `(...)`.
            // Indexed arrays use numeric order and no trailing space.
            val entries =
                BashAssocOrder.order(arr.keys).joinToString(" ") { k ->
                    "[${quoteAssocKey(k)}]=${quoteDeclareValue(arr[k] ?: "")}"
                }
            // bash distinguishes `declare -A foo` (declared, never assigned)
            // from `declare -A foo=()` (assigned empty) by the stored value
            // — `Variable.isSet` carries the same distinction.
            // bash: empty-array printing depends on context.
            //   - declare -p: omits `=()` ONLY for attribute-only declarations
            //     (`declare -A foo`). Empty assignments (`foo=()` or
            //     `declare -A foo=()`) keep `=()`.
            //   - ${var@A}: also omits when declare-builtin set the empty
            //     value (`declare -A foo=()`); plain `foo=()` keeps `=()`.
            val omitEmpty =
                tableEntry?.attributeOnly == true ||
                    (forTransformA && tableEntry?.declaredEmptyViaBuiltin == true)
            if (arr.isEmpty() && omitEmpty) {
                "$leadKeyword $flags $name"
            } else {
                val body = if (entries.isEmpty()) "" else "$entries "
                "$leadKeyword $flags $name=($body)"
            }
        }

        isArr -> {
            val entries =
                tableEntry?.indexedOrNull.orEmpty().entries.sortedBy { it.key }.joinToString(" ") { (k, v) ->
                    "[$k]=${quoteDeclareValue(v)}"
                }
            val omitEmpty =
                tableEntry?.attributeOnly == true ||
                    (forTransformA && tableEntry?.declaredEmptyViaBuiltin == true)
            if (entries.isEmpty() && omitEmpty) {
                "$leadKeyword $flags $name"
            } else {
                "$leadKeyword $flags $name=($entries)"
            }
        }

        scalarValue != null -> {
            "$leadKeyword $flags $name=${quoteDeclareScalar(scalarValue)}"
        }

        else -> {
            "$leadKeyword $flags $name"
        }
    }
}

/**
 * Bash 5.3 scalar declare-p formatting: POSIX `'...'` form (with embedded
 * `'` escaped as `'\''`) for plain values, ANSI-C `$'...'` for values
 * containing control chars. Used for `declare -p NAME` scalar lines and
 * the `${NAME@A}` transform. Array element values still use the older
 * double-quoted form via [quoteDeclareValue].
 */
internal fun Interpreter.quoteDeclareScalar(v: String): String {
    if (v.any { it.code < 0x20 || it.code == 0x7f }) return ansicQuote(v)
    // Bash 5.3 switches to POSIX `'...'\''...'` form when the value
    // contains a literal `'` — POSIX quoting handles it cleanly without
    // a backslash escape inside double quotes. For values without
    // single quotes, the historical double-quote form is preserved
    // (matches every other declare-p fixture in the corpus).
    if ('\'' in v) return "'" + v.replace("'", "'\\''") + "'"
    return quoteDeclareValue(v)
}

/** Bash declare-quoting: wrap in `"..."` with `\` escaping for `\\`, `"`, `$`, `` ` ``. */
internal fun Interpreter.quoteDeclareValue(v: String): String {
    // Bash: if the value contains a control char (tab/newline/etc.),
    // print it ANSI-C-quoted as `$'…'` so the output stays one-line and
    // round-trippable. Otherwise use double-quoted form with the usual
    // backslash escapes for `\ " $ \``, matching bash's `$'...'` value
    // quoting in `declare -p`.
    if (v.any { it.code < 0x20 || it.code == 0x7f }) return ansicQuote(v)
    val sb = StringBuilder("\"")
    for (c in v) {
        when (c) {
            '\\', '"', '$', '`' -> {
                sb.append('\\')
                sb.append(c)
            }

            else -> {
                sb.append(c)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

/**
 * Associative-array key formatting in `declare -p` output. Bash prints keys
 * unquoted when they look like simple identifiers / digit sequences and
 * quotes them only when they contain whitespace or shell-special characters.
 */
internal fun Interpreter.quoteAssocKey(k: String): String {
    if (k.isEmpty()) return "\"\""
    // Bash 5.3: standalone `*` and `@` are wildcard-subscript sentinels
    // — they need quoting even as assoc keys to disambiguate from
    // `[*]` / `[@]` reads. Mixed-string forms (`a@b`, `a*b`) flow through
    // unquoted. Verified directly.
    if (k == "*" || k == "@") return quoteDeclareValue(k)
    // Bash's `declare -p` key-quoting deny-list — quote when the key
    // contains a shell-meta char: ` \t\n;[](){}&|*?$<>\\"'\`!`
    // (whitespace, statement separators, redirection, glob, quote chars,
    // backslash, backtick, history-expansion `!`) plus any control byte.
    // `!` quotes anywhere in the key (`!`, `a!b`, `!x` all quoted). Plain
    // `=`, `.`, `-`, `+`, `@`, `#`, `%`, `:`, `/` etc. survive unquoted.
    // Verified bash 5.3.
    val needsQuote =
        k.any { c ->
            c in " \t\n;[](){}&|*?$<>\\\"'`!" || c.code < 0x20
        }
    return if (needsQuote) quoteDeclareValue(k) else k
}

/**
 * Filter a candidate name list to only those matching the declare attribute
 * set. `-a` (Indexed) keeps indexed arrays; `-A` (Associative) keeps assoc
 * arrays; other attrs (Export, Readonly, Integer, etc.) require the variable
 * to carry that attribute in [Interpreter.varAttrs]. Multiple flags AND
 * together — `declare -pax` shows only exported indexed arrays.
 *
 * Always presented to bash's built-in dynamic arrays: BASH_LINENO,
 * BASH_SOURCE, FUNCNAME, BASH_ARGC, BASH_ARGV, DIRSTACK count as indexed
 * even when they're not in the [Interpreter.indexedArrays] storage.
 */
internal fun Interpreter.filterDeclareList(
    names: List<String>,
    attrs: Set<VarAttr>,
): List<String> {
    val synthIndexed = setOf("BASH_ARGC", "BASH_ARGV", "BASH_LINENO", "BASH_SOURCE", "DIRSTACK", "FUNCNAME")
    return names.filter { n ->
        val nAttrs = varTable.find(n)?.attrs ?: emptySet()
        val tv = varTable.find(n)
        val isInd = tv?.isIndexed == true || n in synthIndexed || VarAttr.Indexed in nAttrs
        val isAsc = tv?.isAssoc == true || VarAttr.Associative in nAttrs
        attrs.all { a ->
            when (a) {
                VarAttr.Indexed -> isInd
                VarAttr.Associative -> isAsc
                else -> a in nAttrs
            }
        }
    }
}
