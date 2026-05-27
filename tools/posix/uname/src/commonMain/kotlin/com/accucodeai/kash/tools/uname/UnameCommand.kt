package com.accucodeai.kash.tools.uname

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `uname [-asnrvmpio]` — print system information.
 *
 * With no flags, prints the kernel name (`-s`). Individual flags select
 * fields; multiple flags print in the canonical order s n r v m p i o,
 * space-separated, on one line. `-a` selects all fields.
 *
 * Host facts come from [unameInfo] (an expect/actual platform split).
 */
public class UnameCommand :
    Command,
    CommandSpec {
    override val name: String = "uname"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    private enum class Field { S, N, R, V, M, P, I, O }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val selected = linkedSetOf<Field>()
        var all = false

        for (arg in args) {
            when {
                arg == "--help" -> {
                    ctx.stdout.writeUtf8(HELP)
                    return CommandResult()
                }

                arg == "--all" -> {
                    all = true
                }

                arg.startsWith("--") -> {
                    ctx.stderr.writeUtf8("uname: unrecognized option '$arg'\n")
                    return CommandResult(exitCode = 1)
                }

                arg.startsWith("-") && arg.length > 1 -> {
                    // Bundled short flags, e.g. `-sm` or `-a`.
                    for (c in arg.drop(1)) {
                        when (c) {
                            'a' -> {
                                all = true
                            }

                            's' -> {
                                selected += Field.S
                            }

                            'n' -> {
                                selected += Field.N
                            }

                            'r' -> {
                                selected += Field.R
                            }

                            'v' -> {
                                selected += Field.V
                            }

                            'm' -> {
                                selected += Field.M
                            }

                            'p' -> {
                                selected += Field.P
                            }

                            'i' -> {
                                selected += Field.I
                            }

                            'o' -> {
                                selected += Field.O
                            }

                            else -> {
                                ctx.stderr.writeUtf8("uname: invalid option -- '$c'\n")
                                return CommandResult(exitCode = 1)
                            }
                        }
                    }
                }

                else -> {
                    ctx.stderr.writeUtf8("uname: extra operand '$arg'\n")
                    return CommandResult(exitCode = 1)
                }
            }
        }

        val info = unameInfo()

        val parts =
            if (all) {
                buildAll(info)
            } else {
                val fields = if (selected.isEmpty()) listOf(Field.S) else canonicalOrder(selected)
                fields.map { fieldValue(info, it) }
            }

        ctx.stdout.writeUtf8(parts.joinToString(" ") + "\n")
        return CommandResult()
    }

    // `-a`: sysname nodename release version machine, then processor and
    // hardware-platform only when not "unknown", then operating-system last.
    // This mirrors GNU coreutils, which suppresses the two unknowable fields.
    private fun buildAll(info: UnameInfo): List<String> =
        buildList {
            add(info.sysname)
            add(info.nodename)
            add(info.release)
            add(info.version)
            add(info.machine)
            if (info.processor != "unknown") add(info.processor)
            if (info.hardwarePlatform != "unknown") add(info.hardwarePlatform)
            add(info.operatingSystem)
        }

    private fun canonicalOrder(selected: Set<Field>): List<Field> = Field.entries.filter { it in selected }

    private fun fieldValue(
        info: UnameInfo,
        field: Field,
    ): String =
        when (field) {
            Field.S -> info.sysname
            Field.N -> info.nodename
            Field.R -> info.release
            Field.V -> info.version
            Field.M -> info.machine
            Field.P -> info.processor
            Field.I -> info.hardwarePlatform
            Field.O -> info.operatingSystem
        }

    private companion object {
        const val HELP: String =
            "Usage: uname [OPTION]...\n" +
                "Print system information.\n\n" +
                "  -a, --all     print all information\n" +
                "  -s            kernel name (default)\n" +
                "  -n            network node hostname\n" +
                "  -r            kernel release\n" +
                "  -v            kernel version\n" +
                "  -m            machine hardware name\n" +
                "  -p            processor type\n" +
                "  -i            hardware platform\n" +
                "  -o            operating system\n" +
                "      --help    display this help and exit\n"
    }
}
