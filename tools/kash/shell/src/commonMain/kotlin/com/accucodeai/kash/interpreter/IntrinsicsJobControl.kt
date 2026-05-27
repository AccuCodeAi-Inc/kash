package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.api.signal.SigCont
import com.accucodeai.kash.api.signal.SigTerm
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.jobs.JobControl
import com.accucodeai.kash.jobs.JobState
import com.accucodeai.kash.jobs.KashJob
import com.accucodeai.kash.parser.isShellIdentifier

// Job-control intrinsics (kill/wait/jobs/fg/bg).

/**
 * Subshell-aware view onto [JobControl.list]. Bash semantics: `jobs`
 * inside `$(...)` or `(...)` lists the parent's table verbatim — POSIX
 * treats it as the same shell environment for visibility purposes. So
 * the listing helper is currently identical to [JobControl.list]; the
 * function exists as a stable surface for future refinement (e.g. once
 * we model `set -m`-only-in-parent visibility differences).
 */
internal fun Interpreter.visibleJobs(): List<KashJob> =
    jobControl.list().filter { job ->
        // Bash's foreground commands are never in `jobs` while running —
        // they ARE the prompt's current statement. Show them only when
        // stopped (Ctrl-Z parked them in the table for `fg`/`bg` to
        // resolve), so the user sees `[N]+ Stopped <cmd>` between prompts.
        if (!job.isForeground) {
            true
        } else {
            jobControl.stateOf(job) == JobState.STOPPED
        }
    }

/**
 * Subshell-aware [JobControl.resolve]. Bash semantics: the *current-job*
 * pointer (`%+` / `%-` / `%%`) is per-subshell — a `$(...)` or `(...)`
 * body has no current job until it backgrounds something itself, even
 * though it still sees its parent's table via explicit `%N` or bare-pid
 * lookups. The mask captured at fork time hides only the inherited ids;
 * jobs the subshell itself registers are not in the mask, so
 * `(sleep 1 & fg %+)` works inside a subshell once a `&` ran there.
 *
 * `%N` explicit jobspecs and bare pids deliberately bypass the mask:
 * `kill %1` and `kill 1234` from inside `(...) &` must still reach a
 * job the parent backgrounded. This matches bash's behavior where
 * coprocs are kill-targetable from any descendant.
 */
internal fun Interpreter.resolveJobspec(spec: String): KashJob? {
    val job = jobControl.resolve(spec) ?: return null
    // Only current-job markers (`%+`, `%-`, `%%`) honour the mask.
    val isCurrentMarker = spec == "%+" || spec == "%-" || spec == "%%"
    if (!isCurrentMarker) return job
    return if (job.id in subshellJobMask) null else job
}

/**
 * Current-job (`%+` / `%%`) accessor that respects the subshell mask.
 * Returns null inside a subshell whose parent's `%+` is the only
 * candidate, matching bash's "fg with no current job" path.
 */
internal fun Interpreter.currentJob(): KashJob? = resolveJobspec("%+")

/**
 * Subshell-aware `$!` accessor. POSIX: `$!` is the most-recently-
 * backgrounded asynchronous command of THIS shell environment; subshells
 * start fresh, so a `(echo $!)` before any `&` inside the subshell must
 * yield empty, not the parent's last bg pid.
 */
internal fun Interpreter.lastBgPidMasked(): Int? {
    val id = jobControl.lastBgId() ?: return null
    // Cheap O(1) mask check — no `list()` iteration, no Set walk over the
    // job table that would race against concurrent register/reap on a
    // shared [JobControl] (pipeline-stage subshells all share one).
    if (id in subshellJobMask) return null
    return jobControl.lastBgPid()
}

/**
 * POSIX/bash `kill`. Forms:
 *   - `kill -l`              — list all signal names.
 *   - `kill -l NAME_OR_NUM`  — translate name↔number.
 *   - `kill [-s SIG | -SIG] JOBSPEC…`  — send signal (default TERM)
 *     to each named job. Jobspec syntax mirrors [JobControl.resolve]:
 *     `%N`, `%+`, `%%`, or a bare integer.
 *
 * kash doesn't model host pids, so `kill 1234` always resolves against
 * the kash job table — never the OS.
 */
