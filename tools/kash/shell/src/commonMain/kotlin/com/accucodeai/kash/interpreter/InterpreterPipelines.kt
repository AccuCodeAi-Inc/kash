package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.ArithCommand
import com.accucodeai.kash.ast.ArithForCommand
import com.accucodeai.kash.ast.CaseCommand
import com.accucodeai.kash.ast.CondCommand
import com.accucodeai.kash.ast.CoprocCommand
import com.accucodeai.kash.ast.ForCommand
import com.accucodeai.kash.ast.FunctionDef
import com.accucodeai.kash.ast.Group
import com.accucodeai.kash.ast.IfCommand
import com.accucodeai.kash.ast.Pipeline
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.Subshell
import com.accucodeai.kash.ast.WhileCommand
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.interpreter.Interpreter.ScriptAbortException
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.jobs.fatalSignalExitCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.accucodeai.kash.ast.Command as CommandNode

// Pipeline / redirection / executeWithStdio extracted from Interpreter.

internal suspend fun Interpreter.runPipeline(
    pipeline: Pipeline,
    initialStdin: SuspendSource,
): Int {
    // Update the parent's currentLine to the pipeline's source line so
    // ERR / DEBUG traps fired after the pipeline observe `$LINENO`
    // pointing at the pipeline that triggered them. A multi-stage
    // pipeline runs each stage in a forked interpreter — those forks
    // update their own currentLine but the parent's stays at the
    // previous statement's line without this nudge.
    if (!lineFrozenForTrap && pipeline.commands.isNotEmpty()) {
        currentLine = pipeline.commands.first().line
    }
    // Bare `time` form: no commands, just emit a zero-timing report.
    if (pipeline.commands.isEmpty() && pipeline.timed != null) {
        emitTimeReport(pipeline.timed)
        return finalize(pipeline, 0)
    }
    // Empty pipeline (bare `!`, or any even-parity `! ! …` run that
    // collapsed to identity): the implicit-true value (exit 0) flows
    // through; `finalize` applies any residual negation.
    if (pipeline.commands.isEmpty()) {
        return finalize(pipeline, 0)
    }
    val codes = IntArray(pipeline.commands.size)
    // POSIX §sh: "a pipeline beginning with the ! reserved word" runs
    // with errexit ignored. Bump the suppression counter so all stages
    // (which inherit it via forkSubshell) see the suppression too.
    if (pipeline.negated) errexitSuppressed++
    val lastCode =
        try {
            runPipelineCore(pipeline, initialStdin, outSink, errSink, codes)
        } finally {
            if (pipeline.negated) errexitSuppressed--
        }
    if (pipeline.timed != null) {
        emitTimeReport(pipeline.timed)
    }
    // POSIX/bash: `PIPESTATUS` is the per-stage exit code array of the
    // most recently executed foreground pipeline. With `set -o pipefail`,
    // the pipeline's overall exit is the rightmost-nonzero stage's exit
    // (or 0 if all succeeded), not just the last stage's. Population
    // happens BEFORE [finalize] so a `pipefail`-aware `set -e` sees the
    // right value.
    publishPipeStatus(codes)
    val effective =
        if (pipefail) {
            codes.lastOrNull { it != 0 } ?: lastCode
        } else {
            lastCode
        }
    val exit = finalize(pipeline, effective)
    checkPendingAbort()
    return exit
}

/**
 * Replace `PIPESTATUS` with the per-stage exit codes of the last
 * pipeline. If [pipeStatusOverride] is set (typically by `wait` after
 * reaping a background pipeline), that takes precedence — matches
 * bash, where `wait $!` followed by `echo "${PIPESTATUS[@]}"` reports
 * the reaped pipeline's per-stage exits, not the trivial single-stage
 * `wait` pipeline's [0].
 */
internal fun Interpreter.publishPipeStatus(codes: IntArray) {
    val effective = pipeStatusOverride ?: codes
    pipeStatusOverride = null
    val arr = mutableMapOf<Int, String>()
    effective.forEachIndexed { i, c -> arr[i] = c.toString() }
    indexedArrays["PIPESTATUS"] = arr
}

/**
 * Pure pipeline mechanics. No interpreter-state side effects (negation /
 * `lastExit` / `pendingAbort`) — those are caller territory. Parameterized
 * on sinks so a background pipeline (running concurrently with the
 * foreground) can use snapshot sinks captured at launch.
 *
 * Foreground only. Single-command "pipelines" run on the caller's
 * interpreter (mutations stick — that's what makes `cd /tmp` work);
 * multi-command pipelines pre-fork one subshell per stage at this entry
 * point and dispatch through [runForkedStages]. Background pipelines
 * skip this entry point entirely: [dispatchBackground] does its own
 * synchronous pre-fork at `&` time before launching the coroutine.
 */
