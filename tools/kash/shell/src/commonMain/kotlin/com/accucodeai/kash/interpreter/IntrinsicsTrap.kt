package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.traps.TrapAction

// POSIX trap intrinsic + trap-table listing.

// POSIX trap dispatch:
// https://pubs.opengroup.org/onlinepubs/9699919799/utilities/trap.html
//   Forms: `trap` (list), `trap '' SIG…` (ignore), `trap - SIG…` (default),
//   `trap 'CMD' SIG…` (set handler). Only EXIT actually fires today via
//   [runExitTrap]; INT/TERM/HUP/USR* dispatch is wired only for signals
//   delivered to a background job via `kill %N` (cancels the coroutine).
//   Foreground async signals require a REPL signal source.

/**
 * Write the current trap-table contents in the POSIX listing format
 * `trap -- '<script>' <SIG>`. With [filter] non-null, emit only
 * entries whose signal is in the filter (used by `trap -p SIG`).
 */
internal suspend fun Interpreter.printTrapTable(
    stdio: Stdio,
    filter: Set<KashSignal>?,
) {
    for ((sig, action) in trapTable.entries()) {
        if (filter != null && sig !in filter) continue
        val script =
            when (action) {
                TrapAction.Ignore -> ""
                is TrapAction.Handler -> action.script
            }
        // Bash prints real signals with the `SIG` prefix (`SIGHUP`,
        // `SIGINT`, …) and pseudo-signals (EXIT/DEBUG/RETURN/ERR — number
        // ≤ 0 in kash's scheme) without one. POSIX is loose on the
        // prefix, but the test corpus expects bash's form.
        val nameForListing = if (sig.number > 0) "SIG${sig.name}" else sig.name
        stdio.stdout.writeUtf8("trap -- '${script.replace("'", "'\\''")}' $nameForListing\n")
    }
}

