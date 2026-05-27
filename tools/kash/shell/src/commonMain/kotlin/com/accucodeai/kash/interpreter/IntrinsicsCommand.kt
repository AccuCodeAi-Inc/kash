package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.fs.Paths
import com.accucodeai.kash.interpreter.Interpreter.Stdio

// Command-resolution intrinsics (hash/type/command) + Classification.

/**
 * POSIX [`hash`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/hash.html).
 * Force-resolve named utilities into the cache, clear it (`-r`), or
 * print contents (no operands). Output format mirrors GNU bash.
 */
internal suspend fun Interpreter.runHashIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var clear = false
    var deleteNames = false
    val names = mutableListOf<String>()
    var i = 0
    var pAssoc: String? = null
    while (i < args.size) {
        when (val a = args[i]) {
            "-r" -> {
                clear = true
            }

            "-d" -> {
                // `hash -d NAME`: remove a single name from the cache.
                // Names listed after `-d` are removed individually; unknown
                // names diagnose + bump exit but don't abort.
                deleteNames = true
            }

            "-p" -> {
                if (i + 1 >= args.size) {
                    stdio.stderr.writeUtf8("hash: -p: option requires an argument\n")
                    return 2
                }
                pAssoc = args[i + 1]
                if (restricted && '/' in pAssoc) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: $pAssoc: restricted\n")
                    return 1
                }
                if (restricted) {
                    // Restricted mode: hash -p (even without `/`) refuses to
                    // verify or cache the target. Bash phrases this as
                    // `hash: <pAssoc>: not found`.
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: $pAssoc: not found\n")
                    return 1
                }
                i++
            }

            "--" -> {
                i++
                while (i < args.size) {
                    names += args[i]
                    i++
                }
                break
            }

            else -> {
                if (a.startsWith("-") && a.length > 1) {
                    stdio.stderr.writeUtf8("hash: invalid option: $a\n")
                    return 2
                }
                names += a
            }
        }
        i++
    }
    if (clear) {
        hashCache.clear()
        syncBashCmds()
        return 0
    }
    if (deleteNames) {
        if (names.isEmpty()) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: -d: name required\n")
            return 2
        }
        var exit = 0
        for (name in names) {
            if (hashCache.remove(name) == null) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: $name: not found\n")
                exit = 1
            }
            hashHits.remove(name)
        }
        syncBashCmds()
        return exit
    }
    if (pAssoc != null) {
        // POSIX `hash -p PATH NAME [NAME...]` trust-injects: store PATH as
        // the resolved location for each NAME without verifying it exists
        // on disk. The `-p` form is documented as a caller-supplied path
        // and is used to pre-populate the table during shell init.
        if (names.isEmpty()) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: -p: missing target name\n")
            return 2
        }
        for (name in names) {
            if ('/' in name) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}hash: $name: hash a name, not a path\n")
                return 1
            }
            hashCache[name] = Resolved.Script(pAssoc)
        }
        syncBashCmds()
        return 0
    }
    if (names.isEmpty()) {
        // Pull in `BASH_CMDS[k]=v` direct writes that bypassed the
        // builtin so they show up in the listing.
        absorbBashCmds()
        if (hashCache.isEmpty()) {
            stdio.stdout.writeUtf8("hash: hash table empty\n")
            return 0
        }
        // Bash lists the `hash` table in command-hash iteration order — the
        // same 256-bucket FNV walk that orders BASH_CMDS — NOT sorted by
        // path. Reuse [BashAssocOrder.commandHashOrder] over the cache keys
        // (insertion order) so the listing matches `${!BASH_CMDS[@]}`.
        stdio.stdout.writeUtf8("hits\tcommand\n")
        for (name in BashAssocOrder.commandHashOrder(hashCache.keys)) {
            val r = hashCache[name] ?: continue
            val path =
                when (r) {
                    is Resolved.Builtin -> r.displayPath
                    is Resolved.Function -> name
                    is Resolved.Script -> r.path
                    is Resolved.Intrinsic -> name
                }
            // Bash right-pads the hit count to width 4 (`   1`, `  42`, ...)
            // so the tab-separated `command` column lines up visually.
            val padded = (hashHits[name] ?: 0).toString().padStart(4)
            stdio.stdout.writeUtf8("$padded\t$path\n")
        }
        return 0
    }
    var exit = 0
    for (name in names) {
        val direct = registry[name]
        if (direct != null && direct.isSpecial) continue
        if ('/' in name) {
            stdio.stderr.writeUtf8("hash: $name: hash a name, not a path\n")
            exit = 1
            continue
        }
        // POSIX: "store the location of utility in the cache".
        var found = false
        for (dir in splitPath(env["PATH"])) {
            val candidate = if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name"
            val exists =
                try {
                    fs.exists(candidate) && !fs.isDirectory(candidate)
                } catch (_: Throwable) {
                    false
                }
            if (!exists) continue
            val r = resolveFsPath(candidate) ?: continue
            hashCache[name] = r
            syncBashCmds()
            found = true
            break
        }
        if (!found) {
            stdio.stderr.writeUtf8("hash: $name: not found\n")
            exit = 1
        }
    }
    return exit
}