internal suspend fun Interpreter.runKillIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    if (args.isEmpty()) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: usage: kill [-s sigspec | -sigspec] pid | jobspec ...\n")
        return 2
    }
    // -l mode.
    if (args[0] == "-l") {
        if (args.size == 1) {
            // POSIX: list only deliverable signals. Pseudo-signals
            // (DEBUG/RETURN/ERR/EXIT) carry sentinel non-positive
            // numbers and are name-only entries in the trap table —
            // they must not appear in `kill -l` output.
            for (s in KashSignal.ALL) {
                if (s.number <= 0) continue
                stdio.stdout.writeUtf8("${s.number}) SIG${s.name}\n")
            }
            return 0
        }
        for (a in args.drop(1)) {
            val numeric = a.toIntOrNull()
            // POSIX: `kill -l <exit-status>` where exit-status > 128
            // resolves to the signal that produced it (`130` → `INT`).
            // Try the bare number first; if that misses and the number
            // is > 128, retry after stripping the 128 offset.
            val s =
                KashSignal.parse(a) ?: run {
                    if (numeric != null && numeric > 128) {
                        KashSignal.parse((numeric - 128).toString())
                    } else {
                        null
                    }
                }
            if (s == null) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: $a: invalid signal specification\n")
                return 1
            }
            // Numeric input → print name; named input → print number.
            stdio.stdout.writeUtf8(if (numeric != null) "${s.name}\n" else "${s.number}\n")
        }
        return 0
    }

    var sig: KashSignal = SigTerm
    var i = 0
    if (args[0] == "-s") {
        if (args.size < 2) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: -s: option requires an argument\n")
            return 2
        }
        val parsed = KashSignal.parse(args[1])
        if (parsed == null) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: ${args[1]}: invalid signal specification\n")
            return 1
        }
        sig = parsed
        i = 2
    } else if (args[0] == "-n") {
        // `kill -n SIGNUM` — numeric-only signal spec form. Bash documents
        // this as separate from `-s` because it forbids names ("-n KILL"
        // is rejected); we just accept what parse() returns either way.
        if (args.size < 2) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: -n: option requires an argument\n")
            return 2
        }
        val parsed = KashSignal.parse(args[1])
        if (parsed == null) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: ${args[1]}: invalid signal specification\n")
            return 1
        }
        sig = parsed
        i = 2
    } else if (args[0].startsWith("-n") && args[0].length > 2) {
        // `kill -n9 PID` — bash's combined-form `-n` + signum. Same
        // numeric-only signal-spec semantics as the split form above.
        val parsed = KashSignal.parse(args[0].drop(2))
        if (parsed == null) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: ${args[0]}: invalid signal specification\n")
            return 1
        }
        sig = parsed
        i = 1
    } else if (args[0].startsWith("-") && args[0].length > 1) {
        val parsed = KashSignal.parse(args[0].drop(1))
        if (parsed == null) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: ${args[0]}: invalid signal specification\n")
            return 1
        }
        sig = parsed
        i = 1
    }

    if (i >= args.size) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: usage: kill [-s sigspec | -sigspec] pid | jobspec ...\n")
        return 2
    }

    var status = 0
    for (j in i until args.size) {
        val target = args[j]
        // `kill <pid>` against a kash-shell pid (today: just `$$`,
        // i.e. the root) routes through the session's [SignalRouter]
        // so the trap dispatcher fires on the interpreter that owns
        // the matching `foregroundSignals` channel. jobs9.sub's
        // `( ... ; kill -USR1 $$ ) &` depends on this — the
        // backgrounded fork's own channel isn't being drained, so a
        // direct `deliverSignal` here would land in a black hole.
        // The router returns false for non-shell pids, falling through
        // to the jobspec/OS path below.
        val asPid = target.toIntOrNull()
        if (asPid != null && signalRouter.deliver(asPid, sig)) {
            continue
        }
        val job = resolveJobspec(target)
        if (job == null) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}kill: $target: no such job\n")
            status = 1
            continue
        }
        jobControl.signal(job, sig)
    }
    return status
}

/**
 * POSIX [`wait`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/wait.html).
 * No operands: reap every tracked job, return 0.
 * One+ operands: each is a jobspec (`%N`, `%%`, `%+`) or the integer
 * id returned by `$!`. Reaps the named job; exit code is that of the
 * *last* awaited job. Unknown jobspec → 127 per POSIX.
 */
