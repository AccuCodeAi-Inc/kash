package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.AccessMode
import com.accucodeai.kash.api.FdTableEntry
import com.accucodeai.kash.api.OpenFileDescription
import com.accucodeai.kash.api.io.AsyncPipe
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.ast.CoprocCommand
import com.accucodeai.kash.interpreter.Interpreter.ScriptAbortException
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.jobs.fatalSignalExitCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.launch
import com.accucodeai.kash.ast.Command as CommandNode

/**
 * Execute `coproc [NAME] body` — bash coprocess.
 *
 * Allocates two channel-backed pipes:
 *   - `toChild`   — parent writes via `${NAME}[1]`, child reads as stdin.
 *   - `fromChild` — child writes as stdout, parent reads via `${NAME}[0]`.
 *
 * The body runs asynchronously under [jobControl], pre-forked on a
 * subshell interpreter (so mutations don't leak back). The parent-side
 * pipe fds are installed in this interpreter's [process] fdTable at the
 * lowest free slots >= 10 — matching bash's "high-fd" allocation policy
 * that keeps `${COPROC[N]}` from clashing with conventional 0/1/2 use
 * after later redirections.
 *
 * Sets shell state for the script to consume:
 *   - `${NAME}[0]` = parent's read fd as a decimal string
 *   - `${NAME}[1]` = parent's write fd as a decimal string
 *   - `${NAME}_PID` = child's pid
 *   - `$!` = child's pid (via the jobControl launch)
 *
 * Returns 0 (like `&`) — the coproc statement itself never blocks. Bash's
 * "only one active coproc" warning is intentionally NOT enforced in v1;
 * the conformance test only ever has one live at a time.
 */
