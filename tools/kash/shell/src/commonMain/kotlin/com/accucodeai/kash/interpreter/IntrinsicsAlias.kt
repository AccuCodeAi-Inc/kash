package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.jobs.JobState
import com.accucodeai.kash.parser.isValidAliasName
import kotlinx.coroutines.ExperimentalCoroutinesApi

// alias/unalias + shopt + helpers (stateLabel, escapeSingleQuoted).

// ---------------- alias / unalias ----------------

/**
 * POSIX [`alias`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/alias.html).
 * `alias` lists; `alias name` prints one entry; `alias name=value` defines.
 * `-p` is accepted as a bash-compat synonym for the bare listing form.
 *
 * Output format: `alias name='value'` with embedded `'` escaped as `'\''`
 * so the listing round-trips through `eval`.
 */
internal suspend fun Interpreter.runAliasIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty() || (args.size == 1 && args[0] == "-p")) {
        // Pull in `BASH_ALIASES[k]=v` direct writes so they appear in
        // `alias -p` output without needing a separate alias call.
        absorbBashAliases()
        // Bash sorts the `alias -p` (and bare `alias`) listing
        // alphabetically by name. Verified bash 5.3 directly.
        for ((name, value) in aliases.entries.sortedBy { it.key }) {
            stdio.stdout.writeUtf8("alias $name='${escapeSingleQuoted(value)}'\n")
        }
        return 0
    }
    var status = 0
    for (a in args) {
        val eq = a.indexOf('=')
        if (eq < 0) {
            // Bash treats unknown flags as `alias: -X: invalid option`
            // + usage, return 2. Without this gate `alias -x foo=bar`
            // fell into the "not found" message for the flag.
            if (a.startsWith("-") && a.length > 1) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}alias: $a: invalid option\n" +
                        "alias: usage: alias [-p] [name[=value] ... ]\n",
                )
                return 2
            }
            val v = aliases[a]
            if (v == null) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}alias: $a: not found\n")
                status = 1
            } else {
                stdio.stdout.writeUtf8("alias $a='${escapeSingleQuoted(v)}'\n")
            }
        } else {
            val name = a.substring(0, eq)
            val value = a.substring(eq + 1)
            if (!isValidAliasName(name)) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}alias: `$name': invalid alias name\n")
                status = 1
            } else {
                aliases[name] = value
                aliasVersion++
                syncBashAliases()
            }
        }
    }
    return status
}

/**
 * POSIX [`unalias`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/unalias.html).
 * `unalias -a` clears the table; `unalias name…` removes by name. Each
 * unknown name returns a diagnostic and bumps exit status, per spec.
 */
internal suspend fun Interpreter.runUnaliasIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty()) {
        // Bash: `unalias` with no args prints usage to stderr and returns 2.
        stdio.stderr.writeUtf8("unalias: usage: unalias [-a] name [name ...]\n")
        return 2
    }
    var status = 0
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-a" -> {
                aliases.clear()
                aliasVersion++
                syncBashAliases()
            }

            "--" -> {
                i++
                while (i < args.size) {
                    if (aliases.remove(args[i]) == null) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}unalias: ${args[i]}: not found\n")
                        status = 1
                    } else {
                        aliasVersion++
                    }
                    i++
                }
                syncBashAliases()
                return status
            }

            else -> {
                if (a.startsWith("-") && a.length > 1) {
                    // Match bash's format exactly — `<file>: line N: unalias:
                    // -X: invalid option` + usage, return 2 — so errors.tests
                    // line 32 can validate the diagnostic.
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}unalias: $a: invalid option\n" +
                            "unalias: usage: unalias [-a] name [name ...]\n",
                    )
                    return 2
                }
                if (aliases.remove(a) == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}unalias: $a: not found\n")
                    status = 1
                } else {
                    aliasVersion++
                    syncBashAliases()
                }
            }
        }
        i++
    }
    return status
}

internal suspend fun Interpreter.escapeSingleQuoted(s: String): String = s.replace("'", "'\\''")

