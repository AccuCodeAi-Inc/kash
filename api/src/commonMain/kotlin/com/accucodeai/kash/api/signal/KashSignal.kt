package com.accucodeai.kash.api.signal

/**
 * Signals that kash models. **Not** real POSIX signals — kash is in-process,
 * so there is no kernel-level signal delivery. These are *cooperative*
 * signals delivered via [JobSignalContext] to a running coroutine, and (for
 * INT/TERM) also via `Job.cancel()` so suspending tools see prompt
 * cancellation at their next `delay` / suspending read.
 *
 * SIGKILL is deliberately absent — there is no way to preempt a coroutine
 * that ignores cancellation without killing the host thread, which would
 * tear down the rest of the session. `kill -9` against a kash job is a
 * documented no-op (logs a diagnostic but does nothing).
 *
 * [SigExit] is a pseudo-signal: it is never delivered through a channel.
 * It's an entry in the trap table so `trap '…' EXIT` fires the handler
 * from the interpreter's top-level finally block, mirroring POSIX bash.
 */
public sealed interface KashSignal {
    public val name: String
    public val number: Int

    public companion object {
        public val ALL: List<KashSignal> =
            listOf(
                SigHup,
                SigInt,
                SigQuit,
                SigAbrt,
                SigKill,
                SigTerm,
                SigPipe,
                SigUsr1,
                SigUsr2,
                SigChld,
                SigTstp,
                SigCont,
                SigStop,
                SigExit,
                SigDebug,
                SigReturn,
                SigErr,
            )

        /** Resolve a token to a signal. Accepts `INT`, `SIGINT`, `2`, `EXIT`, etc. (case-insensitive). */
        public fun parse(token: String): KashSignal? {
            val t = token.uppercase()
            val stripped = if (t.startsWith("SIG")) t.removePrefix("SIG") else t
            ALL.firstOrNull { it.name == stripped }?.let { return it }
            val n = stripped.toIntOrNull() ?: return null
            // Pseudo-signals (DEBUG/RETURN/ERR/EXIT) are not addressable by
            // number in bash — only by name. We give them sentinel negative
            // numbers internally; numeric lookup excludes them.
            return ALL.firstOrNull { it.number == n && it.number >= 0 }
        }
    }
}

/**
 * Render this signal as a single-bit mask in the format kernel
 * `/proc/<pid>/status` Sig* lines use: bit `(number - 1)` of a 64-bit
 * mask. Returns `0L` for sentinel-numbered signals (SigExit, SigDebug,
 * SigReturn, SigErr — pseudo-signals with no kernel-level signum, so
 * they can't appear in a Linux-shaped sigmask).
 */
public fun KashSignal.bit(): Long = if (number in 1..64) 1L shl (number - 1) else 0L

/**
 * Fold a signal set into a 16-hex-char zero-padded lowercase mask,
 * matching kernel `%016lx` for `/proc/<pid>/status`'s
 * SigPnd/SigBlk/SigIgn/SigCgt lines. Pseudo-signals contribute 0.
 */
public fun Iterable<KashSignal>.toSigmaskHex(): String {
    var m = 0L
    for (s in this) m = m or s.bit()
    return m.toULong().toString(16).padStart(16, '0')
}

public data object SigHup : KashSignal {
    override val name: String = "HUP"
    override val number: Int = 1
}

public data object SigInt : KashSignal {
    override val name: String = "INT"
    override val number: Int = 2
}

public data object SigQuit : KashSignal {
    override val name: String = "QUIT"
    override val number: Int = 3
}

/**
 * SIGABRT — bash accepts `trap … 6 / ABRT / SIGABRT` and queues a handler.
 * Kash is in-process so there's no kernel SIGABRT delivery; trap
 * registration is tracked for source-compatibility with scripts that list
 * `6` alongside other signal numbers (common idiom for "any error path").
 */
public data object SigAbrt : KashSignal {
    override val name: String = "ABRT"
    override val number: Int = 6
}

public data object SigPipe : KashSignal {
    override val name: String = "PIPE"
    override val number: Int = 13
}