internal suspend fun Interpreter.runPipelineCore(
    pipeline: Pipeline,
    initialStdin: SuspendSource,
    out: SuspendSink,
    err: SuspendSink,
    /** If non-null, filled with each stage's exit code by index for
     *  `PIPESTATUS` reporting. Must be sized to `pipeline.commands.size`. */
    stageCodes: IntArray? = null,
): Int {
    val n = pipeline.commands.size
    // Tty bits at this pipeline's boundary. Pipeline-internal pipes are
    // never tty (they're in-memory channels), but the first stage's
    // stdin and the last stage's stdout may still be — depending on
    // whether `out`/`err` are the script-level sinks or capture
    // buffers from a parent context.
    val parentStdoutIsTty = (out === outSink) && outSinkIsTty
    val parentStderrIsTty = (err === errSink) && errSinkIsTty
    val parentStdinIsTty = currentStdinIsTty
    val parentTerminal = hostTerminal
    if (n == 1) {
        // No fork: foreground single-command needs mutations (cd, export,
        // function defs) to be visible to the caller.
        val code =
            executeWithStdio(
                pipeline.commands.first(),
                Stdio(
                    stdin = initialStdin,
                    stdout = out,
                    stderr = err,
                    stdinIsTty = parentStdinIsTty,
                    stdoutIsTty = parentStdoutIsTty,
                    stderrIsTty = parentStderrIsTty,
                    terminalControl = parentTerminal,
                ),
            )
        stageCodes?.set(0, code)
        return code
    }
    // POSIX §2.12: each command of a multi-command pipeline runs in a
    // subshell environment. Pre-fork one [Interpreter] per stage.
    //
    // Exception: `shopt -s lastpipe` runs the LAST stage in the calling
    // shell so its variable/function/cwd mutations persist — `printf foo
    // | read x` would then leave `$x=foo` instead of dropping it on
    // subshell exit. We fork only the first N-1 stages and reuse `this`
    // for the final stage.
    //
    // If any stage fork hits RLIMIT_NPROC we unregister whatever forks
    // we already got, emit the bash-style fork error, and surface exit
    // 1 — matches bash's `bash: fork: retry: Resource temporarily
    // unavailable` and the failed-pipeline-status convention.
    val lastInParent = shoptOptions["lastpipe"] == true
    val forkCount = if (lastInParent) n - 1 else n
    val stageForks = ArrayList<Interpreter>(n)
    try {
        for (idx in 0 until forkCount) {
            val s = forkSubshell()
            // Pipeline stages semantically fork+exec: the stage's command
            // is about to run as if execve'd, so FD_CLOEXEC entries
            // inherited from the parent must drop here. Without this,
            // `exec 5>file; cmd | grep` would leave fd 5 visible inside
            // the grep stage.
            // Drop FD_CLOEXEC entries — semantically a pipeline stage
            // is fork+exec; without this, `exec 5>file; cmd | grep`
            // would leave fd 5 visible inside the stage. We avoid the
            // full [KashProcess.execReset] because that also flips
            // signal Handlers to Default, and pipeline stages
            // (subshells) inherit traps in bash, not exec'd commands.
            run {
                val toDrop =
                    s.process.fdTable.entries
                        .filter { it.value.closeOnExec }
                        .map { it.key }
                for (fd in toDrop) {
                    s.process.fdTable
                        .remove(fd)
                        ?.ofd
                        ?.release()
                }
            }
            stageForks += s
        }
    } catch (e: com.accucodeai.kash.api.ForkException) {
        for (stage in stageForks) {
            signalRouter.unregister(stage.process.pid)
            machine.unregisterProcess(stage.process.pid)
        }
        err.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
        return 1
    }
    if (lastInParent) stageForks += this
    return try {
        runForkedStages(
            pipeline = pipeline,
            initialStdin = initialStdin,
            out = out,
            err = err,
            stageForks = stageForks,
            stdinIsTty = parentStdinIsTty,
            stdoutIsTty = parentStdoutIsTty,
            stderrIsTty = parentStderrIsTty,
            terminalControl = parentTerminal,
            stageCodes = stageCodes,
            parentInterp = if (lastInParent) this else null,
        )
    } finally {
        // Each stage's forked process was registered in machine.processTable
        // by fork(); unregister now that the stage is done so transient
        // pipeline pids don't accumulate in /proc. With lastpipe the
        // final entry is `this` (never registered as a separate process
        // by fork), so we skip it.
        val toRelease = if (lastInParent) stageForks.dropLast(1) else stageForks
        for (stage in toRelease) {
            signalRouter.unregister(stage.process.pid)
            machine.unregisterProcess(stage.process.pid)
        }
    }
}

