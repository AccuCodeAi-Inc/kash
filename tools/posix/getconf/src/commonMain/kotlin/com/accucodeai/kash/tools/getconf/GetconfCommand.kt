package com.accucodeai.kash.tools.getconf

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX `getconf` — print system configuration variable values.
 *
 * Forms:
 * - `getconf NAME`           — print NAME's value (1 line).
 * - `getconf NAME PATH`      — print path-dependent NAME for PATH. kash
 *                              treats these as path-independent — same
 *                              value as plain `getconf NAME`.
 * - `getconf -a`             — list every supported variable.
 * - `getconf -v SPEC ...`    — select POSIX spec version. Accepted and
 *                              ignored (kash has one table).
 *
 * Unknown name → exit 1 with stderr message. Unset `PATH` falls back to a
 * sensible POSIX-ish default.
 */
public class GetconfCommand :
    Command,
    CommandSpec {
    override val name: String = "getconf"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private val defaultPath = "/usr/bin:/bin"

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var listAll = false
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--" -> {
                    i++
                    while (i < args.size) {
                        positional += args[i]
                        i++
                    }
                }

                a == "-h" || a == "--help" -> {
                    ctx.stdout.writeUtf8("Usage: getconf [-v SPEC] NAME [PATH]\n       getconf -a\n")
                    return CommandResult()
                }

                a == "-V" || a == "--version" -> {
                    ctx.stdout.writeUtf8("getconf (kash)\n")
                    return CommandResult()
                }

                a == "-a" || a == "--all" -> {
                    listAll = true
                    i++
                    continue
                }

                a == "-v" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("getconf: option requires an argument -- 'v'\n")
                        return CommandResult(exitCode = 2)
                    }
                    // Accepted, ignored.
                    i += 2
                    continue
                }

                a.startsWith("-v") && a.length > 2 -> {
                    // -vSPEC form, accepted and ignored.
                    i++
                    continue
                }

                a.startsWith("-") && a.length > 1 && a != "-" -> {
                    ctx.stderr.writeUtf8("getconf: unrecognized option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    positional += a
                    i++
                }
            }
            if (i >= args.size) break
        }

        if (listAll) {
            for ((k, v) in GetconfTable.TABLE) {
                val resolved = if (v == GetconfTable.PATH_SENTINEL) resolvePath(ctx) else v
                ctx.stdout.writeLine("$k: $resolved")
            }
            return CommandResult()
        }

        if (positional.isEmpty()) {
            ctx.stderr.writeUtf8("getconf: missing operand\n")
            return CommandResult(exitCode = 2)
        }
        if (positional.size > 2) {
            ctx.stderr.writeUtf8("getconf: too many operands\n")
            return CommandResult(exitCode = 2)
        }

        val name = positional[0]
        val raw =
            GetconfTable.TABLE[name] ?: run {
                ctx.stderr.writeUtf8("getconf: $name: unknown configuration variable\n")
                return CommandResult(exitCode = 1)
            }
        val value = if (raw == GetconfTable.PATH_SENTINEL) resolvePath(ctx) else raw
        ctx.stdout.writeLine(value)
        return CommandResult()
    }

    private fun resolvePath(ctx: CommandContext): String = ctx.process.env["PATH"] ?: defaultPath
}
