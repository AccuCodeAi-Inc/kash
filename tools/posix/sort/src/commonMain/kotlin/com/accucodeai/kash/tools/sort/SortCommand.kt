package com.accucodeai.kash.tools.sort

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8LineOrNull
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound

/**
 * POSIX `sort` — see [TOOLS.md](../../../../../../../../../docs/TOOLS.md) entry.
 *
 * Supported flags: `-n`, `-r`, `-u`, `-k POS[,POS]`, `-t SEP`. Bundled short
 * flags (`-nru`) and `--` are accepted. Multiple `-k` keys are honored in
 * declaration order.
 *
 * I/O contract:
 *   - If file args are present, lines are read from each in order (use `-` for stdin).
 *   - Otherwise the entire stdin is consumed.
 *   - All lines are buffered in memory, sorted, then written to stdout.
 *
 * Limitation: in-memory only; no external merge-sort. Inputs larger than heap
 * will OOM. See [STATUS.md](STATUS.md).
 */
public class SortCommand :
    Command,
    CommandSpec {
    override val name: String = "sort"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val (options, files) =
            try {
                SortOptionParser.parse(args)
            } catch (e: SortOptionError) {
                ctx.stderr.writeUtf8("${e.message}\n")
                return CommandResult(exitCode = 2)
            }

        val lines = ArrayList<String>()
        if (files.isEmpty()) {
            readAllLines(ctx.stdin, lines)
        } else {
            for (arg in files) {
                if (arg == "-") {
                    readAllLines(ctx.stdin, lines)
                    continue
                }
                val path = resolvePath(ctx.process.cwd, arg)
                val src =
                    try {
                        ctx.process.fs.source(path)
                    } catch (_: FileNotFound) {
                        ctx.stderr.writeUtf8("sort: cannot read: $arg: No such file or directory\n")
                        return CommandResult(exitCode = 2)
                    }
                try {
                    readAllLines(src, lines)
                } finally {
                    src.close()
                }
            }
        }

        val sorted = SortEngine.sort(lines, options)
        for (line in sorted) {
            ctx.stdout.writeLine(line)
        }
        return CommandResult()
    }

    /** Minimal POSIX-style path resolver; mirrors core's internal `Paths.resolve`. */
    private fun resolvePath(
        cwd: String,
        path: String,
    ): String {
        val joined = if (path.startsWith("/")) path else "$cwd/$path"
        val parts = mutableListOf<String>()
        for (segment in joined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        return "/" + parts.joinToString("/")
    }

    private suspend fun readAllLines(
        src: SuspendSource,
        into: MutableList<String>,
    ) {
        while (true) {
            val line = src.readUtf8LineOrNull() ?: break
            into += line
        }
    }
}