internal suspend fun Interpreter.runWaitIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    // Parse flags: `-n` returns when ANY one job completes (vs default
    // "wait for all listed"). `-p VAR` stores the pid of the reaped job
    // into VAR (only meaningful with `-n`). `-f` is for foreground job
    // syncs in interactive job-control mode — accepted as a no-op here.
    var waitAny = false
    var pidVar: String? = null
    val specs = mutableListOf<String>()
    var i = 0
    var endOfOptions = false
    while (i < args.size) {
        val a = args[i]
        when {
            endOfOptions -> {
                specs += a
            }

            a == "--" -> {
                // POSIX `--` ends option processing; subsequent
                // hyphenated args are treated as operands. Bash
                // accepts `wait -- -4` then reports "-4" as
                // `not a pid or valid job spec` via the operand
                // shape-validation pass below.
                endOfOptions = true
            }

            a == "-n" -> {
                waitAny = true
            }

            a == "-f" -> {}

            a == "-p" -> {
                if (i + 1 >= args.size) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: -p: requires argument\n")
                    return 2
                }
                val pidVarArgIdx = i + 1
                pidVar = args[i + 1]
                i++
                val lb = pidVar.indexOf('[')
                val rb = pidVar.lastIndexOf(']')
                if (lb > 0 && rb == pidVar.length - 1) {
                    // `wait -p NAME[sub]` stores the reaped pid into an array
                    // element. Validate the base name; the subscript is a
                    // literal index/key (no arith here).
                    val base = pidVar.substring(0, lb)
                    val sub = pidVar.substring(lb + 1, rb)
                    // array_expand_once security baseline: a literal `$(...)`
                    // in the subscript must not be re-evaluated. On an INDEXED
                    // base the subscript is arithmetic, so a surviving `$(...)`
                    // is the injection vector → reject (matches read's indexed
                    // lane). On an ASSOCIATIVE base the subscript is a literal
                    // string key — bash never executes it — so store it
                    // verbatim like the assoc-write and `read` assoc paths.
                    if ("\$(" in sub && varTable.find(base)?.isAssoc != true) {
                        stdio.stderr.writeUtf8(
                            "${shellDiagPrefix()}$sub: arithmetic syntax error: operand expected (error token is \"$sub\")\n",
                        )
                        return 1
                    }
                    // Greedy single-key: an unquoted array-reference arg with
                    // an associative base takes the subscript to the closing
                    // `]` under assoc_expand_once, so `wait -p A[$rk]` (rk=']')
                    // stores key `]`. Otherwise the first `]` closes and
                    // `A[]]` is an invalid identifier.
                    val firstRb = pidVar.indexOf(']', lb + 1)
                    val greedyAssoc = isGreedyAssocRef(base, pidVarArgIdx)
                    if (!isShellIdentifier(base) || (firstRb != rb && !greedyAssoc)) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: `$pidVar': not a valid identifier\n")
                        return 1
                    }
                    if (varTable.find(base)?.isReadonly == true) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: $base: readonly variable\n")
                        return 1
                    }
                } else {
                    if (pidVar.isEmpty() || !isShellIdentifier(pidVar)) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: `$pidVar': not a valid identifier\n")
                        return 1
                    }
                    if (varTable.find(pidVar)?.isReadonly == true) {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: $pidVar: cannot unset: readonly variable\n")
                        return 1
                    }
                }
            }

            a.startsWith("-") && a.length > 1 -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: $a: invalid option\n")
                return 2
            }

            else -> {
                specs += a
            }
        }
        i++
    }

    // `wait -p NAME` / `wait -p NAME[sub]`: route a bracketed target to the
    // array-element setter (indexed vs assoc), else write the scalar.
    suspend fun storePidVar(pid: String) {
        val pv = pidVar ?: return
        val t = parseBracketTarget(pv)
        if (t != null) setBuiltinArrayElementTarget(t.base, t.sub, pid) else env[pv] = pid
    }
    if (specs.isEmpty()) {
        // POSIX: bare `wait` (no specs) always returns 0; drain each
        // job's buffered output in creation order. With `-n` and no
        // specs, wait for *any* job to complete and return its exit —
        // bash's "first-to-finish wins". Prefer an already-completed
        // job (cheap, deterministic) over `select`-ing on every job's
        // done channel — that path costs nothing if any job is ready,
        // and the suspending fallback handles the no-completion case.
        if (waitAny) {
            val candidates = visibleJobs()
            if (candidates.isEmpty()) return 127
            // Bash's `wait -n` returns the FIRST-to-finish job. Under
            // real time bash picks whichever process actually exited
            // first; the candidate list is iterated by job-id but only
            // one job is "newly dead" at a given moment in practice. In
            // kash under a virtual-time test scheduler, multiple jobs
            // may be already-completed by the time `wait -n` runs,
            // because virtual time can advance past several `delay()`s
            // between statements. The naive "first completed by id-
            // iteration" picks the lowest id, not the first-to-finish.
            //
            // Drain `completionStream` for any completed candidate that
            // arrived while we weren't watching: completion arrives in
            // FIFO order (the channel is UNLIMITED and the callback
            // fires synchronously from `done.complete()`), so the first
            // matching job in the stream is the first-to-finish among
            // the candidates. Skip past completions for jobs not in
            // candidates (e.g. earlier waits' targets) or already
            // reaped.
            val byPid = candidates.associateBy { it.id }
            var chosen: KashJob? = null
            while (true) {
                val pending = jobControl.completionStream.tryReceive().getOrNull() ?: break
                if (byPid[pending.id] === pending) {
                    chosen = pending
                    break
                }
            }
            if (chosen == null) {
                // No buffered completion; suspend on the stream until
                // a candidate completes. Filter out non-candidate
                // arrivals from concurrent `wait -n` callers / earlier
                // job lineages.
                while (chosen == null) {
                    val pending = jobControl.completionStream.receive()
                    if (byPid[pending.id] === pending) chosen = pending
                }
            }
            val exit = jobControl.reap(chosen, stdio.stdout, stdio.stderr)
            if (pidVar != null) storePidVar(chosen.leaderPid.toString())
            return exit
        }
        // POSIX §`wait` and bash man page §SIGNALS: a bare `wait`
        // must return `128 + signo` if a signal whose handler is
        // installed arrives while wait is suspended, and the handler
        // must run immediately after wait returns. Run each reap
        // inside a select that races the job's `done` against the
        // foregroundSignals channel — when a trapped signal wins,
        // requeue it for the next [drainForegroundSignals] poll and
        // bail out.
        for (j in visibleJobs()) {
            if (!j.done.isCompleted) {
                val sig: KashSignal? =
                    kotlinx.coroutines.selects.select {
                        j.done.onAwait { null }
                        foregroundSignals.onReceive { received ->
                            // SIGCHLD must NOT terminate `wait` — wait is
                            // by definition waiting for children. Re-queue
                            // for the next [drainForegroundSignals] poll
                            // so the trap still fires, and let the select
                            // keep waiting on `j.done`.
                            if (received === com.accucodeai.kash.api.signal.SigChld) {
                                foregroundSignals.trySend(received)
                                null
                            } else if (trapTable.get(received) is com.accucodeai.kash.traps.TrapAction.Handler) {
                                received
                            } else {
                                null
                            }
                        }
                    }
                if (sig != null) {
                    foregroundSignals.trySend(sig)
                    return com.accucodeai.kash.jobs
                        .fatalSignalExitCode(sig)
                }
            }
            jobControl.reap(j, stdio.stdout, stdio.stderr)
            // Bash fires SIGCHLD AFTER the child's output is drained to
            // the terminal — order matters for traps that `echo`
            // something visible. Queue the signal here so
            // drainForegroundSignals at the next safe point fires the
            // trap with the output already flushed.
            deliverSignal(com.accucodeai.kash.api.signal.SigChld)
        }
        return 0
    }
    var exit = 0
    if (waitAny) {
        // Wait for the first of the listed specs to complete. Pre-resolve
        // every spec so a single unresolved name fails fast with bash's
        // "no such job", then select on the survivor set's `done` channels
        // so the genuinely-first-to-complete job wins — `wait -p var -n
        // %2 %3` must record the pid of whichever job finished, not just
        // the one that happens to be earliest in the argument list.
        val resolved = mutableListOf<KashJob>()
        for (spec in specs) {
            val job =
                resolveJobspec(spec) ?: run {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: $spec: no such job\n")
                    return 127
                }
            resolved += job
        }
        if (resolved.isEmpty()) return 127
        val ready = resolved.firstOrNull { it.done.isCompleted }
        val chosen =
            ready ?: kotlinx.coroutines.selects.select {
                for (j in resolved) {
                    j.done.onAwait { j }
                }
            }
        exit = jobControl.reap(chosen, stdio.stdout, stdio.stderr)
        if (pidVar != null) storePidVar(chosen.leaderPid.toString())
        return exit
    }
    for (spec in specs) {
        // Distinguish malformed-spec cases that bash reports as
        // "not a pid or valid job spec" rather than "no such job":
        // anything not a positive integer or `%`-prefixed jobspec.
        val isValidShape =
            spec.startsWith("%") ||
                (spec.toLongOrNull()?.let { it >= 0 } == true)
        if (!isValidShape) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}wait: `$spec': not a pid or valid job spec\n",
            )
            exit = 2
            continue
        }
        val job = resolveJobspec(spec)
        if (job == null) {
            // Bare-integer wait against a pid we know about (anywhere
            // in processTable) but isn't a job member: bash reports
            // "pid N is not a child of this shell". Otherwise the
            // usual no-such-job applies.
            val barePidUnresolved = spec.toIntOrNull()
            if (barePidUnresolved != null && machine.processTable.containsKey(barePidUnresolved)) {
                stdio.stderr.writeUtf8(
                    "${shellDiagPrefix()}wait: pid $barePidUnresolved is not a child of this shell\n",
                )
            } else {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}wait: $spec: no such job\n")
            }
            exit = 127
            continue
        }
        // Bare-pid wait: if the spec is a non-leader member, await the
        // pipeline and return *that stage's* exit code (zsh-style
        // per-process wait). Leader and jobspec forms get the
        // pipeline's last-stage exit.
        val barePid = spec.toIntOrNull()
        val memberIndex =
            if (barePid != null) job.memberPids.indexOf(barePid).takeIf { it >= 0 } else null
        val stageWait = memberIndex != null && memberIndex > 0
        exit = jobControl.reap(job, stdio.stdout, stdio.stderr)
        // Stash the reaped pipeline's per-stage exits as the override
        // that will win against the wait command's own trivial publish
        // when runPipeline calls publishPipeStatus on the way out.
        pipeStatusOverride = job.stageCodes.copyOf()
        if (stageWait) exit = job.stageCodes[memberIndex]
        if (pidVar != null && barePid != null) storePidVar(barePid.toString())
    }
    return exit
}

/**
 * POSIX [`jobs`](https://pubs.opengroup.org/onlinepubs/9699919799/utilities/jobs.html).
 * Prints one line per tracked job. Bash format:
 *
 *   `[1]+  Running                    sleep 10 &`
 *
 * Three-space gap after `]`, 24-wide state column, trailing ` &` only
 * for backgrounded RUNNING jobs (omitted for Stopped/Done).
 *
 * Flags:
 *   -l   one line per pipeline member showing each stage's pid.
 *   -p   leader pid only, one per job.
 *   -r   filter to running jobs only.
 *   -s   filter to stopped jobs only.
 *   -n   only jobs whose status changed since the last query.
 */
internal suspend fun Interpreter.runJobsIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var pidOnly = false
    var longForm = false
    var runningOnly = false
    var stoppedOnly = false
    var changedOnly = false
    val specs = mutableListOf<String>()
    for (a in args) {
        when {
            a == "-l" -> {
                longForm = true
            }

            a == "-p" -> {
                pidOnly = true
            }

            a == "-r" -> {
                runningOnly = true
            }

            a == "-s" -> {
                stoppedOnly = true
            }

            a == "-n" -> {
                changedOnly = true
            }

            a == "--" -> {}

            a.startsWith("%") -> {
                specs += a
            }

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}jobs: $a: invalid option\n")
                stdio.stderr.writeUtf8("jobs: usage: jobs [-lnprs] [jobspec ...] or jobs -x command [args]\n")
                return 2
            }

            else -> {
                specs += a
            }
        }
    }
    val all =
        if (specs.isEmpty()) {
            visibleJobs()
        } else {
            specs.mapNotNull { spec ->
                resolveJobspec(spec) ?: run {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}jobs: $spec: no such job\n")
                    null
                }
            }
        }
    val targets =
        all.filter { job ->
            val st = jobControl.stateOf(job)
            when {
                runningOnly && st != JobState.RUNNING -> false
                stoppedOnly && st != JobState.STOPPED -> false
                changedOnly && job.id !in jobControl.changedJobs() -> false
                else -> true
            }
        }
    // For the `+`/`-` MARK column, bash's `jobs` listing reads the
    // global current-job / previous-job pointers verbatim — they're an
    // attribute of the JobControl entry, not the subshell's local state.
    // So a `$(jobs)` substitution prints the parent's `+`/`-` annotations
    // even though `fg %%` inside that same substitution fails. Use the
    // raw resolve, not the masked one.
    val currentId = jobControl.resolve("%+")?.id
    val previousId = jobControl.resolve("%-")?.id
    // Bash "report-and-remove": completed jobs (DONE / TERMINATED) are
    // listed once by `jobs` and then dropped from the table, so a later
    // `jobs` invocation won't show them again. Bash marks each job
    // "notified" after the print and the
    // reaper purges any NOTIFIED+DEAD entry on the next pass. Collected
    // here and dropped after the listing loop to avoid mutating the
    // iteration's snapshot.
    val toForget = mutableListOf<KashJob>()
    for (job in targets) {
        val mark =
            when (job.id) {
                currentId -> "+"
                previousId -> "-"
                else -> " "
            }
        val st = jobControl.stateOf(job)
        val state = stateLabel(st)
        // bash format: `[id]<mark><2 spaces><state-padded-to-width-27><command><&?>`.
        // Total prefix-to-command columns = 4(`[N]<mark>`) + 2 + 27 = 33.
        val statePad = state.padEnd(27)
        // Trailing `&` is bash's signal for "still in background" — only
        // attached when the job is logically running in the background.
        // Stopped/Done jobs omit it.
        val cmdSuffix = if (st == JobState.RUNNING) "${job.command} &" else job.command
        when {
            pidOnly -> {
                stdio.stdout.writeUtf8("${job.leaderPid}\n")
            }

            longForm -> {
                for ((idx, pid) in job.memberPids.withIndex()) {
                    val prefix = if (idx == 0) "[${job.id}]$mark " else "     "
                    val rowTail = if (idx == 0) "$statePad$cmdSuffix" else statePad.trimEnd()
                    stdio.stdout.writeUtf8("$prefix $pid $rowTail\n")
                }
            }

            else -> {
                stdio.stdout.writeUtf8("[${job.id}]$mark  $statePad$cmdSuffix\n")
            }
        }
        // Done/Terminated entries get the one-shot report-then-remove
        // treatment, BUT defer the reap for entries that completed
        // during the currently-executing statement. Bash's
        // `cleanup_dead_jobs` runs at synchronization points (between
        // statements), not at the listing site — so two `$(jobs)`
        // substitutions within one outer statement both see the dead
        // entry; only the *next* statement's listing reaps it. Skipping
        // this guard caused `echo $(jobs); echo $(fg %% ; jobs)` to
        // print empty for the second substitution under virtual time,
        // because the first one immediately reaped a virtual-time-DONE
        // job that bash would have still seen as Running.
        if (st == JobState.DONE || st == JobState.TERMINATED) {
            val completedHere = job.completedAtStatement == jobControl.topLevelStatementCounter
            if (!completedHere) toForget += job
        }
    }
    for (job in toForget) jobControl.tryReap(job)
    if (changedOnly) jobControl.clearChangedJobs()
    return 0
}

/**
 * `fg [%n]` — resume a stopped or background job in the foreground and
 * wait for it. Since kash is in-process and we have no controlling
 * terminal to transfer (real tty foreground-group plumbing would route
 * through `PosixTerminalControl` + `tcsetpgrp(2)`), "foreground"
 * here means: deliver SigCont (clears the logical-stop bit and unblocks
 * `awaitExitCode`) and synchronously wait for completion. Echoes the
 * pipeline label first, matching bash.
 *
 * No-arg form picks the most-recent job (`%+`).
 */
internal suspend fun Interpreter.runFgIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    // Bash: `fg` accepts a jobspec argument and nothing else; any flag
    // starting with `-` (other than the standalone `--`) is an error
    // reported as `fg: -X: invalid option` plus the usage line.
    val filtered = mutableListOf<String>()
    for (a in args) {
        when {
            a == "--" -> {}

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}fg: $a: invalid option\n")
                stdio.stderr.writeUtf8("fg: usage: fg [job_spec]\n")
                return 2
            }

            else -> {
                filtered += a
            }
        }
    }
    // POSIX §2.11/§job-control: fg requires job control to be active.
    // Outside of `set -m` (monitor mode), bash reports `fg: no job
    // control` with exit status 1.
    if (!monitor) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}fg: no job control\n")
        return 1
    }
    val spec = filtered.firstOrNull() ?: "%+"
    val job =
        resolveJobspec(spec) ?: run {
            // No-current-jobs distinction: when the caller used the
            // default `%+` and there's nothing to fg, bash reports it
            // as "no current jobs" instead of the generic no-such-job.
            if (filtered.isEmpty()) {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}fg: no current jobs\n")
            } else {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}fg: $spec: no such job\n")
            }
            return 1
        }
    // Bash: `fg %N` against a job that was backgrounded BEFORE `set -m`
    // was enabled refuses with "job N started without job control". The
    // monitor mode gate above only covers "monitor never enabled"; this
    // is the "monitor enabled mid-script after the job was forked" case.
    if (!job.startedUnderMonitor) {
        stdio.stderr.writeUtf8(
            "${shellDiagPrefix()}fg: job ${job.id} started without job control\n",
        )
        return 1
    }
    stdio.stdout.writeUtf8("${job.command}\n")
    jobControl.signal(job, SigCont)
    // Resume the stop-gate too: a Ctrl-Z'd foreground KashJob has its
    // deferred parked on the gate; SigCont clears the stopped bit but
    // doesn't itself wake the gate, so we open it explicitly here.
    // Idempotent on backgrounded jobs (their deferred isn't on the
    // gate, so resume is a no-op for them).
    stopGate.resume()
    return jobControl.reap(job, stdio.stdout, stdio.stderr)
}

