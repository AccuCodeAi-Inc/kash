package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.RLimit
import com.accucodeai.kash.api.RLimitPair
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.interpreter.Interpreter.Stdio

/**
 * Bash `ulimit [-SHa] [-cdfklmnpstuv [LIMIT]]`. Reads or writes a
 * per-process resource limit stored on [com.accucodeai.kash.api.KashProcess.rlimits].
 *
 * Enforcement today:
 *  - `NPROC` (`-u`): the fork seam in
 *    [com.accucodeai.kash.api.DefaultKashProcess.fork] rejects when
 *    `processTable.size >= soft`.
 *  - `NOFILE` (`-n`): the shared fd allocator
 *    [com.accucodeai.kash.interpreter.allocHighFd] returns null when the
 *    next free fd would meet or exceed `soft`. Honored by all three
 *    fd-allocating sites — the `{NAME}>file` anonymous-fd redirection,
 *    coproc parent-side pipes, and `<(...)`/`>(...)` process
 *    substitution. Procsub fds are auto-reclaimed at the end of each
 *    enclosing command (see [Interpreter.procsubFds] /
 *    [reclaimProcsubsSince]) so tight loops like
 *    `for c in $(seq 1000); do read x < <(echo y); done` don't leak
 *    fds even under a low NOFILE limit. Coproc fds are user-managed
 *    (exposed as `${NAME[@]}`); the script closes them explicitly.
 *
 * Other letter flags parse and store into the per-process map but have
 * no consumer yet; storage is scaffolding for future enforcement.
 *
 * Flag → enum mapping mirrors bash:
 *
 *   -c  CORE      core file size (blocks)
 *   -d  DATA      data-segment size (kbytes)
 *   -f  FSIZE     file size (blocks)
 *   -l  MEMLOCK   max locked memory (kbytes)
 *   -m  RSS       max resident set size (kbytes)
 *   -n  NOFILE    max open file descriptors      (ENFORCED)
 *   -s  STACK     stack size (kbytes)
 *   -t  CPU       cpu time (seconds)
 *   -u  NPROC     max user processes              (ENFORCED)
 *   -v  AS        virtual memory (kbytes)
 *
 * `-S` selects the soft limit, `-H` the hard limit; without either,
 * read returns the soft value and write sets both (bash convention).
 * The literal `unlimited` reads/writes [Long.MAX_VALUE]. Non-root may
 * lower the hard limit but never raise it (POSIX `setrlimit(2)`);
 * we gate on `process.effectiveUid != 0`.
 */
internal suspend fun Interpreter.runUlimitIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    var soft = true
    var hard = true
    var showAll = false
    val selected = mutableListOf<RLimit>()

    var i = 0
    while (i < args.size && args[i].startsWith("-") && args[i] != "-") {
        val a = args[i]
        if (a == "--") {
            i++
            break
        }
        // Each char after the leading '-' is its own flag (`-Hu`, `-aS`, etc.).
        var k = 1
        while (k < a.length) {
            when (val ch = a[k]) {
                'S' -> {
                    soft = true
                    hard = false
                }

                'H' -> {
                    soft = false
                    hard = true
                }

                'a' -> {
                    showAll = true
                }

                'c' -> {
                    selected += RLimit.CORE
                }

                'd' -> {
                    selected += RLimit.DATA
                }

                'f' -> {
                    selected += RLimit.FSIZE
                }

                'l' -> {
                    selected += RLimit.MEMLOCK
                }

                'm' -> {
                    selected += RLimit.RSS
                }

                'n' -> {
                    selected += RLimit.NOFILE
                }

                's' -> {
                    selected += RLimit.STACK
                }

                't' -> {
                    selected += RLimit.CPU
                }

                'u' -> {
                    selected += RLimit.NPROC
                }

                'v' -> {
                    selected += RLimit.AS
                }

                // The flags below are accepted for bash-completion parity
                // but kash doesn't model these resources — treat them as
                // unlimited on read and silently accept-and-discard on write.
                'b', 'e', 'i', 'p', 'q', 'r', 'x', 'T' -> {
                    Unit
                }

                else -> {
                    stdio.stderr.writeUtf8("ulimit: -$ch: invalid option\n")
                    stdio.stderr.writeUtf8("ulimit: usage: ulimit [-SHacdfklmnpstuv] [limit]\n")
                    return 2
                }
            }
            k++
        }
        i++
    }
    val operand = args.drop(i).firstOrNull()

    if (showAll) {
        for (limit in ALL_LIMITS) writeRow(limit, soft, stdio)
        return 0
    }
    if (selected.isEmpty()) {
        // Bash default: bare `ulimit` (no flag, no operand) prints FSIZE.
        selected += RLimit.FSIZE
    }
    if (operand == null) {
        // Read path: print each selected limit on its own line.
        for (limit in selected) {
            val pair = process.rlimits[limit]
            val value = pickValue(pair, soft)
            stdio.stdout.writeUtf8(formatValue(value, limit) + "\n")
        }
        return 0
    }
    // Write path: parse operand, apply to each selected.
    val newValue =
        parseLimitValue(operand) ?: run {
            stdio.stderr.writeUtf8("ulimit: $operand: invalid number\n")
            return 1
        }
    for (limit in selected) {
        val existing = process.rlimits[limit]
        val curSoft = existing?.soft ?: Long.MAX_VALUE
        val curHard = existing?.hard ?: Long.MAX_VALUE
        val newSoft = if (soft) newValue else curSoft
        val newHard = if (hard) newValue else curHard
        // POSIX: non-root cannot raise the hard limit.
        if (hard && newHard > curHard && process.effectiveUid != 0) {
            stdio.stderr.writeUtf8("ulimit: ${limitTag(limit)}: cannot modify limit: Operation not permitted\n")
            return 1
        }
        // Soft must not exceed (the new) hard.
        if (newSoft > newHard) {
            stdio.stderr.writeUtf8("ulimit: ${limitTag(limit)}: cannot modify limit: Invalid argument\n")
            return 1
        }
        process.rlimits[limit] = RLimitPair(soft = newSoft, hard = newHard)
    }
    return 0
}

