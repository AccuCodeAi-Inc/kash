package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.completion.CompleteAction
import com.accucodeai.kash.completion.CompleteOption
import com.accucodeai.kash.completion.CompleteSpec
import com.accucodeai.kash.completion.bashCompspecBucket
import com.accucodeai.kash.completion.formatCompleteSpec
import com.accucodeai.kash.interpreter.Interpreter.Stdio

private const val COMPLETE_USAGE =
    "complete: usage: complete [-abcdefgjksuv] [-pr] [-DEI] [-o option] " +
        "[-A action] [-G globpat] [-W wordlist] [-F function] [-C command] " +
        "[-X filterpat] [-P prefix] [-S suffix] [name ...]"

private const val COMPOPT_USAGE =
    "compopt: usage: compopt [-o|+o option] [-DEI] [name ...]"

/**
 * Bash [`complete`](https://www.gnu.org/software/bash/manual/html_node/Programmable-Completion-Builtins.html)
 * builtin. Registers, prints, or removes per-command completion specs
 * consulted at TAB time by [com.accucodeai.kash.completion.ShellCompleter].
 *
 * Implements the full bash CLI surface — actions a/b/c/d/e/f/g/j/k/s/u/v,
 * default flags D/E/I, print/remove modes p/r, and arg-bearing options
 * A/G/W/F/C/X/P/S/o — but does *not* yet execute the registered specs to
 * produce candidates during interactive completion. See ShellCompleter
 * for the runtime side.
 *
 * Exit status follows bash: 0 on success, 1 if -p/-r references a name
 * with no registered spec, 2 on usage error.
 */
internal suspend fun Interpreter.runCompleteIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val diag = shellDiagPrefix()
    val parsed =
        parseCompleteArgs(
            args = args,
            stdio = stdio,
            progName = "complete",
            usage = COMPLETE_USAGE,
            allowDashV = false,
            diagPrefix = diag,
        ) ?: return 2

    val hasAnySpec =
        parsed.actions.isNotEmpty() || parsed.options.isNotEmpty() ||
            parsed.glob != null || parsed.wordlist != null || parsed.filter != null ||
            parsed.prefix != null || parsed.suffix != null ||
            parsed.function != null || parsed.command != null
    // Mode 1: print specs (`-p`, OR bare `complete` with no flags/names —
    // bash treats both as print-all).
    val implicitPrint =
        !parsed.printMode && !parsed.removeMode && !hasAnySpec && parsed.names.isEmpty() &&
            !parsed.defaultD && !parsed.defaultE && !parsed.defaultI
    if (parsed.printMode || implicitPrint) {
        return printSpecs(parsed.names, stdio)
    }

    // Mode 2: remove specs.
    if (parsed.removeMode) {
        if (parsed.names.isEmpty() && !parsed.defaultD && !parsed.defaultE && !parsed.defaultI) {
            completeSpecs.clear()
            completeDefault = null
            completeEmpty = null
            completeInitial = null
            return 0
        }
        var anyMissing = false
        if (parsed.defaultD) completeDefault = null
        if (parsed.defaultE) completeEmpty = null
        if (parsed.defaultI) completeInitial = null
        for (name in parsed.names) {
            if (completeSpecs.remove(name) == null) {
                stdio.stderr.writeUtf8("${diag}complete: $name: no completion specification\n")
                anyMissing = true
            }
        }
        return if (anyMissing) 1 else 0
    }

    // Mode 3: register a spec. Need at least one of: a name, or -D/-E/-I.
    if (!hasAnySpec && parsed.names.isEmpty()) {
        // `complete` with literally no args is treated as "print all" by bash
        // — already covered by parsed.printMode above when no flags were
        // given. We get here when the user typed e.g. `complete -b` with no
        // name: register-mode with a spec but no targets → usage error.
        stdio.stderr.writeUtf8("$COMPLETE_USAGE\n")
        return 2
    }
    if (parsed.names.isEmpty() && !parsed.defaultD && !parsed.defaultE && !parsed.defaultI) {
        stdio.stderr.writeUtf8("$COMPLETE_USAGE\n")
        return 2
    }
    val spec =
        CompleteSpec(
            actions = parsed.actions,
            options = parsed.options,
            glob = parsed.glob,
            wordlist = parsed.wordlist,
            filter = parsed.filter,
            prefix = parsed.prefix,
            suffix = parsed.suffix,
            function = parsed.function,
            command = parsed.command,
        )
    if (parsed.defaultD) completeDefault = spec
    if (parsed.defaultE) completeEmpty = spec
    if (parsed.defaultI) completeInitial = spec
    for (name in parsed.names) {
        completeSpecs[name] = spec
    }
    return 0
}

/**
 * Bash `compopt`: modify the `-o` options of an existing spec. Without a
 * name argument, modifies the spec for the command currently being
 * completed (we don't yet have an "active completion" concept, so that
 * path errors with "compopt: not currently executing completion function").
 *
 * `+o option` removes the option; `-o option` adds it. `-D`/`-E`/`-I`
 * apply the change to the corresponding default spec instead of a
 * per-name spec.
 */