/**
 * Run pipeline stages on already-forked interpreters. Receiverless —
 * deliberately doesn't touch any "parent" interpreter state, because the
 * call sites that use this (foreground multi-stage pipelines and
 * background pipelines of any size) MUST NOT read state that the
 * concurrent foreground might mutate (outSink, currentStdinIsTty, etc.).
 * All ambient state is passed in explicitly.
 */
private suspend fun runForkedStages(
    pipeline: Pipeline,
    initialStdin: SuspendSource,
    out: SuspendSink,
    err: SuspendSink,
    stageForks: List<Interpreter>,
    stdinIsTty: Boolean,
    stdoutIsTty: Boolean,
    stderrIsTty: Boolean,
    terminalControl: com.accucodeai.kash.api.terminal.TerminalControl?,
    stageCodes: IntArray? = null,
    /** Interpreter that should NOT swallow `exit`: when a stage's
     *  interpreter is `===` this one, an `exit` propagates (used by
     *  `shopt -s lastpipe` so the in-parent final stage's `exit`
     *  terminates the caller as bash does). */
    parentInterp: Interpreter? = null,
): Int {
    val n = pipeline.commands.size
    if (n == 1) {
        val code =
            stageForks[0].executeWithStdio(
                pipeline.commands.first(),
                Stdio(
                    stdin = initialStdin,
                    stdout = out,
                    stderr = err,
                    stdinIsTty = stdinIsTty,
                    stdoutIsTty = stdoutIsTty,
                    stderrIsTty = stderrIsTty,
                    terminalControl = terminalControl,
                ),
            )
        stageCodes?.set(0, code)
        return code
    }
    val pipes = List(n - 1) { AsyncPipe() }
    var lc = 0
    coroutineScope {
        val jobs =
            pipeline.commands.mapIndexed { idx, cmd ->
                val stdin: SuspendSource = if (idx == 0) initialStdin else pipes[idx - 1].source
                val stdout: SuspendSink = if (idx == n - 1) out else pipes[idx].sink
                val stageStdinIsTty = idx == 0 && stdinIsTty
                val stageStdoutIsTty = idx == n - 1 && stdoutIsTty
                val stageInterp = stageForks[idx]
                // Pipe ends in the middle of a pipeline are never the tty —
                // only the first stage's stdin and the last stage's stdout
                // can carry the terminal handle. installStdio's per-fd
                // isTty gate enforces this even if we pass the control.
                val stageTerminal = if (stageStdinIsTty || stageStdoutIsTty) terminalControl else null
                // Inherit the caller's dispatcher (no hardcoded
                // Dispatchers.Default). Pre-AsyncPipe-rewrite this was
                // forced to Default to dodge runBlocking-on-the-test-
                // scheduler deadlocks; AsyncPipe is suspend-native now,
                // so stages back-pressure as coroutine suspensions on
                // whatever dispatcher the user runs on.
                // `|&` separator following stage `idx` routes its stderr
                // through the same pipe as its stdout. Last stage never
                // has a following separator, so its stderr stays parental.
                val stageStderrToPipe =
                    idx < n - 1 && pipeline.pipeStderr.getOrElse(idx) { false }
                val stageStderr: SuspendSink = if (stageStderrToPipe) stdout else err
                val stageStderrIsTtyEff = if (stageStderrToPipe) false else stderrIsTty
                async {
                    var stageExit = 0
                    try {
                        try {
                            stageExit =
                                stageInterp.executeWithStdio(
                                    cmd,
                                    Stdio(
                                        stdin = stdin,
                                        stdout = stdout,
                                        stderr = stageStderr,
                                        stdinIsTty = stageStdinIsTty,
                                        stdoutIsTty = stageStdoutIsTty,
                                        stderrIsTty = stageStderrIsTtyEff,
                                        terminalControl = stageTerminal,
                                    ),
                                )
                        } catch (e: Interpreter.ScriptAbortException) {
                            // POSIX §2.12: `exit N` inside a forked
                            // pipeline stage terminates only that stage —
                            // its code surfaces as the stage's exit code
                            // and the rest of the pipeline keeps running.
                            // Swallow for forked stages only; the lastpipe
                            // in-parent stage is `parentInterp` and lets
                            // exit propagate so the caller terminates.
                            if (stageInterp === parentInterp) {
                                throw e
                            } else {
                                stageExit = e.code
                            }
                        }
                        // POSIX §2.12 / bash: a pipeline stage is a
                        // subshell; its EXIT trap fires when the stage
                        // exits, whether normally or via `exit N`. Point
                        // the stage's out/errSink at the pipe ends so the
                        // handler's writes flow into the pipeline.
                        if (stageInterp !== parentInterp) {
                            val savedOut = stageInterp.outSink
                            val savedErr = stageInterp.errSink
                            stageInterp.outSink = stdout
                            stageInterp.errSink = stageStderr
                            stageInterp.lastExit = stageExit
                            try {
                                stageInterp.runExitTrap()
                            } finally {
                                stageInterp.outSink = savedOut
                                stageInterp.errSink = savedErr
                            }
                            stageExit = stageInterp.lastExit
                        }
                        stageExit
                    } finally {
                        // Close our outbound pipe so downstream sees EOF.
                        if (idx < n - 1) pipes[idx].sink.close()
                        // Close our inbound pipe so upstream sees SIGPIPE on
                        // its next write — POSIX `yes | head` semantics:
                        // head exits → kernel closes its stdin pipe → yes's
                        // next write trips SIGPIPE. Without this, an infinite
                        // producer would loop forever past pipeline shutdown.
                        if (idx > 0) pipes[idx - 1].source.close()
                    }
                }
            }
        jobs.forEachIndexed { i, j ->
            val code = j.await()
            stageCodes?.set(i, code)
            if (i == n - 1) lc = code
        }
    }
    return lc
}

