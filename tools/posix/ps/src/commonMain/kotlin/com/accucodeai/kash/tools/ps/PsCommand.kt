package com.accucodeai.kash.tools.ps

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8

/**
 * POSIX 🅄 `ps` — report process status. Reads
 * [com.accucodeai.kash.api.KashMachine.processTable], one row per live
 * process.
 *
 * Default columns match POSIX / bash's `ps`: `PID TTY TIME CMD`. No
 * `-e`/`-a`/`-x` flag handling yet; default shows every registered
 * process (kash has no notion of "current tty session" to filter by).
 */
public class PsCommand :
    Command,
    CommandSpec {
    override val name: String = "ps"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val processes =
            ctx.process.machine.processTable
                .values
                .sortedBy { it.pid }
        val rows = mutableListOf<List<String>>()
        rows += listOf("PID", "TTY", "TIME", "CMD")
        for (p in processes) {
            val cmd =
                when {
                    p.argv.isNotEmpty() -> p.argv.joinToString(" ")
                    p.commandName.isNotEmpty() -> p.commandName
                    else -> "[unknown]"
                }
            val tty = if (p.controllingTty != null) "tty" else "?"
            val time = formatCpuTime(p.userCpuMicros + p.sysCpuMicros)
            rows += listOf(p.pid.toString(), tty, time, cmd)
        }
        val widths = IntArray(4)
        for (row in rows) {
            for (i in 0..3) widths[i] = maxOf(widths[i], row[i].length)
        }
        val sb = StringBuilder()
        for (row in rows) {
            sb.append(row[0].padStart(widths[0]))
            sb.append(' ')
            sb.append(row[1].padEnd(widths[1]))
            sb.append(' ')
            sb.append(row[2].padStart(widths[2]))
            sb.append(' ')
            sb.append(row[3])
            sb.append('\n')
        }
        ctx.stdout.writeUtf8(sb.toString())
        return CommandResult()
    }

    private fun formatCpuTime(micros: Long): String {
        val totalSeconds = micros / 1_000_000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return buildString {
            if (hours < 10) append('0')
            append(hours)
            append(':')
            if (minutes < 10) append('0')
            append(minutes)
            append(':')
            if (seconds < 10) append('0')
            append(seconds)
        }
    }
}