private val ALL_LIMITS: List<RLimit> =
    listOf(
        RLimit.CORE,
        RLimit.DATA,
        RLimit.FSIZE,
        RLimit.MEMLOCK,
        RLimit.RSS,
        RLimit.NOFILE,
        RLimit.STACK,
        RLimit.CPU,
        RLimit.NPROC,
        RLimit.AS,
    )

private suspend fun Interpreter.writeRow(
    limit: RLimit,
    soft: Boolean,
    stdio: Stdio,
) {
    val pair = process.rlimits[limit]
    val value = pickValue(pair, soft)
    val label = bashLabelFor(limit)
    stdio.stdout.writeUtf8(label + "\t" + formatValue(value, limit) + "\n")
}

private fun pickValue(
    pair: RLimitPair?,
    soft: Boolean,
): Long {
    if (pair == null) return Long.MAX_VALUE
    return if (soft) pair.soft else pair.hard
}

private fun parseLimitValue(operand: String): Long? {
    if (operand == "unlimited") return Long.MAX_VALUE
    return operand.toLongOrNull()?.takeIf { it >= 0 }
}

private fun formatValue(
    value: Long,
    limit: RLimit,
): String {
    if (value == Long.MAX_VALUE) return "unlimited"
    return value.toString()
}

/** Short tag used in error messages (matches bash). */
private fun limitTag(limit: RLimit): String =
    when (limit) {
        RLimit.CORE -> "core file size"
        RLimit.DATA -> "data seg size"
        RLimit.FSIZE -> "file size"
        RLimit.MEMLOCK -> "max locked memory"
        RLimit.RSS -> "max memory size"
        RLimit.NOFILE -> "open files"
        RLimit.STACK -> "stack size"
        RLimit.CPU -> "cpu time"
        RLimit.NPROC -> "max user processes"
        RLimit.AS -> "virtual memory"
    }

/** Label printed in the `ulimit -a` table (bash format). */
private fun bashLabelFor(limit: RLimit): String =
    when (limit) {
        RLimit.CORE -> "core file size          (blocks, -c)"
        RLimit.DATA -> "data seg size           (kbytes, -d)"
        RLimit.FSIZE -> "file size               (blocks, -f)"
        RLimit.MEMLOCK -> "max locked memory       (kbytes, -l)"
        RLimit.RSS -> "max memory size         (kbytes, -m)"
        RLimit.NOFILE -> "open files                      (-n)"
        RLimit.STACK -> "stack size              (kbytes, -s)"
        RLimit.CPU -> "cpu time               (seconds, -t)"
        RLimit.NPROC -> "max user processes              (-u)"
        RLimit.AS -> "virtual memory          (kbytes, -v)"
    }