/**
 * Launch [pipeline] under [sessionScope]. Pre-forks one subshell per
 * stage **synchronously at `&` time** — matches bash's "fork-per-stage,
 * no wrapper shell" model. The launched coroutine just runs the
 * already-forked stages via [runForkedStages]; it doesn't touch the
 * parent's interpreter state at all, so foreground mutations of
 * `outSink`/`currentStdinIsTty`/etc. after `&` don't race the
 * background.
 *
 * Snapshot timing matters: in `x=1; cmd $x &; x=2`, the background
 * command must see `x=1` (its state at the moment `&` fired), not
 * `x=2`. Pre-forking synchronously gets this for free — each stage
 * fork copies parent env/cwd/etc. *now*, before this function returns.
 */
internal suspend fun Interpreter.dispatchBackground(
    pipeline: Pipeline,
    initialStdin: SuspendSource,
) {
    val capturedErr = errSink
    val pretty = prettyPrintPipeline(pipeline)
    // Pre-fork at `&` time so each stage sees parent state at the moment
    // `&` fired, not whenever the background coroutine happens to wake up.
    val n = pipeline.commands.size
    val stageForks = ArrayList<Interpreter>(n)
    try {
        for (i in 0 until n) {
            val s = forkSubshell()
            // See foreground-pipeline note: stages fork+exec, so
            // FD_CLOEXEC entries drop here.
            // Drop FD_CLOEXEC entries — semantically a pipeline stage
            // is fork+exec; without this, `exec 5>file; cmd | grep`
            // would leave fd 5 visible inside the stage. We avoid the
            // full [KashProcess.execReset] because that also flips
            // signal Handlers to Default, and pipeline stages
            // (subshells) inherit traps in bash, not exec'd commands.
            run {
                val toDrop =
                    s.process.fdTable.entries
                        .filter { it.value.closeOnExec }
                        .map { it.key }
                for (fd in toDrop) {
                    s.process.fdTable
                        .remove(fd)
                        ?.ofd
                        ?.release()
                }
            }
            stageForks += s
        }
    } catch (e: com.accucodeai.kash.api.ForkException) {
        // RLIMIT_NPROC: roll back the stages we already got, emit the
        // bash-style fork error on the foreground stderr, and return.
        // No job is launched; `$!` keeps its prior value. The
        // foreground statement still exits 0 — POSIX `cmd &` is a
        // dispatch, not the command itself.
        for (stage in stageForks) {
            signalRouter.unregister(stage.process.pid)
            machine.unregisterProcess(stage.process.pid)
        }
        capturedErr.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
        return
    }
    // Tty bits are captured here for the same reason — the foreground
    // may flip `currentStdinIsTty` (it gets cleared after each statement
    // consumes its stdin) before the background coroutine wakes up.
    val capturedStdinIsTty = currentStdinIsTty
    val capturedMachine = machine
    val capturedSignalRouter = signalRouter
    // POSIX $!: pid of the most recent background job's leader process.
    // Pre-fork stage 0 is the leader (matches bash, where `cmd | grep`
    // backgrounded reports cmd's pid, the first stage).
    val memberPids = stageForks.map { it.process.pid }
    // Synthetic `setpgid`: every stage in this pipeline joins a new
    // process group led by stage 0. Matches bash's per-pipeline pgid
    // assignment via `setpgid(pid, pid)` for the leader and
    // `setpgid(pid, leader)` for each subsequent stage. The
    // machine-wide `processGroups` map records membership so a future
    // `kill -<pgid>` and `jobs -l`'s pgid column can read it directly.
    val pipelinePgid = memberPids.first()
    val pgidMembers = capturedMachine.processGroups.getOrPut(pipelinePgid) { mutableSetOf() }
    for (stage in stageForks) {
        stage.process.pgid = pipelinePgid
        pgidMembers += stage.process.pid
    }
    val job = jobControl.register(pretty, memberPids, startedUnderMonitor = monitor)
    // The driver coroutine is launched directly into the session scope.
    // It owns no `Deferred<Int>` of its own — the job's completion
    // status lives on `job.done`. Cancelling `driverJob` (via
    // JobControl.signal terminate-class) tears down every stage via
    // structured cancellation; the finally completes `done` with the
    // signal exit code so reapers see a value either way.
    val driver =
        sessionScope.launch(CoroutineName("kashjob:${job.id}:${pretty.take(60)}")) {
            var exit = 0
            try {
                val raw =
                    runForkedStages(
                        pipeline = pipeline,
                        initialStdin = initialStdin,
                        out = job.stdoutBuf.asSuspendSink(),
                        err = job.stderrBuf.asSuspendSink(),
                        stageForks = stageForks,
                        stdinIsTty = capturedStdinIsTty,
                        stdoutIsTty = false,
                        stderrIsTty = false,
                        terminalControl = null,
                        stageCodes = job.stageCodes,
                    )
                exit = if (pipeline.negated) (if (raw == 0) 1 else 0) else raw
            } catch (_: CancellationException) {
                // Signal-driven teardown. SIGTERM is the catch-all signal
                // we used to default to; future work: thread the actual
                // signal through from JobControl.signal so we can pick
                // the exact 128+N code per fatal signal class.
                exit = fatalSignalExitCode(com.accucodeai.kash.api.signal.SigTerm)
            } catch (e: ScriptAbortException) {
                // `exit N` inside a backgrounded `{ ...; }` brace group
                // or single-command pipeline propagates as the job's
                // exit code — bash treats the brace group's `exit` as
                // terminating only that backgrounded execution, with
                // its code surfacing as `$?` after `wait`.
                exit = e.code
            } catch (_: Throwable) {
                // Any other uncaught error becomes a non-zero exit.
                exit = 1
            } finally {
                for (stage in stageForks) {
                    capturedSignalRouter.unregister(stage.process.pid)
                    capturedMachine.unregisterProcess(stage.process.pid)
                }
                // Drop our pgid membership entries; if the group is now
                // empty, drop the group itself so processGroups doesn't
                // accumulate dead pgids across long sessions.
                capturedMachine.processGroups[pipelinePgid]?.let { members ->
                    for (stage in stageForks) members -= stage.process.pid
                    if (members.isEmpty()) capturedMachine.processGroups.remove(pipelinePgid)
                }
                if (!job.done.isCompleted) {
                    // Snapshot the shell's current statement counter so
                    // the `jobs` builtin can defer reaping for entries
                    // that finished during the currently-executing
                    // statement (matches bash's "cleanup_dead_jobs at
                    // sync points, not at listing site").
                    job.completedAtStatement = jobControl.topLevelStatementCounter
                    job.done.complete(exit)
                    // SIGCHLD is delivered at reap time, not completion
                    // time. Bash fires the trap AFTER the child's stdout
                    // has been collected/flushed; kash mirrors that by
                    // hooking into JobControl.reap rather than the
                    // background driver. See [JobControl.reap].
                }
            }
        }
    job.driverJob = driver
    // bash-style banner: "[<id>]\n". Bash also prints the leader pid
    // (we have it as `leaderPid` for `$!`) but we keep the minimal
    // job-id-only form to match existing conformance fixtures. Easy
    // upgrade later: `"[${job.id}] $leaderPid\n"`. The banner comes
    // from the foreground shell, not the background job, so it goes
    // straight to the foreground errSink (not job.stderrBuf).
    //
    // Bash only prints this banner in *interactive* mode (or with `set -m`
    // monitor mode on). A `bash -c 'cmd &'` from a script stays quiet. The
    // conformance harness runs non-interactive `Kash.exec`, so anything else
    // would diverge from bash's `.right` fixtures (see coproc.tests's
    // `{ sleep 1; kill $REFLECT_PID; } &`). Kash doesn't track monitor
    // mode yet — the interactive bit is the only gate we have, and it's
    // the POSIX-correct one for the script case.
    if (interactive) capturedErr.writeUtf8("[${job.id}]\n")
}