internal suspend fun Interpreter.runCompoptIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val diag = shellDiagPrefix()
    val addOptions = mutableSetOf<CompleteOption>()
    val removeOptions = mutableSetOf<CompleteOption>()
    var defaultD = false
    var defaultE = false
    var defaultI = false
    val names = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                while (i < args.size) {
                    names += args[i]
                    i++
                }
                break
            }

            a == "-o" || a == "+o" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("${diag}compopt: $a: option requires an argument\n")
                    return 2
                }
                val opt = CompleteOption.fromName(v)
                if (opt == null) {
                    stdio.stderr.writeUtf8("${diag}compopt: $v: invalid option name\n")
                    return 2
                }
                if (a == "-o") addOptions += opt else removeOptions += opt
                i += 2
            }

            a == "-D" -> {
                defaultD = true
                i++
            }

            a == "-E" -> {
                defaultE = true
                i++
            }

            a == "-I" -> {
                defaultI = true
                i++
            }

            a.startsWith("-") && a.length > 1 -> {
                stdio.stderr.writeUtf8("${diag}compopt: $a: invalid option\n")
                stdio.stderr.writeUtf8("$COMPOPT_USAGE\n")
                return 2
            }

            else -> {
                names += a
                i++
            }
        }
    }

    if (names.isEmpty() && !defaultD && !defaultE && !defaultI) {
        // Bash: this is allowed only when invoked from inside a completion
        // function (modifies the in-flight compspec). We don't support that
        // yet — surface a clear error.
        stdio.stderr.writeUtf8("${diag}compopt: not currently executing completion function\n")
        return 1
    }

    fun apply(spec: CompleteSpec): CompleteSpec = spec.copy(options = (spec.options + addOptions) - removeOptions)

    var anyMissing = false
    if (defaultD) completeDefault = completeDefault?.let(::apply) ?: CompleteSpec(options = addOptions)
    if (defaultE) completeEmpty = completeEmpty?.let(::apply) ?: CompleteSpec(options = addOptions)
    if (defaultI) completeInitial = completeInitial?.let(::apply) ?: CompleteSpec(options = addOptions)
    for (name in names) {
        val existing = completeSpecs[name]
        if (existing == null) {
            stdio.stderr.writeUtf8("${diag}compopt: $name: no completion specification\n")
            anyMissing = true
        } else {
            completeSpecs[name] = apply(existing)
        }
    }
    return if (anyMissing) 1 else 0
}

private suspend fun Interpreter.printSpecs(
    names: List<String>,
    stdio: Stdio,
): Int {
    if (names.isEmpty()) {
        // Print all registered specs in bash compspec hash-table order:
        // group by `bashCompspecBucket(name)` (FNV-1 hash low 9 bits),
        // walk buckets 0..511 ascending, and within each bucket print in
        // reverse insertion order (bash adds new entries at the head of
        // the chain, so iteration emits LIFO).
        // groupBy + sort entries by bucket ascending; flatten with reverse-
        // insertion-order inside each bucket. `toSortedMap` isn't available
        // on wasmJs (it relies on Java's TreeMap), so we sort the entry list
        // explicitly to keep the implementation common-compilable.
        val ordered =
            completeSpecs.entries
                .toList()
                .withIndex()
                .groupBy { (_, e) -> bashCompspecBucket(e.key) }
                .entries
                .sortedBy { it.key }
                .flatMap { (_, indexed) ->
                    indexed.sortedByDescending { it.index }.map { it.value }
                }
        for (entry in ordered) {
            stdio.stdout.writeUtf8(formatCompleteSpec(entry.value, entry.key))
            stdio.stdout.writeUtf8("\n")
        }
        completeDefault?.let {
            stdio.stdout.writeUtf8(formatCompleteSpec(it, null))
            stdio.stdout.writeUtf8(" -D\n")
        }
        completeEmpty?.let {
            stdio.stdout.writeUtf8(formatCompleteSpec(it, null))
            stdio.stdout.writeUtf8(" -E\n")
        }
        completeInitial?.let {
            stdio.stdout.writeUtf8(formatCompleteSpec(it, null))
            stdio.stdout.writeUtf8(" -I\n")
        }
        return 0
    }
    var anyMissing = false
    for (name in names) {
        val spec = completeSpecs[name]
        if (spec == null) {
            stdio.stderr.writeUtf8("complete: $name: no completion specification\n")
            anyMissing = true
        } else {
            stdio.stdout.writeUtf8(formatCompleteSpec(spec, name))
            stdio.stdout.writeUtf8("\n")
        }
    }
    return if (anyMissing) 1 else 0
}

/**
 * Internal parsed form of `complete` / `compgen` argv. Reused by both —
 * they share most of the option grammar.
 */