internal suspend fun Interpreter.runCoproc(
    cmd: CoprocCommand,
    stdio: Stdio,
): Int {
    val name = cmd.name ?: "COPROC"

    val toChild = AsyncPipe()
    val fromChild = AsyncPipe()

    // Parent-side fds: lowest free >= 10. Bash uses 63/60 in its baseline
    // (high fds counted down from FD_SETSIZE); the actual number is
    // unspecified — scripts only test that they're decimal integers.
    //
    // Coproc fds are explicitly user-managed (exposed via ${NAME[0]} /
    // ${NAME[1]} and torn down by the script's own `exec {fd}>&-`), so
    // unlike procsub there's no automatic per-command reclamation —
    // NOFILE here gates the user's coproc invocations, which is the
    // intended bash behavior.
    val readFd =
        allocHighFd(process) ?: run {
            errSink.writeUtf8(
                "${shellDiagPrefix()}coproc: cannot allocate file descriptor: Too many open files\n",
            )
            return 1
        }
    process.fdTable[readFd] =
        FdTableEntry(
            ofd =
                OpenFileDescription(
                    source = fromChild.source,
                    accessMode = AccessMode.RDONLY,
                    owning = true,
                    pipeInode = fromChild.inode,
                ),
            // Coproc fds are bash-script-visible (via ${NAME[0]}/[1]) and
            // must NOT survive across an exec — matches bash, which sets
            // FD_CLOEXEC on coproc pipe ends so a child exec'd from the
            // parent shell doesn't inherit them.
            closeOnExec = true,
        )
    val writeFd =
        allocHighFd(process) ?: run {
            process.fdTable
                .remove(readFd)
                ?.ofd
                ?.release()
            errSink.writeUtf8(
                "${shellDiagPrefix()}coproc: cannot allocate file descriptor: Too many open files\n",
            )
            return 1
        }
    process.fdTable[writeFd] =
        FdTableEntry(
            ofd =
                OpenFileDescription(
                    sink = toChild.sink,
                    accessMode = AccessMode.WRONLY,
                    owning = true,
                    pipeInode = toChild.inode,
                ),
            closeOnExec = true,
        )

    // Pre-fork synchronously so the child sees parent state at coproc-time.
    val childFork =
        try {
            forkSubshell()
        } catch (e: com.accucodeai.kash.api.ForkException) {
            stdio.stderr.writeUtf8("${shellDiagPrefix()}fork: ${e.message}\n")
            return 1
        }
    val childPid = childFork.process.pid

    // ${NAME}[0]/[1] and ${NAME}_PID set BEFORE we launch, so a tight
    // `coproc { ...; }; echo $COPROC_PID` reads them deterministically.
    val arr = indexedArrays.getOrPut(name) { mutableMapOf() }
    arr.clear()
    arr[0] = readFd.toString()
    arr[1] = writeFd.toString()
    env["${name}_PID"] = childPid.toString()

    val capturedStdinIsTty = currentStdinIsTty
    val capturedMachine = machine
    val capturedSignalRouter = signalRouter
    val pretty =
        when (val body = cmd.body) {
            is com.accucodeai.kash.ast.SimpleCommand -> {
                val head = body.name?.let { wordToString(it) } ?: ""
                val tail = body.args.joinToString(" ") { wordToString(it) }
                if (tail.isEmpty()) "coproc $head" else "coproc $head $tail"
            }

            else -> {
                "coproc ${body::class.simpleName ?: "body"}"
            }
        }

    val job = jobControl.register(pretty, listOf(childPid), startedUnderMonitor = monitor)
    val driver =
        sessionScope.launch(CoroutineName("kashjob:${job.id}:${pretty.take(60)}")) {
            var exit = 0
            try {
                val childStdio =
                    Stdio(
                        stdin = toChild.source,
                        stdout = fromChild.sink,
                        stderr = stdio.stderr,
                        stdinIsTty = capturedStdinIsTty,
                        stdoutIsTty = false,
                        stderrIsTty = false,
                    )
                exit =
                    try {
                        childFork.runCoprocBody(cmd.body, childStdio)
                    } catch (_: ScriptAbortException) {
                        1
                    }
            } catch (_: CancellationException) {
                exit = fatalSignalExitCode(com.accucodeai.kash.api.signal.SigTerm)
            } catch (_: Throwable) {
                exit = 1
            } finally {
                try {
                    fromChild.sink.close()
                } catch (_: Throwable) {
                }
                try {
                    toChild.source.close()
                } catch (_: Throwable) {
                }
                capturedSignalRouter.unregister(childPid)
                capturedMachine.unregisterProcess(childPid)
                if (!job.done.isCompleted) job.done.complete(exit)
            }
        }
    job.driverJob = driver
    // Register this coproc's fds in the interpreter-wide fd→coproc-name
    // map so the redir runtime can reset `${NAME}[@]` to `-1 -1` when a
    // user-issued `exec N<&${NAME[0]}-` / `exec >&${NAME[1]}-` closes one
    // of these fds (matches bash's behavior: `echo ${COPROC[@]}` after
    // the move prints `-1 -1`, not the original fd numbers).
    coprocFdOwner[readFd] = name
    coprocFdOwner[writeFd] = name
    // `kill ${NAME}_PID` parks fatal-signal cleanup here: closing the
    // parent→child sink makes the body's blocked `cat -` (or any other
    // tool reading from its stdin) see EOF and exit cleanly. AsyncPipe's
    // receive is a cancellable coroutine suspension now (suspend-native),
    // so structured cancellation would also work — but closing the sink
    // is the cleaner POSIX-shaped shutdown: it lets the child drain any
    // already-queued bytes and exit on EOF instead of throwing
    // CancellationException mid-read. See [KashJob.onFatalSignal] for the
    // signal-routing rationale.
    job.onFatalSignal {
        try {
            toChild.sink.close()
        } catch (_: Throwable) {
        }
    }
    return 0
}

/** Run a coproc body on the child fork. Single-Statement wrap so the
 *  body's exit code flows through the normal pipeline finalize path. */
private suspend fun Interpreter.runCoprocBody(
    body: CommandNode,
    stdio: Stdio,
): Int {
    outSink = stdio.stdout
    errSink = stdio.stderr
    return try {
        executeWithStdio(body, stdio)
    } catch (e: ScriptAbortException) {
        e.code
    } finally {
        runExitTrap()
    }
}