internal fun Interpreter.prettyPrintPipeline(pipeline: Pipeline): String {
    // Lightweight one-line label for `jobs` and CoroutineName. No
    // round-trippable formatting — just enough to recognize a job.
    return pipeline.commands.joinToString(" | ") { cmd -> prettyPrintCommand(cmd) }
}

private fun Interpreter.prettyPrintCommand(cmd: com.accucodeai.kash.ast.Command): String =
    when (cmd) {
        is SimpleCommand -> {
            val head = cmd.name?.let { wordToString(it) } ?: ""
            val tail = cmd.args.joinToString(" ") { wordToString(it) }
            if (tail.isEmpty()) head else "$head $tail"
        }

        // `( body )` — render as parens around a `;`-joined body so
        // `jobs` / sync-point notifications show e.g.
        // `[1]+  Done   ( sleep 5; kill -TERM $$ )` instead of the
        // useless `Subshell` class name.
        is Subshell -> {
            "( ${cmd.body.joinToString("; ") { prettyPrintStatement(it) }} )"
        }

        // `{ body; }` — bash brace group.
        is com.accucodeai.kash.ast.Group -> {
            "{ ${cmd.body.joinToString("; ") { prettyPrintStatement(it) }}; }"
        }

        else -> {
            cmd::class.simpleName ?: "command"
        }
    }

