package com.accucodeai.kash.tools.yes

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.BrokenPipeException
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `yes` — repeatedly write a line until the writer's stdout is closed.
 *
 * - `yes`         → emits `y\n` forever
 * - `yes WORD…`   → emits `WORD1 WORD2 …\n` forever
 *
 * Every argument is a literal string to echo; there are no flags. BSD `yes`
 * and the POSIX behavior real shells expect treat `yes --help` as "repeat
 * `--help` forever", and so do we.
 *
 * Termination: in a pipeline like `yes | head -5`, the downstream reader
 * closes its source after consuming what it needs; the next write here
 * throws [BrokenPipeException], which we treat as a normal end-of-pipe
 * (exit 0) — matching what users expect from `yes | head` working at all.
 *
 * To avoid one channel-send per line, we pre-build an ~8 KiB block of
 * repeated lines and write that block in a loop. The downstream reader
 * still sees individual `\n`-terminated lines.
 */
public class YesCommand :
    Command,
    CommandSpec {
    override val name: String = "yes"
    override val kind: CommandKind = CommandKind.TOOL
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val line = (if (args.isEmpty()) "y" else args.joinToString(" ")) + "\n"
        val block = buildBlock(line)
        return try {
            while (true) {
                ctx.stdout.writeUtf8(block)
            }
            @Suppress("UNREACHABLE_CODE")
            CommandResult()
        } catch (_: BrokenPipeException) {
            CommandResult()
        }
    }

    private fun buildBlock(line: String): String {
        if (line.length >= BLOCK_TARGET) return line
        val reps = BLOCK_TARGET / line.length
        val sb = StringBuilder(reps * line.length)
        repeat(reps) { sb.append(line) }
        return sb.toString()
    }

    private companion object {
        const val BLOCK_TARGET: Int = 8 * 1024
    }
}