/**
 * bash `shopt` — most options are tolerated silently to keep portability
 * shims like `shopt -s extglob` from derailing conformance scripts. The
 * observable ones we track:
 *
 *   - `extdebug` — enables `return 1`/`return 2` from a DEBUG trap to
 *     short-circuit the upcoming command (used by bash's dbg-support2 test).
 *
 * Returns 0 unconditionally; unknown options are accepted.
 */
internal suspend fun Interpreter.runShoptIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var setMode: Boolean? = null
    var printable = false
    var query = false
    var optionsMode = false // -o: act on `set -o` flags rather than shopts
    val names = mutableListOf<String>()
    for (a in args) {
        when (a) {
            "-s" -> {
                setMode = true
            }

            "-u" -> {
                setMode = false
            }

            "-p" -> {
                printable = true
            }

            "-q" -> {
                query = true
            }

            "-o" -> {
                optionsMode = true
            }

            else -> {
                if (a.startsWith("-") && a.length > 1) {
                    // Unknown option: bash prints `shopt: -X: invalid option`
                    // and a usage line, then returns 2. The full table is NOT
                    // dumped — without this guard a `shopt -z` silently fell
                    // into the no-names branch and printed every option.
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}shopt: $a: invalid option\n" +
                            "shopt: usage: shopt [-pqsu] [-o] [optname ...]\n",
                    )
                    return 2
                }
                names.add(a)
            }
        }
    }
    if (optionsMode) return 0 // -o set-option pass-through; not modeled here.

    fun get(n: String): Boolean =
        shoptOptions[n] ?: when (n) {
            "extdebug" -> extdebugEnabled
            else -> false
        }

    fun set(
        n: String,
        v: Boolean,
    ) {
        when (n) {
            "extdebug" -> extdebugEnabled = v
        }
        shoptOptions[n] = v
        // Track explicit user toggles separately. Some shopts (e.g.
        // expand_aliases) have a bash-mode-dependent default that we
        // pre-seed to ON; classify() consults the user-set flag to
        // decide whether the alias-visibility behavior was intended.
        shoptOptions["${n}_user_set"] = true
    }

    if (names.isEmpty()) {
        // List every tracked shopt (matches bash's -p output format
        // closely: `shopt -s NAME` / `shopt -u NAME`). `toSortedSet` is
        // JVM-only — sort via [sorted] for wasmJs compatibility.
        // Hide internal `_user_set` companion keys (used by classify()
        // to distinguish bash's interactive-default shopts from those
        // a script explicitly enabled). Users never see these.
        val allNames =
            (shoptOptions.keys + "extdebug")
                .filterNot { it.endsWith("_user_set") }
                .toSet()
                .sorted()
        for (n in allNames) {
            val on = get(n)
            // `shopt -s [-p]` / `shopt -u [-p]` with no names filters the
            // listing to only the matching state — bash dumps only the
            // currently-set shopts with `-s`, only currently-unset with
            // `-u`. Without this filter, both flags printed the whole
            // table identically.
            if (setMode != null && setMode != on) continue
            if (printable || setMode != null) {
                stdio.stdout.writeUtf8("shopt ${if (on) "-s" else "-u"} $n\n")
            } else {
                val state = if (on) "on" else "off"
                stdio.stdout.writeUtf8("${n.padEnd(20)}\t$state\n")
            }
        }
        return 0
    }

    var exit = 0
    for (n in names) {
        when {
            query -> {
                if (!get(n)) exit = 1
            }

            setMode != null -> {
                set(n, setMode)
            }

            else -> {
                val on = get(n)
                if (printable) {
                    stdio.stdout.writeUtf8("shopt ${if (on) "-s" else "-u"} $n\n")
                } else {
                    val state = if (on) "on" else "off"
                    stdio.stdout.writeUtf8("${n.padEnd(20)}\t$state\n")
                }
            }
        }
    }
    return exit
}

/**
 * Bash strsignal-style human string for [signum] — matches the
 * "Done"-column text bash prints for signal-killed jobs (`Killed`,
 * `Hangup`, `Segmentation fault`, …) rather than the raw `SIG*`
 * name. Sourced from glibc's `sys_siglist` / `strsignal(3)`, which
 * bash 5.x delegates to. Unknown signums fall back to `Terminated`
 * so a sane label always appears.
 */