/**
 * `bg [%n]` — resume a stopped job in the background. Just delivers
 * SigCont; control returns immediately to the caller. No-op-with-error
 * on a running or already-done job is what bash does, but we accept
 * any non-terminated job for forgiveness.
 */
internal suspend fun Interpreter.runBgIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val filtered = mutableListOf<String>()
    for (a in args) {
        when {
            a == "--" -> {}

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}bg: $a: invalid option\n")
                stdio.stderr.writeUtf8("bg: usage: bg [job_spec ...]\n")
                return 2
            }

            else -> {
                filtered += a
            }
        }
    }
    if (!monitor) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}bg: no job control\n")
        return 1
    }
    val specs = if (filtered.isEmpty()) listOf("%+") else filtered
    var status = 0
    for (spec in specs) {
        val job =
            resolveJobspec(spec) ?: run {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}bg: $spec: no such job\n")
                status = 1
                null
            } ?: continue
        if (jobControl.stateOf(job) == JobState.RUNNING) {
            // Bash: `bg %1` against a job that's already running in the
            // background returns 1 with this exact diagnostic.
            stdio.stderr.writeUtf8("${shellDiagPrefix()}bg: job ${job.id} already in background\n")
            status = 1
            continue
        }
        jobControl.signal(job, SigCont)
        // Resume the stop-gate (same reasoning as runFgIntrinsic).
        // A stopped foreground KashJob resumed via `bg` runs to
        // completion in the background, output buffered into job.stdoutBuf
        // for the next sync-point notification.
        stopGate.resume()
        stdio.stdout.writeUtf8("[${job.id}]${markFor(job)} ${job.command} &\n")
    }
    return status
}