internal fun Interpreter.prettyPrintStatement(st: com.accucodeai.kash.ast.Statement): String =
    // A Statement is a list of `&&`/`||`-chained pipelines, but for the
    // job-label use case we only need to identify the work; collapse
    // chain operators to plain `;` to keep the label tight.
    st.pipelines.joinToString("; ") { prettyPrintPipeline(it) }

internal fun Interpreter.wordToString(word: Word): String =
    word.parts.joinToString("") { p ->
        when (p) {
            is WordPart.Literal -> p.value
            else -> "?"
        }
    }

internal fun Interpreter.checkPendingAbort() {
    if (pendingAbort) {
        pendingAbort = false
        throw ScriptAbortException(pendingAbortCode)
    }
}

internal fun Interpreter.finalize(
    pipeline: Pipeline,
    exit: Int,
): Int {
    val result = if (pipeline.negated) (if (exit == 0) 1 else 0) else exit
    lastExit = result
    // POSIX: `! cmd` is exempt from errexit. If the inner command set
    // pendingAbort (via the simple-command errexit check or a special-
    // builtin failure), clear it — the `!` wrapper turns the failure
    // into either a normal failure (result=1, no errexit because of !)
    // or a normal success (result=0).
    if (pipeline.negated) {
        pendingAbort = false
        pendingAbortCode = 0
    }
    return result
}

/**
 * Bash `time` reserved-word output. Emits to [Interpreter.errSink] after
 * the timed pipeline completes. kash doesn't model user/sys CPU time, and
 * the conformance corpus only exercises sub-decisecond commands where
 * `real %2R` rounds to `0.00` — so we emit zeros for all three slots
 * (matching the `times` builtin's accounting gap, by design).
 *
 *   - [com.accucodeai.kash.ast.TimeSpec.POSIX] (`time -p`): literal
 *     `real %.2f\nuser %.2f\nsys %.2f\n` per POSIX.
 *   - [com.accucodeai.kash.ast.TimeSpec.DEFAULT]: parses `$TIMEFORMAT` and
 *     substitutes the `%R`, `%U`, `%S` directives (with optional precision
 *     digit and the `l` long-form flag). Unset/empty `TIMEFORMAT` falls
 *     back to bash's default `\nreal\t%3lR\nuser\t%3lU\nsys\t%3lS`.
 */
internal suspend fun Interpreter.emitTimeReport(spec: com.accucodeai.kash.ast.TimeSpec) {
    val text =
        when (spec) {
            com.accucodeai.kash.ast.TimeSpec.POSIX -> {
                "real 0.00\nuser 0.00\nsys 0.00\n"
            }

            com.accucodeai.kash.ast.TimeSpec.DEFAULT -> {
                val fmt = env["TIMEFORMAT"] ?: "\nreal\t%3lR\nuser\t%3lU\nsys\t%3lS"
                formatTimeReport(fmt) + "\n"
            }
        }
    errSink.writeUtf8(text)
}

/**
 * Substitute bash time-format directives in [fmt]. Supports `%[N][l](R|U|S|P)`
 * and `%%`. N is precision (0–3); `l` selects "long" `Mm SS.NNNs` form. P is
 * CPU percentage. Unknown directives are passed through verbatim.
 */
