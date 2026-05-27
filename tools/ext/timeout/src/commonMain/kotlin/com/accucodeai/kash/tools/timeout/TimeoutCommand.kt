package com.accucodeai.kash.tools.timeout

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GNU-style `timeout` — run a utility, abort it if it exceeds DURATION.
 *
 * ```
 * timeout [OPTION] DURATION COMMAND [ARG]...
 * ```
 *
 * ## Coroutine model
 *
 * The whole point of this tool is *not* to block. The child runs through
 * [com.accucodeai.kash.api.UtilityRunner] (POSIX `execvp`-style), which the
 * interpreter executes **inline in this command's own coroutine** (see
 * `DefaultKashMachine.spawn` — it calls the child block directly, no
 * `launch`). Wrapping that call in [withTimeoutOrNull] therefore lets the
 * standard structured-cancellation machinery do the work: when the deadline
 * fires, a `TimeoutCancellationException` is raised at the child's next
 * suspension point (a `delay`, a pipe read/write parked on back-pressure),
 * its `finally` blocks unwind, and we get back `null`. No thread is ever
 * parked; cancellation parks the coroutine exactly like the rest of the
 * pipeline machinery.
 *
 * **Cooperative-cancellation caveat.** Like `kotlinx.coroutines` everywhere,
 * this can only interrupt a child that actually suspends. A child spinning in
 * a tight CPU loop with no suspension point won't observe the deadline until
 * it next yields — same limitation the Pyodide engine documents. I/O-bound
 * and `sleep`-style children stop promptly.
 *
 * ## Divergences from GNU coreutils (by design)
 *
 * kash has no kernel signals — the deadline is enforced by coroutine
 * cancellation, not by delivering `SIGTERM`/`SIGKILL` to a process group.
 * Consequently:
 *  - `-s`/`--signal` is accepted and only affects the `--preserve-status`
 *    exit code and the `-v` diagnostic; the same cancellation happens
 *    regardless of which signal you name.
 *  - `-k`/`--kill-after` is accepted (and its DURATION is validated) but
 *    inert: cancellation is already the hard stop, so there is no
 *    second escalation stage to schedule.
 *  - `-f`/`--foreground` is accepted and inert (no process groups here).
 *
 * ## Exit status (GNU-compatible)
 *  - `124` — COMMAND timed out (default).
 *  - `125` — `timeout` itself failed (bad option, bad DURATION, missing
 *    operand, or no interpreter context).
 *  - `126` — COMMAND was found but could not be invoked.
 *  - `127` — COMMAND was not found.
 *  - `128 + signal` — on timeout when `--preserve-status` is set (the status
 *    a signal-killed command would have reported; default signal `TERM` → 143).
 *  - otherwise — COMMAND's own exit status.
 */