private fun Interpreter.markFor(job: com.accucodeai.kash.jobs.KashJob): String =
    when (job.id) {
        resolveJobspec("%+")?.id -> "+"
        resolveJobspec("%-")?.id -> "-"
        else -> " "
    }

/**
 * Bash `suspend [-f]` — suspends the current shell as if SIGSTOP had
 * been delivered to it. In an in-process model with no controlling
 * tty this can never actually park the shell, so we report bash's
 * "no job control" diagnostic whenever monitor mode is off. With
 * `-f` (force) bash skips the login-shell guard; we accept it for
 * compatibility but still gate on monitor mode since the kash shell
 * has no real way to honor the request.
 */
internal suspend fun Interpreter.runSuspendIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var force = false
    for (a in args) {
        when {
            a == "--" -> {}

            a == "-f" -> {
                force = true
            }

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}suspend: $a: invalid option\n")
                stdio.stderr.writeUtf8("suspend: usage: suspend [-f]\n")
                return 2
            }

            else -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}suspend: too many arguments\n")
                return 2
            }
        }
    }
    @Suppress("UNUSED_VARIABLE")
    val unused = force
    if (!monitor) {
        stdio.stderr.writeUtf8("${shellDiagPrefix()}suspend: cannot suspend: no job control\n")
        return 1
    }
    // Monitor mode is on but we still can't actually SIGSTOP an
    // in-process shell — emit the same diagnostic for graceful
    // refusal. Real-tty hosts can override this intrinsic.
    stdio.stderr.writeUtf8("${shellDiagPrefix()}suspend: cannot suspend: no job control\n")
    return 1
}