private fun formatTimeReport(fmt: String): String {
    val sb = StringBuilder(fmt.length)
    var i = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c != '%') {
            sb.append(c)
            i++
            continue
        }
        // Parse %[N][l](char)
        var j = i + 1
        var precision = 3
        var longForm = false
        if (j < fmt.length && fmt[j].isDigit()) {
            precision = fmt[j].digitToInt().coerceIn(0, 3)
            j++
        }
        if (j < fmt.length && fmt[j] == 'l') {
            longForm = true
            j++
        }
        if (j >= fmt.length) {
            // dangling `%` — emit verbatim
            sb.append(fmt, i, fmt.length)
            break
        }
        val directive = fmt[j]
        when (directive) {
            '%' -> {
                sb.append('%')
            }

            'R', 'U', 'S' -> {
                sb.append(formatZeroSeconds(precision, longForm))
            }

            'P' -> {
                sb.append(formatZeroPercent(precision))
            }

            else -> {
                // Unknown — preserve original sequence.
                sb.append(fmt, i, j + 1)
            }
        }
        i = j + 1
    }
    return sb.toString()
}

private fun formatZeroSeconds(
    precision: Int,
    longForm: Boolean,
): String {
    val frac =
        when (precision) {
            0 -> ""
            1 -> ".0"
            2 -> ".00"
            else -> ".000"
        }
    return if (longForm) "0m0$frac" + "s" else "0$frac"
}

private fun formatZeroPercent(precision: Int): String =
    when (precision) {
        0 -> "0%"
        1 -> "0.0%"
        2 -> "0.00%"
        else -> "0.000%"
    }

/**
 * Execute one command with the given [base] stdio, applying any
 * redirections on top. Returns its exit code.
 */
