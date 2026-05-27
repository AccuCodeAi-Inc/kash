package com.accucodeai.kash.tools.nice

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `nice` — invoke a utility with an altered nice value, or print the
 * caller's nice value.
 *
 * Forms:
 *  - `nice`                              → print the current nice value
 *    (always `0` in kash; we don't model process priority).
 *  - `nice [-n N] UTILITY [ARG]...`      → run UTILITY with adjusted niceness
 *    (the increment is parsed and accepted, but has no effect — kash has no
 *    scheduler hooks).
 *  - `nice [-N] UTILITY [ARG]...`        → legacy short form (e.g. `-10`).
 *
 * Default increment when an option is given but no value: 10.
 *
 * Exit codes:
 *  - 0 (no utility, just printing).
 *  - 126 if the utility is found but not invokable as a utility.
 *  - 127 if the utility cannot be found.
 *  - Otherwise the utility's own exit code.
 */
public class NiceCommand :
    Command,
    CommandSpec {
    override val name: String = "nice"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        @Suppress("UNUSED_VARIABLE")
        var increment = 10 // parsed but not enforced; kept for clarity.

        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    break
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8("Usage: nice [-n N] [UTILITY [ARG]...]\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("nice (kash)\n")
                    return CommandResult(exitCode = 0)
                }

                a == "-n" || a == "--adjustment" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("nice: option requires an argument -- 'n'\n")
                        return CommandResult(exitCode = 125)
                    }
                    val n =
                        args[i + 1].toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8("nice: invalid adjustment '${args[i + 1]}'\n")
                            return CommandResult(exitCode = 125)
                        }
                    increment = n
                    i += 2
                }

                a.startsWith("--adjustment=") -> {
                    val v = a.substring("--adjustment=".length)
                    val n =
                        v.toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8("nice: invalid adjustment '$v'\n")
                            return CommandResult(exitCode = 125)
                        }
                    increment = n
                    i++
                }

                // Legacy: -N or -+N (where N is digits). Distinguish from -h etc.
                a.startsWith("-") && a.length > 1 && isLegacyAdjust(a) -> {
                    val n =
                        a.substring(1).toIntOrNull() ?: run {
                            ctx.stderr.writeUtf8("nice: invalid adjustment '$a'\n")
                            return CommandResult(exitCode = 125)
                        }
                    increment = n
                    i++
                }

                else -> {
                    break
                }
            }
        }

        if (i >= args.size) {
            // No utility — print current niceness. POSIX: must be the
            // *system* nice value; we always report 0.
            ctx.stdout.writeUtf8("0\n")
            return CommandResult(exitCode = 0)
        }

        val utility = args[i]
        val utilArgs = args.subList(i + 1, args.size)

        val runner =
            ctx.utilityRunner ?: run {
                ctx.stderr.writeUtf8("nice: not supported in this context\n")
                return CommandResult(exitCode = 127)
            }
        val rc =
            runner.run(
                name = utility,
                args = utilArgs,
                stdin = ctx.stdin,
                stdout = ctx.stdout,
                stderr = ctx.stderr,
            )
        return CommandResult(exitCode = rc)
    }

    /** True iff [a] is a legacy `-N`/`-+N` adjustment (digits after the `-`). */
    private fun isLegacyAdjust(a: String): Boolean {
        if (a.length < 2) return false
        val rest = a.substring(1)
        val withoutSign = if (rest.startsWith("+") || rest.startsWith("-")) rest.substring(1) else rest
        if (withoutSign.isEmpty()) return false
        return withoutSign.all { it.isDigit() }
    }
}