// `disown [-h] [-a|-r] [jobspec...]`. Removes jobs from the table so
// they aren't reported by `jobs` and aren't auto-waited on shell exit.
//
//   -a   operate on all jobs
//   -r   operate on running jobs only
//   -h   keep the job tracked but mark "no HUP on shell exit" — in our
//        in-process model we accept the flag and treat it as a no-op
//        (we don't deliver SIGHUP on exit at all yet).
//
// Without jobspecs and without `-a`/`-r`, disown defaults to the
// current job (`%+`).
internal suspend fun Interpreter.runDisownIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var allJobs = false
    var runningOnly = false
    var keepHup = false
    val specs = mutableListOf<String>()
    for (a in args) {
        when {
            a == "--" -> {}

            a == "-a" -> {
                allJobs = true
            }

            a == "-r" -> {
                runningOnly = true
            }

            a == "-h" -> {
                keepHup = true
            }

            // Bash accepts combined short flags: `-ah`, `-rh`, `-ar`.
            a.length > 1 && a.startsWith("-") && a.drop(1).all { it in "arh" } -> {
                for (c in a.drop(1)) {
                    when (c) {
                        'a' -> allJobs = true
                        'r' -> runningOnly = true
                        'h' -> keepHup = true
                    }
                }
            }

            a.startsWith("-") -> {
                stdio.stderr.writeUtf8("${shellDiagPrefix()}disown: $a: invalid option\n")
                stdio.stderr.writeUtf8("disown: usage: disown [-h] [-ar] [jobspec ... | pid ...]\n")
                return 2
            }

            else -> {
                specs += a
            }
        }
    }
    // Bash diagnostic: `disown -h @12` warns about a jobspec missing
    // the leading `%`, then reports it as no-such-job. Detect the
    // `@N` shape and emit the warning before the regular resolution
    // failure.
    val warned = mutableSetOf<String>()
    for (spec in specs) {
        if (spec.startsWith("@") && spec.length > 1 && spec.drop(1).all { it.isDigit() }) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}disown: warning: $spec: job specification requires leading `%'\n",
            )
            warned += spec
        }
    }
    val targets: List<com.accucodeai.kash.jobs.KashJob> =
        when {
            allJobs -> {
                visibleJobs()
            }

            runningOnly -> {
                visibleJobs().filter { jobControl.stateOf(it) == JobState.RUNNING }
            }

            specs.isEmpty() -> {
                val cur = resolveJobspec("%+")
                if (cur == null) {
                    stdio.stderr.writeUtf8("${shellDiagPrefix()}disown: current: no such job\n")
                    return 1
                }
                listOf(cur)
            }

            else -> {
                specs.mapNotNull { spec ->
                    resolveJobspec(spec) ?: run {
                        stdio.stderr.writeUtf8("${shellDiagPrefix()}disown: $spec: no such job\n")
                        null
                    }
                }
            }
        }
    if (keepHup) {
        // No-op in our model: we don't propagate SIGHUP on shell exit.
        // Flag accepted for compatibility so scripts that use it parse.
        return 0
    }
    for (job in targets) jobControl.forget(job)
    return 0
}
