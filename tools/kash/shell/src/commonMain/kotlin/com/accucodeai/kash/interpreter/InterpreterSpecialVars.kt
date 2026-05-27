package com.accucodeai.kash.interpreter

/**
 * Register hook-backed [Variable]s for bash's dynamic specials.
 *
 * A dynamic-variable read hook (like bash's RANDOM/SECONDS): a
 * scalar Variable whose value is computed on each read instead of
 * stored. This means [Expander.lookup] / [VarTable.find] return the
 * live value uniformly — no per-name special-case branches.
 *
 * Read-only specials (RANDOM, LINENO, BASHPID, PPID, `$-`,
 * EPOCHSECONDS, EPOCHREALTIME) install only a [Variable.getter].
 *
 * Read-write specials (SECONDS — bash allows `SECONDS=N` to rebase
 * the elapsed counter) install both a getter and a setter; the
 * setter mutates the closed-over base so subsequent reads reflect
 * the new origin.
 *
 * Synthesized arrays (FUNCNAME, BASH_LINENO, BASH_SOURCE, DIRSTACK,
 * PIPESTATUS, BASH_REMATCH) install [Variable.indexedGetter] /
 * [Variable.assocGetter].
 */
internal fun Interpreter.registerSpecialVariables() {
    // Seed IFS to the POSIX default if the embedder didn't pre-populate
    // it. Bash always has IFS set at shell start (space/tab/newline);
    // tests like `${IFS+...}` depend on `set` returning true. Field-
    // splitting sites already fall back to the same string when IFS
    // happens to be absent, so this only adds the visible binding.
    if (!env.containsKey("IFS")) env["IFS"] = " \t\n"

    // ---------------- read-only scalar specials ----------------

    // RANDOM — bash returns a fresh 0..32767 each read. Writing reseeds:
    // `RANDOM=42` switches to a deterministic stream starting at seed 42.
    // We hold a local [kotlin.random.Random] instance so the setter can
    // swap it out; with no setter call yet, the getter delegates to
    // [randomSource] so embedders (tests, replay) can pin a stream.
    val randomState = arrayOfNulls<kotlin.random.Random>(1)
    varTable.findOrCreate("RANDOM").apply {
        getter = {
            val rng = randomState[0]
            (rng?.nextInt(0, 32768) ?: randomSource()).toString()
        }
        setter = { s ->
            val n = s.toLongOrNull()
            if (n != null) randomState[0] = kotlin.random.Random(n)
        }
    }

    varTable.findOrCreate("LINENO").apply {
        getter = { (callStack.currentLine - callStack.linenoOffset).coerceAtLeast(0).toString() }
    }

    varTable.findOrCreate("BASHPID").apply {
        getter = { process.pid.toString() }
    }

    varTable.findOrCreate("BASH_SUBSHELL").apply {
        getter = { bashSubshellDepth.toString() }
    }

    varTable.findOrCreate("PPID").apply {
        getter = { shellPpid.toString() }
    }

    varTable.findOrCreate("EPOCHSECONDS").apply {
        getter = { clock.now().epochSeconds.toString() }
    }

    // $UID / $EUID — the effective and real user id. Bash sets these
    // automatically at session start; kash backs them with the injected
    // [com.accucodeai.kash.api.user.UserDatabase]. kash is single-user
    // (no setuid), so real and effective are identical.
    varTable.findOrCreate("UID").apply {
        getter = { userDb.current().uid.toString() }
    }
    varTable.findOrCreate("EUID").apply {
        getter = { userDb.current().uid.toString() }
    }
    varTable.findOrCreate("GROUPS").apply {
        attrs += VarAttr.Indexed
        indexedGetter = {
            val groups = userDb.current().groups
            val map = LinkedHashMap<Int, String>(groups.size)
            for ((i, g) in groups.withIndex()) map[i] = g.first.toString()
            map
        }
    }

    // ---------------- synthesized indexed arrays ----------------

    // DIRSTACK[0] is always the current cwd; DIRSTACK[1..N] are the
    // entries pushed by `pushd`, top-most first. Always populated so
    // `~+0` / `${DIRSTACK[0]}` reliably yields cwd.
    varTable.findOrCreate("DIRSTACK").apply {
        attrs += VarAttr.Indexed
        indexedGetter = {
            val map = LinkedHashMap<Int, String>(dirStack.size + 1)
            map[0] = cwd
            for ((i, d) in dirStack.withIndex()) map[i + 1] = d
            map
        }
    }

    // FUNCNAME / BASH_LINENO / BASH_SOURCE — call-stack views.
    // [Interpreter.callStack] holds functionNameStack and
    // callerLineStack innermost-LAST; the bash-visible arrays index
    // innermost-first with a synthetic "main" frame at depth.
    varTable.findOrCreate("FUNCNAME").apply {
        attrs += VarAttr.Indexed
        indexedGetter = {
            val depth = functionNameStack.size
            if (depth == 0) {
                emptyMap()
            } else {
                val map = LinkedHashMap<Int, String>(depth + 1)
                for (i in 0 until depth) map[i] = functionNameStack[depth - 1 - i]
                map[depth] = "main"
                map
            }
        }
    }

    varTable.findOrCreate("BASH_LINENO").apply {
        attrs += VarAttr.Indexed
        indexedGetter = {
            val depth = functionNameStack.size
            if (depth == 0) {
                mapOf(0 to "0")
            } else {
                val map = LinkedHashMap<Int, String>(depth + 1)
                for (i in 0 until depth) map[i] = callerLineStack[depth - 1 - i].toString()
                map[depth] = "0"
                map
            }
        }
    }

    varTable.findOrCreate("BASH_SOURCE").apply {
        attrs += VarAttr.Indexed
        indexedGetter = {
            val depth = functionNameStack.size
            val sourcePath =
                if (shoptOptions["bash_source_fullpath"] == true && !dollarZero.startsWith("/")) {
                    if (cwd == "/") "/$dollarZero" else "$cwd/$dollarZero"
                } else {
                    dollarZero
                }
            if (depth == 0) {
                mapOf(0 to sourcePath)
            } else {
                val map = LinkedHashMap<Int, String>(depth + 1)
                for (i in 0 until depth) map[i] = sourcePath
                map[depth] = sourcePath
                map
            }
        }
    }

    varTable.findOrCreate("EPOCHREALTIME").apply {
        getter = {
            val n = clock.now()
            val micros = (n.nanosecondsOfSecond / 1000).toString().padStart(6, '0')
            "${n.epochSeconds}.$micros"
        }
    }

    // `$-` — single-letter option flags currently in effect.
    varTable.findOrCreate("-").apply {
        getter = { composeDashFlagsString() }
    }

    // ---------------- read-write SECONDS ----------------
    //
    // Bash: `SECONDS` defaults to "seconds since shell start"; a
    // write rebases it (`SECONDS=0` resets, `SECONDS=100` starts the
    // counter at 100). We hold the base offset and adjust on write.
    val secondsBase = LongArray(1) // mutable holder — closures can read/write
    secondsBase[0] = 0L
    varTable.findOrCreate("SECONDS").apply {
        getter = { (clock.elapsedSinceShellStart().inWholeSeconds + secondsBase[0]).toString() }
        setter = { s ->
            val n = s.toLongOrNull() ?: 0L
            secondsBase[0] = n - clock.elapsedSinceShellStart().inWholeSeconds
        }
    }
}

/** Compose `$-` — same shape as `Interpreter.composeDashFlags()`, expression-named to avoid a forward ref. */
private fun Interpreter.composeDashFlagsString(): String {
    val sb = StringBuilder()
    if (errexit) sb.append('e')
    if (nounset) sb.append('u')
    if (noclobber) sb.append('C')
    if (pipefail) sb.append('o')
    // bash always reports `h` (hashall) and `B` (brace expansion) on by default
    sb.append('h')
    sb.append('B')
    if (interactive) sb.append('i')
    return sb.toString()
}
