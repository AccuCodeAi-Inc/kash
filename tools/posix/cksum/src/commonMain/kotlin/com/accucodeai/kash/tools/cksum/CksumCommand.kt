package com.accucodeai.kash.tools.cksum

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeLine
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths

/**
 * `cksum` — POSIX CRC-32 + byte count.
 *
 * Output: `<CRC> <SIZE> <FILENAME>` for each FILE; if no FILE (or `-`),
 * read stdin and omit the filename column (just `<CRC> <SIZE>`).
 *
 * The CRC algorithm lives in [Crc32Cksum]. No flags beyond `--` and `-`.
 */
public class CksumCommand :
    Command,
    CommandSpec {
    override val name: String = "cksum"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val files = mutableListOf<String>()
        var endOfOpts = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                endOfOpts -> {
                    files += a
                }

                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    files += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("cksum: invalid option: $a\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        var exit = 0
        if (files.isEmpty()) {
            val (crc, size) = computeFromSource(ctx.stdin)
            ctx.stdout.writeLine("$crc $size")
            return CommandResult()
        }
        for (arg in files) {
            if (arg == "-") {
                val (crc, size) = computeFromSource(ctx.stdin)
                // POSIX does not require a filename when reading from stdin;
                // emit `-` for clarity.
                ctx.stdout.writeLine("$crc $size -")
            } else {
                val abs = Paths.resolve(ctx.process.cwd, arg)
                try {
                    val src = ctx.process.fs.source(abs)
                    val (crc, size) =
                        try {
                            computeFromSource(src)
                        } finally {
                            src.close()
                        }
                    ctx.stdout.writeLine("$crc $size $arg")
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("cksum: $arg: No such file or directory\n")
                    exit = 1
                }
            }
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun computeFromSource(src: SuspendSource): Pair<Long, Long> {
        val bytes = src.readAllBytes()
        return Crc32Cksum.of(bytes)
    }
}