internal suspend fun Interpreter.runTypeIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var brief = false
    var path = false
    var forcePath = false // `-P`: PATH search only, bypass alias/keyword/function/builtin
    var showAll = false // `-a`: list ALL classifications (function + builtin + path)
    val names = mutableListOf<String>()
    for (a in args) {
        when {
            a == "-t" -> {
                brief = true
            }

            a == "-p" -> {
                path = true
            }

            a == "-P" -> {
                forcePath = true
                path = true
            }

            a == "-a" -> {
                showAll = true
            }

            a == "-f" -> {
                Unit
            }

            a == "--" -> {
                Unit
            }

            a.startsWith("-") && a.length > 1 -> {
                // bash: unrecognized type option → "<scriptpath>: line N:
                // type: -X: invalid option" + usage line, exit 2.
                stdio.stderr.writeUtf8("${shellDiagPrefix()}type: $a: invalid option\n")
                stdio.stderr.writeUtf8("type: usage: type [-afptP] name [name ...]\n")
                return 2
            }

            else -> {
                names += a
            }
        }
    }
    var exit = 0
    for (name in names) {
        // `type -P` runs a PATH-only search (bypassing aliases, functions,
        // keywords, and shell builtins). Falls through to the regular
        // hash-then-PATH lookup so a hashed entry still wins.
        val classifications: List<Classification> =
            when {
                forcePath -> listOf(classifyPathOnly(name))
                showAll -> classifyAll(name)
                else -> listOf(classify(name))
            }
        val c = classifications.first()
        if (c is Classification.NotFound) {
            // Bash silently exits 1 for `type -t NAME` when NAME isn't
            // found; only the non-brief forms print the diagnostic.
            // `type -p NAME` (the path form) is also silent.
            if (!brief && !path) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}type: $name: not found\n")
            }
            exit = 1
            continue
        }
        for (c in classifications) {
            when {
                brief -> {
                    val tag =
                        when (c) {
                            Classification.Keyword -> {
                                "keyword"
                            }

                            Classification.Function -> {
                                "function"
                            }

                            is Classification.Alias -> {
                                "alias"
                            }

                            is Classification.SpecialBuiltin -> {
                                "builtin"
                            }

                            is Classification.Intrinsic -> {
                                "builtin"
                            }

                            // bash convention: kind=TOOL behaves like a file at /usr/bin/<name>;
                            // BUILTIN reports as "builtin".
                            is Classification.Utility -> {
                                if (c.spec.kind == com.accucodeai.kash.api.CommandKind.TOOL) "file" else "builtin"
                            }

                            is Classification.File -> {
                                "file"
                            }

                            is Classification.Hashed -> {
                                "file"
                            }

                            Classification.NotFound -> {
                                error("unreachable")
                            }
                        }
                    stdio.stdout.writeUtf8("$tag\n")
                }

                path -> {
                    val p =
                        when (c) {
                            is Classification.Utility -> c.path
                            is Classification.File -> c.path
                            is Classification.Hashed -> c.path
                            else -> null
                        }
                    if (p != null) stdio.stdout.writeUtf8("$p\n")
                }

                else -> {
                    // bash convention for non-brief `type`: BUILTIN/INTRINSIC report
                    // as "shell builtin"; TOOL reports its `/usr/bin/<name>` path.
                    // Keeps `type echo` → "echo is a shell builtin" stable while
                    // `type grep` correctly surfaces `/usr/bin/grep`.
                    val msg =
                        when (c) {
                            Classification.Keyword -> {
                                "$name is a shell keyword"
                            }

                            Classification.Function -> {
                                // bash `type funcname` prints "X is a function"
                                // immediately followed by the parsed body in
                                // re-parseable form (so `eval "$(type X | sed 1d)"`
                                // round-trips).
                                val fn = functions[name]
                                if (fn != null) {
                                    "$name is a function\n" + AstPrinter.functionBody(fn).trimEnd('\n')
                                } else {
                                    "$name is a function"
                                }
                            }

                            is Classification.Alias -> {
                                "$name is aliased to `${c.value}'"
                            }

                            is Classification.SpecialBuiltin -> {
                                // "special" qualifier shown only in POSIX mode
                                // (see Intrinsic branch).
                                if (posixModeRuntime) {
                                    "$name is a special shell builtin"
                                } else {
                                    "$name is a shell builtin"
                                }
                            }

                            is Classification.Intrinsic -> {
                                // The "special" qualifier in `type` output is
                                // gated on POSIX mode — outside POSIX mode every
                                // intrinsic is just "shell builtin". POSIX mode
                                // distinguishes the §2.14 special builtins so
                                // scripts can detect the precedence/assignment-
                                // persistence behavior they get.
                                if (c.entry.isSpecial && posixModeRuntime) {
                                    "$name is a special shell builtin"
                                } else {
                                    "$name is a shell builtin"
                                }
                            }

                            is Classification.Utility -> {
                                // Registry-level (tool-declared) alias — e.g. CommandSpec
                                // for `python3` lists `python` in `aliases`. Surface that.
                                if (c.spec.name != name) {
                                    "$name is an alias for ${c.spec.name}"
                                } else if (c.spec.kind == com.accucodeai.kash.api.CommandKind.TOOL) {
                                    "$name is ${c.path}"
                                } else {
                                    "$name is a shell builtin"
                                }
                            }

                            is Classification.File -> {
                                "$name is ${c.path}"
                            }

                            is Classification.Hashed -> {
                                "$name is hashed (${c.path})"
                            }

                            Classification.NotFound -> {
                                error("unreachable")
                            }
                        }
                    stdio.stdout.writeUtf8("$msg\n")
                }
            }
        }
    }
    return exit
}