internal suspend fun Interpreter.runTrapIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    // Bash usage line emitted after every option error.
    val usage = "trap: usage: trap [-Plp] [[action] signal_spec ...]\n"
    // Parse leading flags: -l, -p, -P. `--` ends option scan; first non-flag
    // is taken as the action or signal (per bash's positional rules below).
    var listSignals = false
    var printAll = false
    var printAction = false
    var idx = 0
    while (idx < args.size) {
        val a = args[idx]
        if (a == "--") {
            idx++
            break
        }
        if (a.length < 2 || a[0] != '-') break
        // bash: `-` alone is the "reset to default" form, not an option.
        if (a == "-") break
        for (c in a.substring(1)) {
            when (c) {
                'l' -> {
                    listSignals = true
                }

                'p' -> {
                    printAll = true
                }

                'P' -> {
                    printAction = true
                }

                else -> {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}trap: -$c: invalid option\n")
                    stdio.stderr.writeUtf8(usage)
                    return 2
                }
            }
        }
        idx++
    }
    if (printAll && printAction) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}trap: cannot specify both -p and -P\n")
        return 2
    }
    if (listSignals) {
        // POSIX `trap -l`: list signal names. Same `<num>) SIG<NAME>` format
        // that `kill -l` uses; pseudo-signals (EXIT/DEBUG/RETURN/ERR —
        // number <= 0) are excluded since they have no signum.
        for (s in KashSignal.ALL) {
            if (s.number <= 0) continue
            stdio.stdout.writeUtf8("${s.number}) SIG${s.name}\n")
        }
        return 0
    }
    val rest = args.drop(idx)
    if (printAction) {
        // bash: `trap -P` with no signal names is an error.
        if (rest.isEmpty()) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}trap: -P requires at least one signal name\n")
            stdio.stderr.writeUtf8(usage)
            return 2
        }
        var status = 0
        for (a in rest) {
            val sig = KashSignal.parse(a)
            if (sig == null) {
                stdio.stderr.writeUtf8("trap: $a: invalid signal specification\n")
                status = 1
                continue
            }
            val entry = trapTable.get(sig)
            if (entry is TrapAction.Handler) {
                stdio.stdout.writeUtf8("${entry.script}\n")
            }
        }
        return status
    }
    if (printAll) {
        if (rest.isEmpty()) {
            printTrapTable(stdio, filter = null)
            return 0
        }
        val filter = mutableSetOf<KashSignal>()
        for (a in rest) {
            val sig = KashSignal.parse(a)
            if (sig == null) {
                stdio.stderr.writeUtf8("trap: $a: invalid signal specification\n")
                return 1
            }
            filter += sig
        }
        printTrapTable(stdio, filter = filter)
        return 0
    }
    if (rest.isEmpty()) {
        printTrapTable(stdio, filter = null)
        return 0
    }
    val action: TrapAction?
    val sigStart: Int
    val first = rest[0]
    // bash-compatible: a single-arg sigspec REVERTS that signal — it's
    // not a listing (listing is only via -p / -P). This is the historical
    // POSIX-extension behavior every shell with a `trap` builtin honors:
    //   - `trap 0`        → revert EXIT
    //   - `trap HUP`      → revert HUP
    //   - `trap 512`      → 512 not a signal → action with no signals → usage
    // Listing is only via `-p` / `-P`.
    val firstIsSignal = first.isNotEmpty() && KashSignal.parse(first) != null
    if (rest.size == 1 && firstIsSignal) {
        action = null // revert
        sigStart = 0 // treat the only arg as the signal
    } else if (first == "-") {
        action = null // reset
        sigStart = 1
    } else if (first == "") {
        action = TrapAction.Ignore
        sigStart = 1
    } else {
        action = TrapAction.Handler(first)
        sigStart = 1
    }
    if (sigStart >= rest.size) {
        // Action provided but no signals. Bash usage error for `trap CMD`
        // where CMD doesn't parse as a signal (e.g. `trap 512`). For empty
        // arg `trap ''` and reset `trap -`, bash is observably silent (per
        // the trap.tests test corpus, lines 120 / "trap ''" produce no
        // output even though trap.def's code path emits usage). Match
        // observed behavior.
        if (first.isEmpty() || first == "-") return 0
        stdio.stderr.writeUtf8(usage)
        return 2
    }
    var status = 0
    for (i in sigStart until rest.size) {
        val sig = KashSignal.parse(rest[i])
        if (sig == null) {
            stdio.stderr.writeUtf8("trap: ${rest[i]}: invalid signal specification\n")
            status = 1
            continue
        }
        // POSIX: SIGKILL and SIGSTOP cannot be caught or ignored. Reject
        // any attempt to register a disposition (set, ignore, or reset).
        if (sig === com.accucodeai.kash.api.signal.SigKill || sig === com.accucodeai.kash.api.signal.SigStop) {
            stdio.stderr.writeUtf8("trap: SIG${sig.name}: cannot be trapped or reset\n")
            status = 1
            continue
        }
        // POSIX: signals ignored at shell startup cannot be re-trapped
        // or reset. Bash silently no-ops (no diagnostic). The child shell
        // path snapshots this set via [TrapTable.sealStartupIgnored].
        if (trapTable.isStartupIgnored(sig)) continue
        if (action == null) {
            trapTable.reset(sig)
        } else {
            trapTable.set(sig, action)
        }
        // bash: setting/reverting a DEBUG or RETURN trap inside an untraced
        // function makes that trap apply to the rest of the function body.
        // The outer (inherited) DEBUG/RETURN was suppressed on function
        // entry; once the function installs its own handler, fire-time
        // gating should let it run. Set the current frame's trace bit so
        // [inheritsTrapDebugReturn] returns true for subsequent commands.
        if ((
                sig === com.accucodeai.kash.api.signal.SigDebug ||
                    sig === com.accucodeai.kash.api.signal.SigReturn
            ) &&
            traceFramesActive.isNotEmpty()
        ) {
            traceFramesActive[traceFramesActive.lastIndex] = true
        }
    }
    return status
}
