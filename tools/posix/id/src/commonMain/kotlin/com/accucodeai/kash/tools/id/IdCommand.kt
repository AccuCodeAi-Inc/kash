package com.accucodeai.kash.tools.id

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.user.UserEntry

/**
 * POSIX 🅄 `id` — print real and effective user and group IDs.
 *
 * kash is a single-user simulator: real and effective IDs are the same, so
 * `-r` is accepted as a no-op modifier (POSIX permits this; `-r` is only
 * meaningful in combination with `-u`/`-g`/`-G`).
 *
 * Options:
 *  - `-u`     numeric uid only
 *  - `-g`     numeric primary gid only
 *  - `-G`     all group IDs, space-separated
 *  - `-n`     names instead of numbers (requires one of `-u`/`-g`/`-G`)
 *  - `-r`     real instead of effective (no-op here — same value)
 *  - `--`     end of options
 *
 * Default (no `-u`/`-g`/`-G`): `uid=N(name) gid=N(name) groups=...`.
 */
public class IdCommand :
    Command,
    CommandSpec {
    override val name: String = "id"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    private data class Opts(
        val user: Boolean = false,
        val group: Boolean = false,
        val allGroups: Boolean = false,
        val nameForm: Boolean = false,
        val real: Boolean = false,
        val operand: String? = null,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var opts = Opts()
        var i = 0
        var endOfOpts = false
        while (i < args.size) {
            val a = args[i]
            if (!endOfOpts && a == "--") {
                endOfOpts = true
                i++
                continue
            }
            if (!endOfOpts && a.length > 1 && a.startsWith("-")) {
                for (c in a.drop(1)) {
                    when (c) {
                        'u' -> {
                            opts = opts.copy(user = true)
                        }

                        'g' -> {
                            opts = opts.copy(group = true)
                        }

                        'G' -> {
                            opts = opts.copy(allGroups = true)
                        }

                        'n' -> {
                            opts = opts.copy(nameForm = true)
                        }

                        'r' -> {
                            opts = opts.copy(real = true)
                        }

                        else -> {
                            ctx.stderr.writeUtf8("id: invalid option -- '$c'\n")
                            return CommandResult(exitCode = 1)
                        }
                    }
                }
                i++
                continue
            }
            // First non-option arg is the user operand; further args are an error.
            if (opts.operand != null) {
                ctx.stderr.writeUtf8("id: extra operand '$a'\n")
                return CommandResult(exitCode = 1)
            }
            opts = opts.copy(operand = a)
            i++
        }

        val modeCount = listOf(opts.user, opts.group, opts.allGroups).count { it }
        if (modeCount > 1) {
            ctx.stderr.writeUtf8("id: cannot print more than one of -u, -g, -G\n")
            return CommandResult(exitCode = 1)
        }
        if (opts.nameForm && modeCount == 0) {
            ctx.stderr.writeUtf8("id: cannot print only names or real IDs in default format\n")
            return CommandResult(exitCode = 1)
        }

        val entry: UserEntry =
            if (opts.operand != null) {
                val name = opts.operand
                val byName = ctx.userDb.lookup(name)
                val byUid = name.toIntOrNull()?.let { ctx.userDb.lookupUid(it) }
                byName ?: byUid ?: run {
                    ctx.stderr.writeUtf8("id: '$name': no such user\n")
                    return CommandResult(exitCode = 1)
                }
            } else {
                ctx.userDb.current()
            }

        val out =
            when {
                opts.user && opts.nameForm -> "${entry.name}\n"
                opts.user -> "${entry.uid}\n"
                opts.group && opts.nameForm -> "${primaryGroupName(entry)}\n"
                opts.group -> "${entry.gid}\n"
                opts.allGroups && opts.nameForm -> entry.groups.joinToString(" ") { it.second } + "\n"
                opts.allGroups -> entry.groups.joinToString(" ") { it.first.toString() } + "\n"
                else -> defaultFormat(entry)
            }
        ctx.stdout.writeUtf8(out)
        return CommandResult()
    }

    private fun primaryGroupName(e: UserEntry): String = e.groups.firstOrNull()?.second ?: e.name

    private fun defaultFormat(e: UserEntry): String {
        val groups = e.groups.joinToString(",") { "${it.first}(${it.second})" }
        return "uid=${e.uid}(${e.name}) gid=${e.gid}(${primaryGroupName(e)}) groups=$groups\n"
    }
}