/**
 * Like [classify] but returns every classification that matches
 * [name] in the order bash's `type -a` reports them: function (if
 * defined) → special builtin / intrinsic → regular builtin → PATH
 * walk entries. Used only by `type -a`; other call sites want the
 * single-best match.
 */
internal suspend fun Interpreter.classifyAll(name: String): List<Classification> {
    val out = mutableListOf<Classification>()
    if (interactive || shoptOptions["expand_aliases_user_set"] == true) {
        aliases[name]?.let { out += Classification.Alias(name, it) }
    }
    if (name in Interpreter.RESERVED_KEYWORDS) out += Classification.Keyword
    val intrinsic = com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]
    val direct = registry[name]
    // Order matches lookup precedence: in posix mode special
    // builtins / intrinsics come before functions; otherwise the
    // function takes precedence and is listed first.
    if (posixModeRuntime) {
        if (intrinsic?.isSpecial == true) out += Classification.Intrinsic(intrinsic)
        if (direct != null && direct.isSpecial) out += Classification.SpecialBuiltin(direct)
        if (functions.containsKey(name)) out += Classification.Function
        if (intrinsic != null && !intrinsic.isSpecial) out += Classification.Intrinsic(intrinsic)
    } else {
        if (functions.containsKey(name)) out += Classification.Function
        if (intrinsic != null) out += Classification.Intrinsic(intrinsic)
        if (direct != null && direct.isSpecial) out += Classification.SpecialBuiltin(direct)
    }
    if (direct != null && !direct.isSpecial &&
        com.accucodeai.kash.api.CommandTag.BASH_BUILTIN in direct.tags
    ) {
        out += Classification.Utility(direct, path = name)
    }
    if ('/' !in name) {
        for (dir in splitPath(env["PATH"])) {
            val absolute = if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name"
            val exists =
                try {
                    fs.exists(absolute) && !fs.isDirectory(absolute)
                } catch (_: Throwable) {
                    false
                }
            if (exists) out += classifyPath(absolute, displayPath = if (dir.isEmpty()) "./$name" else absolute)
        }
    } else {
        out += classifyPath(Paths.resolve(cwd, name))
    }
    return if (out.isEmpty()) listOf(Classification.NotFound) else out
}