public class TimeoutCommand :
    Command,
    CommandSpec {
    override val name: String = "timeout"
    override val kind: CommandKind = CommandKind.TOOL

    // Non-POSIX (GNU extension); output is the child's, but the act of timing
    // is wall-clock dependent, so flag it IMPURE like `sleep`.
    override val tags: Set<CommandTag> = setOf(CommandTag.IMPURE)
    override val command: Command get() = this

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var signalName = "TERM"
        var signalNum = SIG_TERM
        var preserveStatus = false
        var verbose = false

        // ---- Phase 1: options, up to the first non-option (or `--`). ----
        var i = 0
        options@ while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    break@options
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(USAGE)
                    return CommandResult(exitCode = 0)
                }

                a == "--version" -> {
                    ctx.stdout.writeUtf8("timeout (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "--preserve-status" -> {
                    preserveStatus = true
                    i++
                }

                a == "--verbose" -> {
                    verbose = true
                    i++
                }

                a == "--foreground" -> {
                    i++ // accepted, inert
                }

                a.startsWith("--signal=") -> {
                    val sig =
                        parseSignal(a.substringAfter('='))
                            ?: return usage(ctx, "invalid signal '${a.substringAfter('=')}'")
                    signalName = sig.first
                    signalNum = sig.second
                    i++
                }

                a == "--signal" -> {
                    val v = args.getOrNull(i + 1) ?: return usage(ctx, "option '--signal' requires an argument")
                    val sig = parseSignal(v) ?: return usage(ctx, "invalid signal '$v'")
                    signalName = sig.first
                    signalNum = sig.second
                    i += 2
                }

                a.startsWith("--kill-after=") -> {
                    val v = a.substringAfter('=')
                    parseDuration(v) ?: return usage(ctx, "invalid time interval '$v'")
                    i++ // validated, inert
                }

                a == "--kill-after" -> {
                    val v = args.getOrNull(i + 1) ?: return usage(ctx, "option '--kill-after' requires an argument")
                    parseDuration(v) ?: return usage(ctx, "invalid time interval '$v'")
                    i += 2 // validated, inert
                }

                a.length > 1 && a[0] == '-' && a != "--" && !a.startsWith("--") -> {
                    // Short-option cluster. `-s`/`-k` take a value (inline
                    // remainder of the cluster, or the next argv word); `-f`/
                    // `-v` are flags.
                    var j = 1
                    var advance = 1
                    cluster@ while (j < a.length) {
                        when (val c = a[j]) {
                            'f' -> {
                                j++
                            }

                            'v' -> {
                                verbose = true
                                j++
                            }

                            's', 'k' -> {
                                val inline = a.substring(j + 1)
                                val value: String
                                if (inline.isNotEmpty()) {
                                    value = inline
                                } else {
                                    value =
                                        args.getOrNull(i + 1)
                                            ?: return usage(ctx, "option requires an argument -- '$c'")
                                    advance = 2
                                }
                                if (c == 's') {
                                    val sig = parseSignal(value) ?: return usage(ctx, "invalid signal '$value'")
                                    signalName = sig.first
                                    signalNum = sig.second
                                } else {
                                    parseDuration(value) ?: return usage(ctx, "invalid time interval '$value'")
                                }
                                break@cluster // value consumed the rest of the cluster
                            }

                            else -> {
                                return usage(ctx, "invalid option -- '$c'")
                            }
                        }
                    }
                    i += advance
                }

                else -> {
                    break@options
                } // first operand: DURATION
            }
        }

        // ---- Phase 2: operands. ----
        if (i >= args.size) return usage(ctx, "missing operand")
        val durationArg = args[i]
        i++
        val seconds =
            parseDuration(durationArg)
                ?: return usage(ctx, "invalid time interval '$durationArg'")

        if (i >= args.size) return usage(ctx, "missing operand after '$durationArg'")
        val utility = args[i]
        val utilArgs = args.subList(i + 1, args.size)

        val runner =
            ctx.utilityRunner ?: run {
                ctx.stderr.writeUtf8("timeout: not supported in this context\n")
                return CommandResult(exitCode = EXIT_TIMEOUT_FAILURE)
            }

        // DURATION of 0 means "no timeout" — run to completion (GNU semantics).
        val millis = (seconds * 1000.0).toLong()
        val rc: Int? =
            if (millis <= 0L) {
                runner.run(utility, utilArgs, ctx.stdin, ctx.stdout, ctx.stderr)
            } else {
                withTimeoutOrNull(millis) {
                    runner.run(utility, utilArgs, ctx.stdin, ctx.stdout, ctx.stderr)
                }
            }

        if (rc == null) {
            // Deadline fired: the child coroutine was cancelled and unwound.
            if (verbose) {
                ctx.stderr.writeUtf8("timeout: sending signal $signalName to command '$utility'\n")
            }
            val code = if (preserveStatus) 128 + signalNum else EXIT_TIMEOUT
            return CommandResult(exitCode = code)
        }
        return CommandResult(exitCode = rc)
    }

    private suspend fun usage(
        ctx: CommandContext,
        message: String,
    ): CommandResult {
        ctx.stderr.writeUtf8("timeout: $message\n")
        return CommandResult(exitCode = EXIT_TIMEOUT_FAILURE)
    }

    /**
     * GNU-style duration: a non-negative number with an optional `s`/`m`/`h`/`d`
     * suffix, returned in seconds. Mirrors `sleep`'s accepted forms (fractional
     * allowed; scientific notation and a leading `+` rejected). Returns null on
     * anything unparseable or negative.
     */
    private fun parseDuration(arg: String): Double? {
        if (arg.isEmpty()) return null
        val multiplier: Double
        val numberPart: String
        when (arg.last()) {
            's' -> {
                multiplier = 1.0
                numberPart = arg.dropLast(1)
            }

            'm' -> {
                multiplier = 60.0
                numberPart = arg.dropLast(1)
            }

            'h' -> {
                multiplier = 60.0 * 60.0
                numberPart = arg.dropLast(1)
            }

            'd' -> {
                multiplier = 60.0 * 60.0 * 24.0
                numberPart = arg.dropLast(1)
            }

            else -> {
                multiplier = 1.0
                numberPart = arg
            }
        }
        if (numberPart.isEmpty()) return null
        for (c in numberPart) {
            if (!(c.isDigit() || c == '.')) return null
        }
        if (numberPart.count { it == '.' } > 1) return null
        val value = numberPart.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite() || value < 0.0) return null
        return value * multiplier
    }

    /**
     * Resolve a signal spec (`TERM`, `SIGTERM`, `15`) to a `(canonicalName,
     * number)` pair. Used only to shape the `--preserve-status` exit code and
     * the `-v` message — kash delivers no real signal. Returns null if the
     * spec is neither a known name nor a positive integer.
     */
    private fun parseSignal(spec: String): Pair<String, Int>? {
        val s = spec.trim().uppercase().removePrefix("SIG")
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { num ->
            if (num <= 0) return null
            val name = SIGNALS.entries.firstOrNull { it.value == num }?.key ?: num.toString()
            return name to num
        }
        val num = SIGNALS[s] ?: return null
        return s to num
    }

    private companion object {
        const val EXIT_TIMEOUT = 124
        const val EXIT_TIMEOUT_FAILURE = 125
        const val SIG_TERM = 15

        val SIGNALS =
            mapOf(
                "HUP" to 1,
                "INT" to 2,
                "QUIT" to 3,
                "ABRT" to 6,
                "KILL" to 9,
                "USR1" to 10,
                "USR2" to 12,
                "PIPE" to 13,
                "ALRM" to 14,
                "TERM" to 15,
                "CONT" to 18,
                "STOP" to 19,
            )

        val USAGE =
            """
            Usage: timeout [OPTION] DURATION COMMAND [ARG]...
            Start COMMAND, and kill it if still running after DURATION.

            DURATION is a number with an optional suffix: s (seconds, default),
            m (minutes), h (hours), d (days). A DURATION of 0 disables the timeout.

              -s, --signal=SIG     signal name/number to report on timeout (default TERM)
              -k, --kill-after=DUR  accepted for compatibility (inert; cancel is the stop)
              -f, --foreground     accepted for compatibility (inert)
              -v, --verbose        diagnose to stderr when the timeout fires
                  --preserve-status exit with 128+signal on timeout instead of 124
                  --help           display this help and exit
                  --version        output version information and exit

            Exit 124 on timeout, 125 on timeout's own error, 126/127 if COMMAND
            could not be run, otherwise COMMAND's exit status.
            """.trimIndent() + "\n"
    }
}