internal suspend fun Interpreter.executeWithStdio(
    cmd: CommandNode,
    base: Stdio,
): Int {
    // Update `$LINENO` for every command, before any expansion fires.
    // Compound commands cover their own internals when they recurse back
    // into executeWithStdio for child statements.
    //
    // Inside a trap handler, `$LINENO` should reflect the line of the
    // outer command that triggered the trap (bash DEBUG semantics) — so
    // we leave [currentLine] pinned to its caller-set value. The handler
    // body still parses/runs normally; only `$LINENO` is fixed.
    if (!lineFrozenForTrap) {
        currentLine = cmd.line
        // Bash sets `$BASH_COMMAND` to the source text of the simple
        // command currently in flight (and leaves it pinned during a
        // trap handler, alongside $LINENO — that's why the assignment
        // sits inside the same `lineFrozenForTrap` gate). The parser
        // fills `srcText` from the original source slice; ASTs built
        // without source (snapshots, brace-expansion synthesized
        // statements) leave srcText null and the variable retains its
        // prior value, matching bash's "last command" semantics.
        //
        // Write directly to the var table (NOT via `env[…]=`), because
        // `env[…]=` goes through ProcessEnvAdapter which mirrors every
        // write into process.env — and process.env leaks to in-process
        // builtins like `printenv` that iterate the OS-env view. Bash
        // keeps BASH_COMMAND as a shell variable (not exported), so
        // subprocesses never see it; we match that here. Tested by
        // bash/exportfunc.tests's `printenv | grep 'foo=bar'` which
        // should produce empty output when no env var matches.
        if (cmd is SimpleCommand) {
            cmd.srcText?.let { text ->
                val v = varTable.findOrCreate("BASH_COMMAND")
                v.value = VariableValue.Scalar(text)
            }
        }
    }
    // Snapshot the live procsub fd set before any expansion fires. The
    // command's redirection-target words and (for simple commands) its
    // arg words may allocate new procsubs via `<(...)` / `>(...)` — those
    // need to scope to THIS command, not leak into the next iteration of
    // a containing loop. Reclamation runs in the finally block: anything
    // added during this command gets closed.
    //
    // Exception: assignment-only simple commands like `A=<(true)` bind
    // the procsub path into a variable for later use. Reclaiming at the
    // assignment's end would invalidate the variable before the script
    // can dereference it. Bash's documented behavior (Bash Hackers wiki
    // on process substitution) extends procsub lifetime to the enclosing
    // scope for assignments — we approximate that by skipping the close
    // pass entirely for assignment-only commands.
    val isAssignmentOnly = cmd is SimpleCommand && cmd.name == null
    val procsubSnapshot = if (isAssignmentOnly) null else procsubFds.toSet()
    val (stdio, cleanups) =
        applyRedirections(cmd.redirections, base) ?: run {
            procsubSnapshot?.let { reclaimProcsubsSince(it) }
            return 1
        }
    try {
        return when (cmd) {
            is SimpleCommand -> {
                runSimple(cmd, stdio)
            }

            is IfCommand -> {
                runIf(cmd, stdio)
            }

            is ForCommand -> {
                runFor(cmd, stdio)
            }

            is ArithForCommand -> {
                runArithFor(cmd, stdio)
            }

            is ArithCommand -> {
                runArithCommand(cmd)
            }

            is WhileCommand -> {
                runWhile(cmd, stdio)
            }

            is CaseCommand -> {
                runCase(cmd, stdio)
            }

            is CondCommand -> {
                runCond(cmd)
            }

            is Group -> {
                runGroup(cmd, stdio)
            }

            is Subshell -> {
                runSubshell(cmd, stdio)
            }

            is CoprocCommand -> {
                runCoproc(cmd, stdio)
            }

            is FunctionDef -> {
                // Bash function-name validation: names may not contain
                // `$`, the unquoted subshell-cmdsub openers `<(` / `>(`,
                // or backticks. Bash diagnoses with
                // `script: line N: \`NAME': not a valid identifier`
                // and exits the definition with status 1 (the script
                // continues). The line reported is the end of the
                // function-definition statement — kash records the
                // start line on the FunctionDef; close enough for the
                // recovery semantics.
                if (!isValidFunctionName(cmd.name)) {
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}`${cmd.name}': not a valid identifier\n",
                    )
                    1
                } else if (posixModeRuntime && isSpecialBuiltinName(cmd.name)) {
                    // POSIX mode rejects redefining a special builtin
                    // as a function. Diagnoses and aborts the script
                    // (bash terminates the current shell on this in
                    // non-interactive mode — for a subshell that means
                    // the subshell exits before subsequent statements).
                    stdio.stderr.writeUtf8(
                        "${shellDiagPrefix()}`${cmd.name}': is a special builtin\n",
                    )
                    throw ScriptAbortException(1)
                } else {
                    functions[cmd.name] = cmd
                    0
                }
            }
        }
    } finally {
        // `exec >FILE` / `exec 3<FILE` form: the no-command exec intrinsic
        // sets [execPersistedRedirections] to steal these redirections into
        // long-lived script-level state (outSink/errSink for fd 0/1/2; the
        // process's fdTable for fd ≥ 3). Skip the cleanup actions so the
        // sinks and OFDs outlive this SimpleCommand. They'll be closed at
        // shell exit, by a later `exec`, or by an explicit `exec N>&-`.
        if (execPersistedRedirections) {
            execPersistedRedirections = false
        } else {
            for (c in cleanups) {
                try {
                    c()
                } catch (_: Throwable) {
                }
            }
        }
        // Close any procsub fds allocated during this command. Consumers
        // that opened `/dev/fd/N` already hold their own dup'd OFD, so
        // dropping the parent-side entry doesn't tear the pipe out from
        // under them.
        procsubSnapshot?.let { reclaimProcsubsSince(it) }
    }
}

/**
 * Close every procsub fd that wasn't present in [before]. Called from
 * [executeWithStdio]'s finally so per-command-scope reclamation matches
 * bash's snapshot-and-close-new pattern.
 */
internal fun Interpreter.reclaimProcsubsSince(before: Set<Int>) {
    if (procsubFds.size == before.size) return
    val toClose = procsubFds.filter { it !in before }
    for (fd in toClose) {
        process.fdTable
            .remove(fd)
            ?.ofd
            ?.release()
        procsubFds.remove(fd)
    }
}

/**
 * Bash function-name validation. Bash accepts a much wider set than
 * POSIX `name`: digits as the first char, `=`, `+`, `-`, `.`, `:`
 * etc. are all fine; only a small list of characters that would
 * confuse later command resolution / quoting is rejected.
 *
 * Rejected (bash's `legal_identifier` for function names): empty,
 * contains `$`, contains `<`, contains `>`, contains `(`, contains
 * `)`, contains backtick, contains a space.
 */
internal fun isSpecialBuiltinName(name: String): Boolean {
    val entry = com.accucodeai.kash.intrinsics.IntrinsicCatalog.byName[name]
    return entry?.isSpecial == true
}

internal fun isValidFunctionName(name: String): Boolean {
    if (name.isEmpty()) return false
    for (c in name) {
        if (c == '$' || c == '<' || c == '>' || c == '(' || c == ')' || c == '`' || c == ' ' || c == '\t' ||
            c == '\n'
        ) {
            return false
        }
    }
    return true
}