/**
 * Classification used by `type`, `command -v`, and `command -V`.
 * Mirrors [resolveCommand]'s POSIX [XCU §2.9.1.1](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/V3_chap02.html#tag_18_09_01_01)
 * order but stays synchronous — never reads file contents — so callers
 * outside [runSimple] can use it.
 */
internal sealed class Classification {
    object Keyword : Classification()

    object Function : Classification()

    /** Runtime alias declared via `alias name=value`. */
    data class Alias(
        val name: String,
        val value: String,
    ) : Classification()

    data class SpecialBuiltin(
        val spec: CommandSpec,
    ) : Classification()

    /** Interpreter-owned intrinsic (see [com.accucodeai.kash.intrinsics.IntrinsicCatalog]). */
    data class Intrinsic(
        val entry: com.accucodeai.kash.intrinsics.IntrinsicEntry,
    ) : Classification()

    /** Special-cased: builtin shadowed by the PATH walk (always with a path). */
    data class Utility(
        val spec: CommandSpec,
        val path: String,
    ) : Classification()

    /** A real-FS file resolvable as a script via PATH. */
    data class File(
        val path: String,
    ) : Classification()

    /** Pre-cached by `hash -p PATH NAME`; trusted target, not verified on disk. */
    data class Hashed(
        val path: String,
    ) : Classification()

    object NotFound : Classification()
}

internal suspend fun Interpreter.classify(name: String): Classification {
    // POSIX `type` / `command -v` report aliases ahead of every other
    // classification when alias substitution is active. Bash gates this
    // on `expand_aliases`: ON by default in interactive shells, OFF in
    // non-interactive scripts unless `shopt -s expand_aliases` flips it.
    // bash/type.tests pins this — lines 56-62 expect `type morealias`
    // to fail with "not found" when expand_aliases is off, then succeed
    // after line 65's `shopt -s expand_aliases`.
    if (interactive || shoptOptions["expand_aliases_user_set"] == true) {
        aliases[name]?.let { return Classification.Alias(name, it) }
    }
    if (name in Interpreter.RESERVED_KEYWORDS) return Classification.Keyword
    // Mirror [resolveCommand]'s posix-vs-non-posix ordering: in posix
    // mode special builtins beat functions; otherwise functions win.
    val direct = registry[name]
    if (posixModeRuntime) {
        com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]?.let { entry ->
            if (entry.isSpecial) return Classification.Intrinsic(entry)
        }
        if (direct != null && direct.isSpecial) return Classification.SpecialBuiltin(direct)
    }
    if (functions.containsKey(name)) return Classification.Function
    if (!posixModeRuntime) {
        com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]?.let { entry ->
            if (entry.isSpecial) return Classification.Intrinsic(entry)
        }
        if (direct != null && direct.isSpecial) return Classification.SpecialBuiltin(direct)
    }
    com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]?.let { entry ->
        return Classification.Intrinsic(entry)
    }
    // Regular non-special built-ins from the [registry] — mirrors the
    // parallel branch in [resolveCommand]. Only specs tagged
    // [CommandTag.BASH_BUILTIN] win over the PATH walk; the rest of the
    // POSIX standard utilities (`cat`, `ls`, `grep`, …) carry the POSIX
    // tag but no BASH_BUILTIN tag and so fall through to PATH the way
    // bash does. Routed via [Classification.Utility] so the renderer can
    // also detect a registry-level alias (`direct.name != name`) and
    // surface "is an alias for" — needed for the python3-registers-as-
    // python pattern.
    if (direct != null && com.accucodeai.kash.api.CommandTag.BASH_BUILTIN in direct.tags) {
        return Classification.Utility(direct, path = name)
    }
    // Path-qualified — resolve against cwd first (matches resolveCommand).
    if ('/' in name) {
        return classifyPath(Paths.resolve(cwd, name))
    }
    // Hash cache lookup mirrors resolveCommand. Path is the user-supplied
    // hint from `hash -p PATH NAME`; we don't verify it exists (the POSIX
    // `hash -p` form is "trust the caller") — `type` reports it with the
    // documented `NAME is hashed (PATH)` phrasing. Bumps the hit counter
    // for parity with resolveCommand so `type` lookups count toward the
    // `hash` listing's hits column.
    hashCache[name]?.let { r ->
        val p =
            when (r) {
                is Resolved.Builtin -> r.displayPath
                is Resolved.Script -> r.path
                is Resolved.Function -> null
                is Resolved.Intrinsic -> null
            }
        if (p != null) {
            hashHits[name] = (hashHits[name] ?: 0) + 1
            return Classification.Hashed(p)
        }
    }
    for (dir in splitPath(env["PATH"])) {
        val absolute = if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name"
        val exists =
            try {
                fs.exists(absolute) && !fs.isDirectory(absolute)
            } catch (_: Throwable) {
                false
            }
        if (!exists) continue
        val display = if (dir.isEmpty()) "./$name" else absolute
        return classifyPath(absolute, displayPath = display)
    }
    return Classification.NotFound
}

