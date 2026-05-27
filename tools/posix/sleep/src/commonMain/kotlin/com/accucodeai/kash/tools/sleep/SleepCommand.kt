package com.accucodeai.kash.tools.sleep

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import kotlinx.coroutines.delay

/**
 * POSIX 🅄 `sleep` — pause for the specified amount of time.
 *
 * Coroutine-aware: uses [kotlinx.coroutines.delay] rather than
 * `Thread.sleep`, so the suspending delay yields to the dispatcher and
 * cooperates with structured cancellation. Under `runTest` virtual time the
 * delay is skipped, which keeps tests fast.
 *
 * Accepted operand forms:
 *  - `sleep 1`              integer seconds
 *  - `sleep 0.5`            fractional seconds
 *  - `sleep 2s`, `sleep 1m`, `sleep 1h`, `sleep 1d`
 *    GNU-style suffix on the last operand only. Mixing multiple operands
 *    (`sleep 1 2 3` → 6 seconds total, POSIX) is supported; suffixes are
 *    accepted on each operand independently.
 *
 * Behaviour:
 *  - With zero operands, exits 2 with a usage message on stderr.
 *  - Negative durations or unparseable operands exit 2 with a diagnostic.
 *  - Zero duration is a successful no-op (no `delay` call issued).
 */
public class SleepCommand :
    Command,
    CommandSpec {
    override val name: String = "sleep"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        if (args.isEmpty()) {
            ctx.stderr.writeUtf8("sleep: missing operand\n")
            return CommandResult(exitCode = 2)
        }

        var totalMillis = 0.0
        for (arg in args) {
            val seconds =
                parseDuration(arg) ?: run {
                    ctx.stderr.writeUtf8("sleep: invalid time interval '$arg'\n")
                    return CommandResult(exitCode = 2)
                }
            if (seconds < 0.0) {
                ctx.stderr.writeUtf8("sleep: invalid time interval '$arg'\n")
                return CommandResult(exitCode = 2)
            }
            totalMillis += seconds * 1000.0
        }

        val millis = totalMillis.toLong()
        if (millis > 0L) {
            delay(millis)
        }
        return CommandResult()
    }

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
        // Reject scientific notation and a leading '+' (POSIX `sleep` doesn't
        // accept them; `toDoubleOrNull` would happily eat both).
        for (c in numberPart) {
            if (!(c.isDigit() || c == '.' || c == '-')) return null
        }
        // Only one '.' allowed; '-' only as the leading character.
        if (numberPart.count { it == '.' } > 1) return null
        if (numberPart.indexOf('-') > 0) return null
        val value = numberPart.toDoubleOrNull() ?: return null
        if (value.isNaN() || value.isInfinite()) return null
        return value * multiplier
    }
}