public data object SigTerm : KashSignal {
    override val name: String = "TERM"
    override val number: Int = 15
}

/**
 * SIGKILL — POSIX-uncatchable terminate. Kash is in-process so we can't
 * actually preempt a coroutine that ignores cancellation; in practice the
 * driverJob.cancel() path used by [SigTerm] / [SigHup] / etc. is the
 * strongest delivery we have. Treat KILL as "terminate immediately" so
 * `kill -9 %1` and `kill -KILL <pid>` produce the expected `done` with a
 * `128+9` exit code instead of silently being dropped at the no-op tail
 * of [com.accucodeai.kash.jobs.JobControl.signal].
 */
public data object SigKill : KashSignal {
    override val name: String = "KILL"
    override val number: Int = 9
}

public data object SigUsr1 : KashSignal {
    override val name: String = "USR1"
    override val number: Int = 10
}

public data object SigUsr2 : KashSignal {
    override val name: String = "USR2"
    override val number: Int = 12
}

/**
 * SIGCHLD — child-process state change. Kash is in-process, but jobs
 * are tracked so completion of a `&` background job can fire a registered
 * trap handler. Wired up so `trap '…' SIGCHLD` registers without erroring;
 * delivery happens when a JobControl-tracked job reaches DONE.
 * Number 17 mirrors Linux SIGCHLD.
 */
public data object SigChld : KashSignal {
    override val name: String = "CHLD"
    override val number: Int = 20
}

/**
 * Job-control stop request. Kash is in-process so we don't actually
 * preempt a running coroutine — the signal flips the job into
 * [com.accucodeai.kash.jobs.JobState.STOPPED] and any tool that polls its
 * [JobSignalContext] can cooperatively park. `kill -STOP %1` and Ctrl-Z
 * both deliver this. Number 18 mirrors Linux SIGTSTP.
 */
public data object SigTstp : KashSignal {
    override val name: String = "TSTP"
    override val number: Int = 18
}

/**
 * Job-control continue request. Clears the stopped flag and resumes the
 * job's logical state. Number 19 mirrors Linux SIGCONT.
 */
public data object SigCont : KashSignal {
    override val name: String = "CONT"
    override val number: Int = 19
}

/**
 * Alias for [SigTstp] — POSIX `kill -STOP` is "uncatchable stop." We don't
 * model the distinction because there's no real kernel here, so this just
 * routes to the same job-state transition. Number 17 mirrors Linux SIGSTOP.
 */
public data object SigStop : KashSignal {
    override val name: String = "STOP"
    override val number: Int = 17
}

/**
 * Pseudo-signal fired by the interpreter at script termination — never
 * delivered through [JobSignalContext], only used as a trap-table key.
 * Number 0 is the conventional placeholder (POSIX `kill -0` is a permission
 * check, not a real signal — we just don't ever deliver this).
 */
public data object SigExit : KashSignal {
    override val name: String = "EXIT"
    override val number: Int = 0
}

/**
 * Bash pseudo-signal: handler fires before each simple command. Never
 * delivered through [JobSignalContext]. Not POSIX — bash extension widely
 * used for debugger-like tracing. Negative sentinel number so it never
 * collides with real signals and is not addressable as `trap … 100`.
 */
public data object SigDebug : KashSignal {
    override val name: String = "DEBUG"
    override val number: Int = -1
}

/**
 * Bash pseudo-signal: handler fires when a function (or sourced script)
 * returns. Not POSIX.
 */
public data object SigReturn : KashSignal {
    override val name: String = "RETURN"
    override val number: Int = -2
}

/**
 * Bash pseudo-signal: handler fires whenever a simple command returns a
 * non-zero exit status (outside the test position of `if`/`while`/`until`,
 * `&&`/`||` left operand, and `!`). Not POSIX. Wired separately by the
 * `set -E`/`errtrace` shopt; the [TrapTable] entry exists either way so the
 * `trap … ERR` command can register a script.
 */
public data object SigErr : KashSignal {
    override val name: String = "ERR"
    override val number: Int = -3
}
