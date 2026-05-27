package com.accucodeai.kash.tools.uuidgen

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `uuidgen` — print a newly-generated UUID, one per line.
 *
 * v1 flags:
 * - `-r` / `--random` : random (v4) — the default.
 * - `-t` / `--time`   : time-based (v1).
 * - `-n COUNT`        : emit COUNT UUIDs (util-linux extension).
 */
public class UuidgenCommand :
    Command,
    CommandSpec {
    override val name: String = "uuidgen"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = emptySet()
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var mode = Mode.RANDOM
        var count = 1
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "-r" || a == "--random" -> {
                    mode = Mode.RANDOM
                }

                a == "-t" || a == "--time" -> {
                    mode = Mode.TIME
                }

                a == "-n" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("uuidgen: option requires an argument -- 'n'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val n = args[i + 1].toIntOrNull()
                    if (n == null || n < 0) {
                        ctx.stderr.writeUtf8("uuidgen: invalid count: '${args[i + 1]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    count = n
                    i++
                }

                a.startsWith("--count=") -> {
                    val v = a.removePrefix("--count=")
                    val n = v.toIntOrNull()
                    if (n == null || n < 0) {
                        ctx.stderr.writeUtf8("uuidgen: invalid count: '$v'\n")
                        return CommandResult(exitCode = 2)
                    }
                    count = n
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8(
                        "Usage: uuidgen [-r|--random] [-t|--time] [-n COUNT]\n",
                    )
                    return CommandResult()
                }

                a.startsWith("-") -> {
                    ctx.stderr.writeUtf8("uuidgen: unknown option: '$a'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    ctx.stderr.writeUtf8("uuidgen: extra operand '$a'\n")
                    return CommandResult(exitCode = 2)
                }
            }
            i++
        }
        val sb = StringBuilder()
        repeat(count) {
            val u =
                when (mode) {
                    Mode.RANDOM -> newRandomUuid()
                    Mode.TIME -> newTimeUuid()
                }
            sb.append(u).append('\n')
        }
        ctx.stdout.writeUtf8(sb.toString())
        return CommandResult()
    }

    private enum class Mode { RANDOM, TIME }
}
