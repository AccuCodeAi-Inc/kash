package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.Parser
import kotlinx.io.Buffer
import kotlinx.io.readString

// Word-expansion façade and command-substitution runtime — wraps
// [Expander] for each callsite (`expandArg`, `expandSingle`,
// `expandPattern`, `expandRegexRhs`, `expandAssignmentValue`,
// `flatExpandArgs`) and runs `$(...)`-style command substitution
// in-process while snapshotting parent state. Extracted from
// [Interpreter] as part of the file-organization cleanup. Pure refactor.

/**
 * Drain any non-fatal diagnostics the Expander queued during the most-
 * recent expansion and write them to stderr in source order with the
 * standard `script: line N:` prefix. Callers invoke this immediately
 * after each [expander] use so the diagnostics interleave correctly
 * with the calling simple-command's output.
 */
internal suspend fun Interpreter.flushExpanderDiagnostics(exp: Expander) {
    if (exp.pendingDiagnostics.isNotEmpty()) {
        for (msg in exp.pendingDiagnostics) {
            errSink.writeUtf8("${shellDiagPrefix()}$msg\n")
        }
        exp.pendingDiagnostics.clear()
    }
    exp.lastBadSubscript.clear()
    if (exp.commandAbortFlag[0]) {
        arithSubstFailedInWordExpansion = true
        exp.commandAbortFlag[0] = false
    }
}

internal fun Interpreter.expander(): Expander =
    Expander(
        env = env,
        positional = positional,
        lastExit = lastExit,
        fs = fs,
        cwd = cwd,
        scriptName = dollarZero,
        lastBgPid = lastBgPidMasked(),
        indexedArrays = arraysWithSyntheticCallStack(),
        assocArrays =
            buildMap {
                for (name in varTable.visibleNames()) {
                    varTable.find(name)?.assocView?.let { put(name, it) }
                }
            },
        currentLine = (currentLine - linenoOffset).coerceAtLeast(0),
        userDb = userDb,
        posixMode = posixModeRuntime,
        shellPid = shellPid,
        bashPid = process.pid,
        shellPpid = shellPpid,
        randomSource = randomSource,
        shellSecondsElapsed = { clock.elapsedSinceShellStart().inWholeSeconds },
        wallClock =
            object : kotlin.time.Clock {
                override fun now(): kotlin.time.Instant = clock.now()
            },
        shoptOptions = shoptOptions,
        currentDashFlags = { composeDashFlags() },
        noglob = { noglob },
        lineEditorActive = { options.emacsMode || options.viMode },
        nameRefRawTarget = { name ->
            val v = varTable.find(name) ?: return@Expander null
            if (VarAttr.NameRef in v.attrs) v.scalarOrNull else null
        },
        parameterTransformOp = { op, name -> parameterTransformDispatch(op, name) },
        nounset = nounset,
        assignArrayElement = { name, sub, value ->
            // `${name[sub]=value}` element assignment from inside parameter
            // expansion. Synchronously mutate the IndexedArraysView /
            // assoc storage; the suspending [setIndexedElement] / [setAssocElement]
            // helpers add diagnostics we don't want from this read-side
            // path (the param-expansion `=` op is silent in bash).
            // Apply -u/-l/-c case mods and -i integer coercion inline
            // (applyVarCase / applyIntegerAttr both suspend; we're on the
            // non-suspending callback path).
            val attrs = varTable.find(name)?.attrs ?: emptySet()
            val coerced =
                if (VarAttr.Integer in attrs) {
                    try {
                        ArithEval(value.ifBlank { "0" }, env, arithStore).evaluate().toString()
                    } catch (_: Throwable) {
                        value
                    }
                } else {
                    value
                }
            val cased =
                when {
                    VarAttr.Upper in attrs -> {
                        coerced.uppercase()
                    }

                    VarAttr.Lower in attrs -> {
                        coerced.lowercase()
                    }

                    VarAttr.Capitalize in attrs -> {
                        if (coerced.isEmpty()) coerced else coerced[0].uppercase() + coerced.drop(1).lowercase()
                    }

                    else -> {
                        coerced
                    }
                }
            val v = varTable.find(name)
            val result =
                if (v?.isAssoc == true) {
                    v.assocOrNull?.set(sub, cased)
                    cased
                } else {
                    val idx =
                        sub.toIntOrNull() ?: try {
                            ArithEval(sub.ifBlank { "0" }, env, arithStore).evaluate().toInt()
                        } catch (_: Throwable) {
                            0
                        }
                    val entry = varTable.findOrCreate(name)
                    if (entry.value !is VariableValue.Indexed) {
                        entry.value = VariableValue.Indexed(mutableMapOf())
                    }
                    entry.attrs += VarAttr.Indexed
                    entry.indexedOrNull?.set(idx, cased)
                    env.remove(name)
                    cased
                }
            result
        },
    )