internal fun bashStrSignal(signum: Int): String =
    when (signum) {
        1 -> "Hangup"
        2 -> "Interrupt"
        3 -> "Quit"
        4 -> "Illegal instruction"
        5 -> "Trace/breakpoint trap"
        6 -> "Aborted"
        7 -> "Bus error"
        8 -> "Floating point exception"
        9 -> "Killed"
        10 -> "User defined signal 1"
        11 -> "Segmentation fault"
        12 -> "User defined signal 2"
        13 -> "Broken pipe"
        14 -> "Alarm clock"
        15 -> "Terminated"
        16 -> "Stack fault"
        17 -> "Child exited"
        18 -> "Continued"
        19 -> "Stopped (signal)"
        20 -> "Stopped"
        21 -> "Stopped (tty input)"
        22 -> "Stopped (tty output)"
        23 -> "Urgent I/O condition"
        24 -> "CPU time limit exceeded"
        25 -> "File size limit exceeded"
        26 -> "Virtual timer expired"
        27 -> "Profiling timer expired"
        28 -> "Window changed"
        29 -> "I/O possible"
        30 -> "Power failure"
        31 -> "Bad system call"
        else -> "Terminated"
    }

internal suspend fun Interpreter.stateLabel(s: JobState): String =
    when (s) {
        JobState.RUNNING -> "Running"
        JobState.STOPPED -> "Stopped"
        JobState.DONE -> "Done"
        JobState.TERMINATED -> "Terminated"
    }

/**
 * Bash interactive "synchronization-point" job-status report — the
 * `[1]+  Done                    sleep 10` lines that appear before
 * each prompt when a backgrounded job has completed. Drains every
 * job whose `done` is satisfied: emits one bash-formatted line per
 * job to [stderr] and removes the entry from the table (the same
 * report-and-remove rule `jobs` itself uses).
 *
 * Output shape mirrors [runJobsIntrinsic] verbatim: `[ID]<+/-/ >`,
 * two spaces, state-padded-to-27, the pipeline label. For a non-
 * zero clean exit bash prints `Exit N` instead of `Done`. Signal
 * kills decode signum = exitCode - 128 and report the bash
 * strsignal-style human string (`Killed`, `Segmentation fault`,
 * `Hangup`, …) via [bashStrSignal].
 *
 * Called from the REPL loop just before reading the next line, so
 * the notification lands between commands and never interleaves
 * with a running tool's output. Non-interactive shells skip this
 * entirely — bash 5.x also gates the notification on interactive
 * mode + monitor mode, and we match.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Interpreter.notifyCompletedBackgroundJobs(stderr: com.accucodeai.kash.api.io.SuspendSink) {
    val list = jobControl.list()
    if (list.isEmpty()) return
    val currentId = jobControl.resolve("%+")?.id
    val previousId = jobControl.resolve("%-")?.id
    for (job in list) {
        if (!job.done.isCompleted) continue
        val st = jobControl.stateOf(job)
        if (st != JobState.DONE && st != JobState.TERMINATED) continue
        val code =
            try {
                job.done.getCompleted()
            } catch (_: Throwable) {
                0
            }
        val mark =
            when (job.id) {
                currentId -> "+"
                previousId -> "-"
                else -> " "
            }
        // Bash: non-zero clean exit reports `Exit N`. Signal kills
        // (code in 129..255) decode signum = code - 128 and report
        // the bash strsignal-style human string (`Killed`, `Hangup`,
        // `Segmentation fault`, …). Falls through to `Terminated`
        // when the signum isn't in our table.
        val state =
            when {
                st == JobState.DONE && code != 0 -> "Exit $code"
                st == JobState.TERMINATED -> bashStrSignal(code - 128)
                else -> stateLabel(st)
            }
        val statePad = state.padEnd(27)
        stderr.writeUtf8("[${job.id}]$mark  $statePad${job.command}\n")
        // Report-and-remove: drop the entry now so it doesn't re-fire
        // at the next sync point and `jobs` doesn't list it.
        jobControl.tryReap(job)
    }
}