internal data class ParsedCompleteArgs(
    val actions: Set<CompleteAction>,
    val options: Set<CompleteOption>,
    val glob: String?,
    val wordlist: String?,
    val filter: String?,
    val prefix: String?,
    val suffix: String?,
    val function: String?,
    val command: String?,
    val names: List<String>,
    val printMode: Boolean,
    val removeMode: Boolean,
    val defaultD: Boolean,
    val defaultE: Boolean,
    val defaultI: Boolean,
    /** For compgen `-V varname`. */
    val arrayVarName: String?,
)

/**
 * Parse `complete`/`compgen` flag set. Returns null after writing a
 * diagnostic + usage line to stderr on any error. The caller maps the
 * null to exit 2.
 *
 * Note on flag-action conflict: `-r` is a *mode* for complete (remove)
 * but is *not a valid option* for compgen — compgen has no -r action, so
 * the caller passes [allowR] = false to make `compgen -r` error.
 */
internal suspend fun parseCompleteArgs(
    args: List<String>,
    stdio: Stdio,
    progName: String,
    usage: String,
    allowDashV: Boolean,
    allowDashR: Boolean = true,
    allowDashDEI: Boolean = true,
    diagPrefix: String = "",
): ParsedCompleteArgs? {
    val actions = mutableSetOf<CompleteAction>()
    val options = mutableSetOf<CompleteOption>()
    var glob: String? = null
    var wordlist: String? = null
    var filter: String? = null
    var prefix: String? = null
    var suffix: String? = null
    var function: String? = null
    var command: String? = null
    val names = mutableListOf<String>()
    var printMode = false
    var removeMode = false
    var defaultD = false
    var defaultE = false
    var defaultI = false
    var arrayVarName: String? = null

    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--" -> {
                i++
                while (i < args.size) {
                    names += args[i]
                    i++
                }
                break
            }

            a == "-A" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -A: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                val act = CompleteAction.fromLong(v)
                if (act == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: $v: invalid action name\n")
                    return null
                }
                actions += act
                i += 2
            }

            a == "-G" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -G: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                glob = v
                i += 2
            }

            a == "-W" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -W: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                wordlist = v
                i += 2
            }

            a == "-F" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -F: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                function = v
                i += 2
            }

            a == "-C" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -C: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                command = v
                i += 2
            }

            a == "-X" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -X: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                filter = v
                i += 2
            }

            a == "-P" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -P: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                prefix = v
                i += 2
            }

            a == "-S" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -S: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                suffix = v
                i += 2
            }

            a == "-o" -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -o: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                val opt = CompleteOption.fromName(v)
                if (opt == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: $v: invalid option name\n")
                    return null
                }
                options += opt
                i += 2
            }

            a == "-V" && allowDashV -> {
                val v = args.getOrNull(i + 1)
                if (v == null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -V: option requires an argument\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                if (!isValidIdentifier(v)) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: `$v': not a valid identifier\n")
                    return null
                }
                arrayVarName = v
                i += 2
            }

            a == "-p" -> {
                printMode = true
                i++
            }

            a == "-r" && allowDashR -> {
                removeMode = true
                i++
            }

            a == "-D" && allowDashDEI -> {
                defaultD = true
                i++
            }

            a == "-E" && allowDashDEI -> {
                defaultE = true
                i++
            }

            a == "-I" && allowDashDEI -> {
                defaultI = true
                i++
            }

            a.startsWith("-") && a.length > 1 && !a.startsWith("--") -> {
                // Bundled single-letter action flags: -cf, -bd, -k, etc.
                // Unknown chars cause an error covering the whole arg.
                var bad: Char? = null
                val toAdd = mutableSetOf<CompleteAction>()
                for (ch in a.drop(1)) {
                    val act = CompleteAction.fromFlag(ch)
                    if (act == null) {
                        bad = ch
                        break
                    }
                    toAdd += act
                }
                if (bad != null) {
                    stdio.stderr.writeUtf8("$diagPrefix$progName: -$bad: invalid option\n")
                    stdio.stderr.writeUtf8("$usage\n")
                    return null
                }
                actions += toAdd
                i++
            }

            else -> {
                names += a
                i++
            }
        }
    }

    return ParsedCompleteArgs(
        actions = actions,
        options = options,
        glob = glob,
        wordlist = wordlist,
        filter = filter,
        prefix = prefix,
        suffix = suffix,
        function = function,
        command = command,
        names = names,
        printMode = printMode,
        removeMode = removeMode,
        defaultD = defaultD,
        defaultE = defaultE,
        defaultI = defaultI,
        arrayVarName = arrayVarName,
    )
}

internal fun isValidIdentifier(s: String): Boolean {
    if (s.isEmpty()) return false
    if (!(s[0].isLetter() || s[0] == '_')) return false
    return s.all { it.isLetterOrDigit() || it == '_' }
}