/**
 * Compute the result of `${name@OP}` for the variable-aware operators
 * `A`/`a`/`K`/`k`. Returns null when the variable doesn't exist (matches
 * bash, which emits nothing for `${undefined@A}`).
 *
 *   A — A `declare …` line that round-trips the variable's current
 *       attributes and value. Identical to `declare -p NAME` output but
 *       without the trailing newline.
 *   a — Just the attribute flag letters (e.g. `arx` for readonly,
 *       indexed, exported). Empty string when the var exists with no
 *       attrs; null when unset.
 *   K — `[k]="v" [k2]="v2"` pairs for arrays, or `"value"` for scalars.
 *       Keys/values double-quoted with the same escapes as `declare -p`.
 *   k — Same as K but unquoted.
 */
internal fun Interpreter.parameterTransformDispatch(
    op: String,
    name: String,
): String? {
    val v = varTable.find(name)
    when (op) {
        "A" -> {
            // bash: `${@@A}` / `${*@A}` recreates the positional
            // parameters as a `set -- ...` command. Each positional gets
            // single-quoted (POSIX form) so the line round-trips.
            if (name == "@" || name == "*") {
                val parts = positional
                if (parts.isEmpty()) return ""
                // bash emits each positional in POSIX `'…'\\''…'` form
                // (not double-quoted), even for simple values — the
                // result is meant to be a valid `set --` command that
                // round-trips the current positional vector.
                return parts.joinToString(" ", prefix = "set -- ") { p ->
                    if (p.any { it.code < 0x20 || it.code == 0x7f }) {
                        ansicQuote(p)
                    } else {
                        "'" + p.replace("'", "'\\''") + "'"
                    }
                }
            }
            return formatDeclareP(name, forTransformA = true)
        }

        "a" -> {
            if (v == null && env[name] == null) return null
            val attrs = v?.attrs ?: emptySet()
            return buildString {
                // Bash emits declare flags in a fixed order:
                // a A r x i u l c n t. Indexed array carries its `a` flag
                // iff the var is actually an indexed array.
                if (v?.isIndexed == true || VarAttr.Indexed in attrs) append('a')
                if (v?.isAssoc == true || VarAttr.Associative in attrs) append('A')
                // Order after a/A: i r x l u c n. Matches the @a / declare -p
                // output bash 5.3 produces (e.g. `ir` for `-ri`, `arl` for
                // `-arl`). Verified against new-exp10 fixture.
                if (VarAttr.Integer in attrs) append('i')
                if (VarAttr.Readonly in attrs) append('r')
                if (VarAttr.Export in attrs) append('x')
                if (VarAttr.Lower in attrs) append('l')
                if (VarAttr.Upper in attrs) append('u')
                if (VarAttr.Capitalize in attrs) append('c')
                if (VarAttr.NameRef in attrs) append('n')
            }
        }

        "K", "k" -> {
            val quote = op == "K"
            // Indexed array: `[idx]="val"` sorted numerically. Bash
            // actually formats `K` as `"idx" "val" "idx" "val" …`
            // (space-separated pairs) for arrays — verified against
            // `info bash` "Shell Parameter Expansion": "produces a
            // quoted version of the value … as a sequence of quoted
            // key-value pairs". Tested empirically against bash 5.2:
            // `declare -a a=(x y); echo "${a@K}"` → `0 "x" 1 "y"`
            // (key unquoted, value quoted).
            if (v?.isIndexed == true) {
                val arr = v.indexedView.orEmpty()
                if (arr.isEmpty()) return ""
                return arr.entries.sortedBy { it.key }.joinToString(" ") { (k, vv) ->
                    if (quote) "$k ${quoteDeclareValue(vv)}" else "$k $vv"
                }
            }
            if (v?.isAssoc == true) {
                val arr = v.assocView.orEmpty()
                if (arr.isEmpty()) return ""
                return BashAssocOrder.order(arr.keys).joinToString(" ") { k ->
                    val vv = arr[k] ?: ""
                    // Bash keys: quote only when needed (same rule as
                    // `declare -p`'s assoc subscript formatting). Values:
                    // always double-quoted for `@K`, bare for `@k`.
                    if (quote) "${quoteAssocKey(k)} ${quoteDeclareValue(vv)}" else "$k $vv"
                }
            }
            // Scalar: bash emits the same form as `${var@Q}` — a
            // single-quoted (or `$'…'` for control chars) round-tripable
            // representation. The `k` variant produces the same output
            // for scalars; the "values unquoted" distinction only
            // applies to array element values.
            val scalar = env[name] ?: v?.scalarOrNull
            if (scalar == null) return null
            return atQQuote(scalar)
        }
    }
    return null
}