/**
 * `type -P NAME` style lookup: aliases, keywords, functions, and shell
 * builtins are skipped entirely; only the hash cache and PATH walk
 * contribute. If a hash entry exists, it wins (with hit-count bump,
 * matching the regular [classify] semantics).
 */
internal suspend fun Interpreter.classifyPathOnly(name: String): Classification {
    if ('/' in name) return classifyPath(Paths.resolve(cwd, name))
    hashCache[name]?.let { r ->
        val p =
            when (r) {
                is Resolved.Builtin -> r.displayPath
                is Resolved.Script -> r.path
                is Resolved.Function -> null
                is Resolved.Intrinsic -> null
            }
        if (p != null) {
            hashHits[name] = (hashHits[name] ?: 0) + 1
            return Classification.Hashed(p)
        }
    }
    for (dir in splitPath(env["PATH"])) {
        // POSIX-shell convention: empty PATH element means "current
        // directory". When `type -P` (or `type` more generally) reports it,
        // the relative `./NAME` form is preferred over the absolute cwd
        // resolution — that lets a `type -P e` inside `cd /tmp/xyz` report
        // `./e` rather than `/tmp/xyz/e`, regardless of what cwd is.
        val absolute = if (dir.isEmpty()) Paths.resolve(cwd, name) else "$dir/$name"
        val exists =
            try {
                fs.exists(absolute) && !fs.isDirectory(absolute)
            } catch (_: Throwable) {
                false
            }
        if (!exists) continue
        val display = if (dir.isEmpty()) "./$name" else absolute
        return classifyPath(absolute, displayPath = display)
    }
    return Classification.NotFound
}

internal suspend fun Interpreter.classifyPath(
    path: String,
    displayPath: String = path,
): Classification {
    if (!fs.exists(path) || fs.isDirectory(path)) return Classification.NotFound
    // Per POSIX `type`/`command`: a path-qualified name is reported as a
    // plain file regardless of what command spec sits behind it. The
    // builtin-vs-file distinction only applies to bare names that go
    // through PATH walk. Otherwise `type /bin/sh` (a BUILTIN registry
    // entry in kash) would mis-report as "builtin"; bash always says
    // "/bin/sh is /bin/sh".
    return Classification.File(displayPath)
}

/** Back-compat shim — kept only because [classifyName] is named in some old tests. */
internal suspend fun Interpreter.classifyName(name: String): String? =
    when (classify(name)) {
        Classification.Keyword -> "keyword"
        Classification.Function -> "function"
        is Classification.Alias -> "alias"
        is Classification.SpecialBuiltin -> "builtin"
        is Classification.Intrinsic -> "builtin"
        is Classification.Utility -> "builtin"
        is Classification.File -> "file"
        is Classification.Hashed -> "file"
        Classification.NotFound -> null
    }

