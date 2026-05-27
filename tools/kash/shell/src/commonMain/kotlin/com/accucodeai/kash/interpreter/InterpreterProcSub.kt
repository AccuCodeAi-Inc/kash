package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.emptySource
import com.accucodeai.kash.ast.Script
import com.accucodeai.kash.interpreter.Interpreter.ScriptAbortException
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.jobs.fatalSignalExitCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch

/**
 * Evaluate a `<(...)` / `>(...)` process substitution.
 *
 * - For `<(body)` (direction `'<'`): parent reads, child writes. Install a
 *   parent-side read fd on `process.fdTable` whose OFD's source is the pipe's
 *   reader; the child runs with its stdout wired to the pipe's sink. The
 *   parent sees a path `/dev/fd/N` that, when opened (via `DevFs`/`ProcFs`'s
 *   `dupFromFdTable`), vends a fresh OFD onto the same pipe.
 *
 * - For `>(body)` (direction `'>'`): parent writes, child reads. Symmetric:
 *   parent's fd is WRONLY into the pipe's sink; child's stdin is the source.
 *
 * The child body runs asynchronously under [jobControl], outliving the
 * producing/consuming command — `cat <(echo a; sleep 1; echo b)` drains the
 * first line, blocks on the second until the child gets there, then closes
 * EOF. Mirrors coproc's lifetime model (see [runCoproc]).
 *
 * Returns the path string `/dev/fd/N` to splice back into the surrounding
 * word as a [WordPart.ExpandedText].
 */
internal suspend fun Interpreter.evalProcessSubstitution(
    direction: Char,
    script: Script,
): String {
    val pipe = AsyncPipe()
    val fd =
        allocHighFd(process) ?: run {
            errSink.writeUtf8(
                "${shellDiagPrefix()}cannot allocate file descriptor for process substitution: Too many open files\n",
            )
            return ""
        }
    val parentOfd =
        when (direction) {
            '<' -> {
                OpenFileDescription(
                    source = pipe.source,
                    accessMode = AccessMode.RDONLY,
                    owning = true,
                    pipeInode = pipe.inode,
                )
            }

            '>' -> {
                OpenFileDescription(
                    sink = pipe.sink,
                    accessMode = AccessMode.WRONLY,
                    owning = true,
                    pipeInode = pipe.inode,
                )
            }

            else -> {
                error("ProcessSubstitution direction must be '<' or '>', got '$direction'")
            }
        }
    process.fdTable[fd] = FdTableEntry(ofd = parentOfd)
    // Register so the enclosing command's reclamation pass knows to
    // close this fd when the command exits.
    procsubFds += fd

    val childFork =
        try {
            forkSubshell()
        } catch (e: com.accucodeai.kash.api.ForkException) {
            // Mirror coproc's fork-fail diagnostic; the bare `>` / `<` form
            // means the caller can't sensibly recover, but a graceful path
            // is required so the parent script keeps running.
            errSink.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
            // Leave the fd installed but pointing nowhere; opening `/dev/fd/N`
            // will read EOF / get a broken pipe. That's the least surprising
            // recovery for the rare fork-failure case.
            return "/dev/fd/$fd"
        }
    val childPid = childFork.process.pid

    val capturedMachine = machine
    val capturedSignalRouter = signalRouter
    val pretty = "<procsub ${if (direction == '<') "in" else "out"}>"

    val job = jobControl.register(pretty, listOf(childPid), startedUnderMonitor = monitor)
    val driver =
        sessionScope.launch(CoroutineName("kashjob:${job.id}:${pretty.take(60)}")) {
            var exit = 0
            try {
                val childStdio =
                    when (direction) {
                        '<' -> {
                            Stdio(
                                stdin = emptySource(),
                                stdout = pipe.sink,
                                stderr = errSink,
                                stdinIsTty = false,
                                stdoutIsTty = false,
                                stderrIsTty = false,
                            )
                        }

                        else -> {
                            Stdio(
                                stdin = pipe.source,
                                stdout = outSink,
                                stderr = errSink,
                                stdinIsTty = false,
                                stdoutIsTty = false,
                                stderrIsTty = false,
                            )
                        }
                    }
                exit =
                    try {
                        childFork.runProcSubBody(script, childStdio)
                    } catch (_: ScriptAbortException) {
                        1
                    }
            } catch (_: CancellationException) {
                exit = fatalSignalExitCode(com.accucodeai.kash.api.signal.SigTerm)
            } catch (_: Throwable) {
                exit = 1
            } finally {
                when (direction) {
                    '<' -> {
                        try {
                            pipe.sink.close()
                        } catch (_: Throwable) {
                        }
                    }

                    '>' -> {
                        try {
                            pipe.source.close()
                        } catch (_: Throwable) {
                        }
                    }
                }
                capturedSignalRouter.unregister(childPid)
                capturedMachine.unregisterProcess(childPid)
                if (!job.done.isCompleted) job.done.complete(exit)
            }
        }
    job.driverJob = driver

    return "/dev/fd/$fd"
}

/**
 * Lowest free fd ≥ [start] in [process]'s fd table. Shared between
 * coproc, procsub, and the `{NAME}>file` anonymous-fd allocator.
 *
 * Honors `RLIMIT_NOFILE`: POSIX defines NOFILE as "one greater than the
 * maximum value that may be assigned to a newly-created descriptor"
 * (i.e. `fd < NOFILE.soft` is required). Returns null when the next free
 * fd would meet or exceed the soft limit — callers emit "Too many open
 * files" and skip the allocation. A null limit (unset NOFILE) means
 * unbounded, preserving prior behavior for any script that hasn't called
 * `ulimit -n`.
 */
internal fun allocHighFd(
    process: com.accucodeai.kash.api.KashProcess,
    start: Int = 10,
    enforceLimit: Boolean = true,
): Int? {
    var fd = start
    while (fd in process.fdTable) fd++
    if (!enforceLimit) return fd
    val limit = process.rlimits[com.accucodeai.kash.api.RLimit.NOFILE]?.soft ?: Long.MAX_VALUE
    return if (fd.toLong() >= limit) null else fd
}

private suspend fun Interpreter.runProcSubBody(
    script: Script,
    stdio: Stdio,
): Int {
    outSink = stdio.stdout
    errSink = stdio.stderr
    var status = 0
    return try {
        // First statement gets the procsub's input source (relevant for `>(…)`
        // where the child's `cat` should read from the pipe). Later statements
        // get emptySource(), matching bash's "stdin consumed once" semantics.
        var first = true
        for (stmt in script.statements) {
            val src = if (first) stdio.stdin else emptySource()
            first = false
            status = runStatement(stmt, src)
        }
        status
    } catch (e: ScriptAbortException) {
        e.code
    } finally {
        runExitTrap()
    }
}