/**
 * Bash `${var@Q}` quoting — single-quoted, with `$'…'` escape form
 * when the value contains a control char, single quote, or backslash.
 * Mirrors [Expander.quoteForShell] but standalone so the variable-
 * transform dispatch can reuse it without an [Expander] instance.
 */
private fun atQQuote(v: String): String {
    if (v.isEmpty()) return "''"
    val needsAnsiC = v.any { it.code < 0x20 || it == '\\' || it == '\'' }
    if (!needsAnsiC) return "'$v'"
    val sb = StringBuilder("$'")
    for (c in v) {
        when (c) {
            '\\' -> {
                sb.append("\\\\")
            }

            '\n' -> {
                sb.append("\\n")
            }

            '\t' -> {
                sb.append("\\t")
            }

            '\r' -> {
                sb.append("\\r")
            }

            '\b' -> {
                sb.append("\\b")
            }

            '\u0007' -> {
                sb.append("\\a")
            }

            '\u000C' -> {
                sb.append("\\f")
            }

            '\u000B' -> {
                sb.append("\\v")
            }

            '\'' -> {
                sb.append("\\'")
            }

            else -> {
                if (c.code < 0x20 || c.code == 0x7F) {
                    sb.append('\\')
                    sb.append(c.code.toString(8).padStart(3, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
    }
    sb.append('\'')
    return sb.toString()
}

/**
 * Compose the `$-` variable's string: one letter per currently-active
 * single-letter shell option, in bash's stable emission order. Defaults
 * `h` (hashall) and `B` (brace expansion) are always present per bash;
 * `i` reflects interactive mode; the rest follow `set -e/-u` etc.
 */
private fun Interpreter.composeDashFlags(): String {
    val sb = StringBuilder()
    if (errexit) sb.append('e')
    sb.append('h') // hashall — always on in kash
    sb.append('B') // brace expansion — always on in kash
    if (interactive) sb.append('i')
    if (nounset) sb.append('u')
    if (pipefail) {
        // pipefail has no $- letter in bash; only the `set -o` form.
        // Intentionally omitted.
    }
    if (noclobber) sb.append('C')
    if (posixModeRuntime) {
        // posix mode shows as `set -o posix`, no $- letter.
    }
    if (restricted) sb.append('r')
    return sb.toString()
}

/**
 * Returns a view of [indexedArrays] augmented with bash's synthetic
 * call-stack arrays — `FUNCNAME`, `BASH_LINENO`, `BASH_SOURCE` — derived
 * from [functionNameStack] and [callerLineStack]. Bash convention:
 *
 *   FUNCNAME[0]    = innermost function (currently running)
 *   FUNCNAME[N]    = caller chain outward
 *   FUNCNAME[last] = "main" (top-level) — only present when *inside* a
 *                    function; at top level the array is unset entirely.
 *
 *   BASH_LINENO[0] = line number in the caller where FUNCNAME[0] was
 *                    invoked. Parallel array to FUNCNAME but shifted —
 *                    BASH_LINENO[N] corresponds to FUNCNAME[N]'s caller.
 *
 * Our [functionNameStack] is innermost-last, so we reverse it. User-set
 * `FUNCNAME=...` is shadowed when inside a function (bash also makes
 * these special and shadows the env value during function execution).
 */
private fun Interpreter.arraysWithSyntheticCallStack(): Map<String, Map<Int, String>> {
    // DIRSTACK / FUNCNAME / BASH_LINENO / BASH_SOURCE are now
    // hook-backed [Variable]s registered at session init (see
    // [Interpreter.registerSpecialVariables]). [Variable.indexedView]
    // surfaces their live values; user-assigned indexed arrays use
    // the same path. Single walk, no per-call merge.
    val out = LinkedHashMap<String, Map<Int, String>>()
    for (name in varTable.visibleNames()) {
        varTable.find(name)?.indexedView?.let { out[name] = it }
    }
    return out
}

internal suspend fun Interpreter.expandArg(word: Word): List<String> {
    val exp = expander()
    val out = exp.expandArg(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    return out
}

internal suspend fun Interpreter.expandSingle(word: Word): String {
    val exp = expander()
    val out = exp.expandSingle(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    return out
}

internal suspend fun Interpreter.expandSingleNoTilde(word: Word): String {
    val exp = expander()
    val out = exp.expandSingleNoTilde(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    return out
}

internal suspend fun Interpreter.expandPattern(word: Word): String {
    val exp = expander()
    val out = exp.expandPattern(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    return out
}

/**
 * Expand a `[[ … =~ … ]]` RHS into a regex source string. Quoted spans
 * (single/double-quoted, backslash-escaped, and the result of expanding
 * a variable that was inside `"..."`) are regex-escaped so their
 * metacharacters match literally. Unquoted spans — including the result
 * of an unquoted `$VAR` expansion — are passed through as regex source.
 * This matches bash's `RTAG_*` / `STRIP_REGEX_OPS_FOR_EXTGLOB` semantics
 * (POSIX leaves regex matching unspecified; bash documents the rule).
 */
internal suspend fun Interpreter.expandRegexRhs(word: Word): String {
    val exp = expander()
    val groups = exp.expandToFragments(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    val sb = StringBuilder()
    for (group in groups) {
        for (frag in group) {
            if (frag.quoted) sb.append(regexEscape(frag.text)) else sb.append(frag.text)
        }
    }
    return sb.toString()
}

internal suspend fun Interpreter.expandAssignmentValue(word: Word): String {
    val exp = expander()
    val out = exp.expandAssignmentValue(preprocessWord(word))
    flushExpanderDiagnostics(exp)
    return out
}

internal suspend fun Interpreter.flatExpandArgs(words: List<Word>): List<String> {
    val out = mutableListOf<String>()
    val braceOn = shoptOptions["braceexpand"] != false // default ON
    for (w in words) {
        val brace = if (braceOn) expandBraces(w, enabled = true) else listOf(w)
        for (bw in brace) out += expandArg(bw)
    }
    return out
}

/**
 * Like [flatExpandArgs] but records, into [flagsOut] (parallel to the
 * returned args), whether each arg came from an unquoted array-reference
 * word that expanded to exactly one field (see [isArrayRefWord]). Used by
 * the [ARRAYREF_ARG_BUILTINS] to decide single-key greedy subscript parsing
 * under `assoc_expand_once`. Expands ONCE — no double command-substitution
 * side effects.
 */
internal suspend fun Interpreter.flatExpandArgsTracked(
    words: List<Word>,
    flagsOut: MutableList<Boolean>,
): List<String> {
    val out = mutableListOf<String>()
    val braceOn = shoptOptions["braceexpand"] != false // default ON
    for (w in words) {
        val arrayRef = isArrayRefWord(w)
        val brace = if (braceOn) expandBraces(w, enabled = true) else listOf(w)
        // An array-reference word carries no braces and doesn't field-split,
        // so it stays a single brace-expansion / single field; only then does
        // its flag survive (the array-reference property is preserved only
        // through unsplit, single-result words).
        if (arrayRef && brace.size == 1) {
            val fields = expandArg(brace[0])
            if (fields.size == 1) {
                out += fields[0]
                flagsOut += true
                continue
            }
            for (f in fields) {
                out += f
                flagsOut += false
            }
        } else {
            for (bw in brace) {
                for (f in expandArg(bw)) {
                    out += f
                    flagsOut += false
                }
            }
        }
    }
    return out
}

internal suspend fun Interpreter.preprocessWord(w: Word): Word {
    if (!containsCommandSub(w.parts)) return w
    val out = ArrayList<WordPart>(w.parts.size)
    for (p in w.parts) out += preprocessPart(p)
    return Word(out, w.line)
}

internal fun Interpreter.containsCommandSub(parts: List<WordPart>): Boolean =
    parts.any { p ->
        when (p) {
            is WordPart.CommandSubstitution -> {
                true
            }

            is WordPart.ArithmeticExpansion -> {
                true
            }

            is WordPart.ProcessSubstitution -> {
                true
            }

            is WordPart.DoubleQuoted -> {
                containsCommandSub(p.parts)
            }

            is WordPart.ParameterExpansion -> {
                (p.arg1?.parts?.let { containsCommandSub(it) } ?: false) ||
                    (p.arg2?.parts?.let { containsCommandSub(it) } ?: false) ||
                    // Deferred operand text MAY contain a cmdsub. We can't
                    // re-lex here without committing to it, so be
                    // conservative — flag for preprocessing if rawArg1 is
                    // present at all. preprocessPart's re-lex below then
                    // expands cmdsubs that actually show up.
                    (p.rawArg1 != null) ||
                    // Subscript text (`${A[$(cmd)]}` / `${A[$var]}`) can
                    // contain cmdsubs/param refs that need pre-expansion.
                    (p.subscript?.let { it.contains('$') || it.contains('`') } == true)
            }

            else -> {
                false
            }
        }
    }

internal suspend fun Interpreter.preprocessPart(p: WordPart): WordPart =
    when (p) {
        is WordPart.CommandSubstitution -> {
            val result =
                try {
                    evalCommandSubstitution(parseCommandSubstitutionBody(p.rawText, p.openLine))
                } catch (e: ParseException) {
                    // Inner `$(...)` syntax error — see [emitCmdSubParseError]
                    // for diagnostic shape and abort semantics.
                    emitCmdSubParseError(e)
                    ""
                }
            WordPart.ExpandedText(result)
        }

        is WordPart.ArithmeticExpansion -> {
            WordPart.ExpandedText(
                evalArithmetic(p.rawText),
                rawSource = "$((" + p.rawText + "))",
            )
        }

        is WordPart.ProcessSubstitution -> {
            val result =
                try {
                    evalProcessSubstitution(p.direction, parseCommandSubstitutionBody(p.rawText, p.openLine))
                } catch (e: ParseException) {
                    emitCmdSubParseError(e)
                    ""
                }
            WordPart.ExpandedText(result)
        }

        is WordPart.DoubleQuoted -> {
            val out = ArrayList<WordPart>(p.parts.size)
            for (sub in p.parts) out += preprocessPart(sub)
            WordPart.DoubleQuoted(out)
        }

        is WordPart.ParameterExpansion -> {
            // Operands of `${var:+word}` / `${var//pat/repl}` etc. may
            // themselves contain `$(...)` or `$((...))`. Without this
            // recursion the inner CommandSubstitution survives into the
            // Expander, which has no I/O hook and treats it as a no-op —
            // dropping the operand entirely. Visible failure: `${x:+ "$(:)"}`
            // produced zero args instead of one quoted-empty anchor.
            //
            // Deferred operand path (rawArg1 != null, from default-value
            // ops): re-lex the captured text here with the outer dq depth
            // restored, then recursively preprocess the resulting chunks
            // so any nested `$(...)` evaluates. The expander then sees
            // a normal ParameterExpansion with arg1 set and rawArg1
            // cleared. Mirrors bash's `default-value RHS expander`
            // calling `expand_string` on the captured text at use time.
            val resolvedArg1 =
                p.arg1 ?: p.rawArg1?.let { raw ->
                    val isBareDefaultOp = p.op in setOf("-", "+", "=", "?")
                    val chunks =
                        try {
                            com.accucodeai.kash.parser.Lexer.lexDeferredOperand(
                                raw,
                                p.outerDqAtLex,
                                inDefaultValueOp = isBareDefaultOp,
                            )
                        } catch (_: Exception) {
                            try {
                                com.accucodeai.kash.parser.Lexer.lexDeferredOperand(
                                    raw,
                                    p.outerDqAtLex,
                                    defaultValueOp = false,
                                    inDefaultValueOp = isBareDefaultOp,
                                )
                            } catch (_: Exception) {
                                null
                            }
                        }
                    chunks?.let {
                        Word(
                            it.map { c ->
                                com.accucodeai.kash.parser
                                    .chunkToPart(c)
                            },
                        )
                    }
                }
            val arg1 = resolvedArg1?.let { w -> Word(w.parts.map { preprocessPart(it) }, w.line) }
            val arg2 = p.arg2?.let { w -> Word(w.parts.map { preprocessPart(it) }, w.line) }
            // Pre-expand the subscript text so `${a[$(echo 42)]}` and
            // `${a[$idx]=v}` see a resolved literal subscript by the time
            // they reach the expander. Bash subscripts undergo parameter
            // expansion, command substitution, arithmetic expansion, and
            // quote removal — the expander's arith path then takes over
            // for the integer math. Skip the special spread markers `@`/`*`
            // and any subscript that's already a plain integer.
            val sub = p.subscript
            val expandedSubscript =
                if (sub == null || sub == "@" || sub == "*" ||
                    sub.toIntOrNull() != null ||
                    (sub.indexOf('$') < 0 && sub.indexOf('`') < 0)
                ) {
                    sub
                } else {
                    try {
                        val ast =
                            com.accucodeai.kash.parser
                                .Parser("echo $sub", aliasResolver)
                                .parseScript()
                        val word =
                            ast.statements
                                .firstOrNull()
                                ?.pipelines
                                ?.firstOrNull()
                                ?.commands
                                ?.firstOrNull()
                                ?.let { it as? com.accucodeai.kash.ast.SimpleCommand }
                                ?.args
                                ?.firstOrNull()
                        if (word != null) expandSingle(word) else sub
                    } catch (_: Throwable) {
                        sub
                    }
                }
            // Clear rawArg1 — the expander will use arg1 now.
            p.copy(
                arg1 = arg1,
                arg2 = arg2,
                rawArg1 = if (arg1 != null) null else p.rawArg1,
                subscript = expandedSubscript,
                // Keep the pre-expansion source so the expander can echo it
                // in a `[...]: bad array subscript` diagnostic when the
                // subscript expands to empty on an assoc array.
                rawSubscript = if (expandedSubscript != sub) sub else p.rawSubscript,
            )
        }

        else -> {
            p
        }
    }

/**
 * Emit a bash-style cmdsub parse-error diagnostic to stderr AND raise a
 * script-level abort. Pre-Part-B this error fired during the outer parse
 * pass, which short-circuited [com.accucodeai.kash.tools.kash.KashShellCommand.runScriptText]
 * to "emit diagnostic + exit 2 without running anything past the bad
 * statement." Part B defers the inner parse to expansion time, so we
 * have to reproduce the same shape here: set [pendingAbort] so
 * [runStreaming] breaks out of its statement loop, and use exit code 2
 * to match the parse-error convention.
 *
 * Modern bash actually treats this as a runtime warning and continues,
 * but the conformance corpus was captured against an older bash that
 * aborted — matching the corpus is the contract.
 */
internal suspend fun Interpreter.emitCmdSubParseError(e: ParseException) {
    emitShellParseError(errSink, currentOuterScript, dollarZero, currentOuterIsCLine, e)
    // Abort the in-flight simple command (so e.g. `echo $(bad)` doesn't
    // execute as `echo` with empty arg) AND the surrounding script loop.
    // Pre-Part-B the entire script aborted at parse time, before any
    // statement ran; we replicate that observable behavior.
    arithSubstFailedInWordExpansion = true
    pendingAbort = true
    pendingAbortCode = 2
}

/**
 * Re-parse a `$(...)` / `<(...)` / `>(...)` body at expansion time against
 * the **live** alias / function table: the body was stored as raw text at
 * outer-parse time precisely so this re-parse can see runtime state.
 *
 * Bash appends "while looking for matching `)'" to syntax-error
 * diagnostics that originate inside a `$(...)`, and an "incomplete" body
 * (compound construct missing its closer) becomes "syntax error near
 * unexpected token `)'" with `)' as the offender. The error-rewriting
 * lives here so deferred-parse failures surface with the same shape they
 * used to when the parse ran at outer-parse time.
 *
 * Inner-text line numbers are translated to outer-script lines by adding
 * [openLine] - 1: the cmdsub body's line 1 corresponds to the outer
 * line where `$(` was opened.
 */
internal fun Interpreter.parseCommandSubstitutionBody(
    rawText: String,
    openLine: Int,
): Script =
    try {
        // [Parser] threads [startLine] through to the inner [Lexer], so
        // every token / Statement / Word / ParseException.line produced
        // from the body already carries the outer-source line number.
        // No post-hoc translation needed (used to add `openLine - 1`
        // here before the Lexer.startLine plumbing landed).
        Parser(rawText, aliasResolver, posixMode = posixModeRuntime, startLine = openLine).parseScript()
    } catch (e: ParseException) {
        val raw = e.message ?: "parse error"
        val (finalMsg, finalTok) =
            when {
                "while looking for matching" in raw -> raw to e.offendingToken
                raw.startsWith("incomplete:") -> "syntax error near unexpected token `)'" to ")"
                else -> "$raw while looking for matching `)'" to e.offendingToken
            }
        throw ParseException(finalMsg, line = e.line, column = e.column, offendingToken = finalTok)
    }

/**
 * Capture stdout of a substituted command. Runs the sub-script
 * inside [Interpreter.runInInPlaceSubshellScope] — the shared
 * "fake-fork" scope that handles cwd / env / errexit / abort marker
 * / job-mask save-restore — with a fresh stdout buffer wrapped
 * around it. Returns the captured text with one trailing newline
 * trimmed (POSIX rule).
 *
 * For the conceptual relationship to the real-fork path
 * ([Interpreter.forkSubshell]), see the doc on
 * [Interpreter.runInInPlaceSubshellScope]. Both routes consult
 * [Interpreter.nextSubshellJobMask] for the job-visibility rule, so
 * they cannot drift on that.
 */
internal suspend fun Interpreter.evalCommandSubstitution(script: Script): String {
    val savedOut = outSink
    val capture = Buffer()
    outSink = capture.asSuspendSink()
    // Default-mode cmdsub absorbs an inner errexit-abort; only POSIX
    // mode needs to propagate to the parent's abort marker. Skip the
    // outcome holder allocation entirely in default mode — keeps the
    // hot path allocation-free for the ifs-posix.tests 6856-iter loop.
    val outcome = if (posixModeRuntime) Interpreter.InPlaceSubshellOutcome() else null
    try {
        val text =
            runInInPlaceSubshellScope(outcome) {
                try {
                    for (stmt in script.statements) {
                        lastExit = runStatement(stmt, emptySource())
                        if (pendingAbort) break
                    }
                } catch (r: Interpreter.ReturnException) {
                    // POSIX: `return` inside a cmdsub `$(...)` aborts the
                    // SUBSHELL only (cmdsub IS a subshell), not the
                    // enclosing function. Stops the cmdsub body, captures
                    // whatever output it produced so far, and the
                    // surrounding function continues with the result.
                    lastExit = r.code
                }
                cmdsubExitSeen = true
                capture.readString().trimEnd('\n')
            }
        // POSIX-mode propagation: a `$(false; echo ok)` whose inner
        // errexit fired must re-arm the parent's abort marker so the
        // outer script aborts. Default (bash non-POSIX) mode absorbs
        // the abort — patterns like `x=$(false; echo ok)` are common
        // and shouldn't kill the script.
        if (outcome != null && outcome.innerAborted) {
            pendingAbort = true
            pendingAbortCode = outcome.innerAbortCode
            lastExit = outcome.innerAbortCode
        }
        return text
    } finally {
        outSink = savedOut
    }
}