internal suspend fun Interpreter.runCommandIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var verbose = false
    var brief = false
    var i = 0
    while (i < args.size && args[i].startsWith("-")) {
        when (args[i]) {
            "-v" -> {
                brief = true
            }

            "-V" -> {
                verbose = true
            }

            "-p" -> {
                if (restricted) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}command: -p: restricted\n")
                    return 1
                }
            }

            "--" -> {
                i++
                break
            }

            else -> {
                Unit
            }
        }
        i++
    }
    if (brief || verbose) {
        var exit = 0
        for (n in args.drop(i)) {
            when (val c = classify(n)) {
                Classification.NotFound -> {
                    // bash: `command -V FOO` for an unknown name prints a
                    // `<scriptpath>: line N: command: FOO: not found`
                    // diagnostic to stderr; `command -v FOO` is silent (POSIX).
                    if (verbose) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}command: $n: not found\n")
                    }
                    exit = 1
                }

                Classification.Keyword -> {
                    // POSIX [§command](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/command.html):
                    // keywords aren't utilities. bash convention diverges
                    // slightly — `command -v while` echoes the keyword
                    // name (treating it like a function/builtin lookup),
                    // not silent fail. `command -V` prints the
                    // "is a shell keyword" classification.
                    if (brief) stdio.stdout.writeUtf8("$n\n")
                    if (verbose) stdio.stdout.writeUtf8("$n is a shell keyword\n")
                }

                Classification.Function -> {
                    if (brief) stdio.stdout.writeUtf8("$n\n")
                    if (verbose) {
                        stdio.stdout.writeUtf8("$n is a function\n")
                        // Bash `command -V func` (like `type func`) follows
                        // the header with the body in declare-f format.
                        functions[n]?.let { stdio.stdout.writeUtf8(AstPrinter.functionBody(it)) }
                    }
                }

                is Classification.Alias -> {
                    // `command -v` round-trips an alias as a redefinable assignment.
                    if (brief) stdio.stdout.writeUtf8("alias $n='${escapeSingleQuoted(c.value)}'\n")
                    if (verbose) stdio.stdout.writeUtf8("$n is aliased to `${c.value}'\n")
                }

                is Classification.SpecialBuiltin -> {
                    if (brief) stdio.stdout.writeUtf8("$n\n")
                    if (verbose) {
                        val tag = if (posixModeRuntime) "special shell builtin" else "shell builtin"
                        stdio.stdout.writeUtf8("$n is a $tag\n")
                    }
                }

                is Classification.Intrinsic -> {
                    if (brief) stdio.stdout.writeUtf8("$n\n")
                    if (verbose) {
                        val tag =
                            if (c.entry.isSpecial && posixModeRuntime) "special shell builtin" else "shell builtin"
                        stdio.stdout.writeUtf8("$n is a $tag\n")
                    }
                }

                is Classification.Utility -> {
                    if (c.spec.name != n) {
                        // Tool-declared registry alias (e.g. python → python3).
                        if (brief) stdio.stdout.writeUtf8("${c.path}\n")
                        if (verbose) stdio.stdout.writeUtf8("$n is an alias for ${c.spec.name}\n")
                    } else if (c.spec.kind == com.accucodeai.kash.api.CommandKind.TOOL) {
                        if (brief) stdio.stdout.writeUtf8("${c.path}\n")
                        if (verbose) stdio.stdout.writeUtf8("$n is ${c.path}\n")
                    } else {
                        if (brief) stdio.stdout.writeUtf8("$n\n")
                        if (verbose) stdio.stdout.writeUtf8("$n is a shell builtin\n")
                    }
                }

                is Classification.File -> {
                    if (brief) stdio.stdout.writeUtf8("${c.path}\n")
                    if (verbose) stdio.stdout.writeUtf8("$n is ${c.path}\n")
                }

                is Classification.Hashed -> {
                    if (brief) stdio.stdout.writeUtf8("${c.path}\n")
                    if (verbose) stdio.stdout.writeUtf8("$n is hashed (${c.path})\n")
                }
            }
        }
        return exit
    }
    if (i >= args.size) return 0
    val name = args[i]
    val rest = args.drop(i + 1)
    com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]?.let {
        return runIntrinsic(name, rest, stdio) ?: 0
    }
    // POSIX `command NAME` with a path-qualified NAME: resolve against the
    // FS the same way the dispatcher would. ToolsFs (`/bin/false`,
    // `/usr/bin/echo`, ...) is the common case — registry is keyed by
    // bare name, so a path lookup misses there.
    val byPath: CommandSpec? = if ('/' in name) fs.commandSpec(name) else null
    val spec =
        registry[name] ?: byPath ?: run {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}$name: command not found\n")
            return 127
        }
    return runResolvedSpec(spec, name, rest, emptyMap(), stdio)
}
