package com.accucodeai.kash.tools.sum

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
 * `sum` — POSIX 16-bit checksum + 512-byte block count.
 *
 * Algorithms:
 *   - BSD (`-r`, default): rotate accumulator right one bit, then add the
 *     byte; mask to 16 bits.
 *   - SysV (`-s`, `--sysv`): straight unsigned sum of bytes; fold twice as
 *     `s = (s & 0xFFFF) + (s >> 16)` to land in 16 bits.
 *
 * Block count is `ceil(bytes / 512)`. Output:
 *   `<CHECKSUM> <BLOCKS>`          (single stdin/file, no name)
 *   `<CHECKSUM> <BLOCKS> <FILE>`   (named file, or multi-file mode)
 */
public class SumCommand :
    Command,
    CommandSpec {
    override val name: String = "sum"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private enum class Alg { BSD, SYSV }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var alg = Alg.BSD
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

                a == "-r" -> {
                    alg = Alg.BSD
                }

                a == "-s" || a == "--sysv" -> {
                    alg = Alg.SYSV
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Allow bundled like `-rs` — last one wins.
                    var ok = true
                    for (c in a.substring(1)) {
                        when (c) {
                            'r' -> {
                                alg = Alg.BSD
                            }

                            's' -> {
                                alg = Alg.SYSV
                            }

                            else -> {
                                ctx.stderr.writeUtf8("sum: invalid option -- '$c'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                    }
                    if (!ok) return CommandResult(exitCode = 2)
                }

                else -> {
                    files += a
                }
            }
            i++
        }

        var exit = 0
        if (files.isEmpty()) {
            val (sum, blocks) = computeFromSource(ctx.stdin, alg)
            ctx.stdout.writeLine(format(sum, blocks, alg, label = null))
            return CommandResult()
        }
        val multi = files.size > 1
        for (arg in files) {
            if (arg == "-") {
                val (sum, blocks) = computeFromSource(ctx.stdin, alg)
                ctx.stdout.writeLine(format(sum, blocks, alg, label = if (multi) "-" else null))
            } else {
                val abs = Paths.resolve(ctx.process.cwd, arg)
                try {
                    val src = ctx.process.fs.source(abs)
                    val (sum, blocks) =
                        try {
                            computeFromSource(src, alg)
                        } finally {
                            src.close()
                        }
                    ctx.stdout.writeLine(format(sum, blocks, alg, label = arg))
                } catch (_: FileNotFound) {
                    ctx.stderr.writeUtf8("sum: $arg: No such file or directory\n")
                    exit = 1
                }
            }
        }
        return CommandResult(exitCode = exit)
    }

    private suspend fun computeFromSource(
        src: SuspendSource,
        alg: Alg,
    ): Pair<Int, Long> {
        val bytes = src.readAllBytes()
        val blocks = (bytes.size + 511) / 512
        return when (alg) {
            Alg.BSD -> bsd(bytes) to blocks.toLong()
            Alg.SYSV -> sysv(bytes) to blocks.toLong()
        }
    }

    /** Format the result row. BSD uses 5-digit zero-padded checksum; SysV is unpadded. */
    private fun format(
        sum: Int,
        blocks: Long,
        alg: Alg,
        label: String?,
    ): String {
        val csum =
            when (alg) {
                Alg.BSD -> sum.toString().padStart(5, '0')
                Alg.SYSV -> sum.toString()
            }
        return if (label != null) "$csum $blocks $label" else "$csum $blocks"
    }

    public companion object {
        public fun bsd(bytes: ByteArray): Int {
            var acc = 0
            for (b in bytes) {
                // Rotate right 1 bit in 16-bit space.
                acc = ((acc ushr 1) or ((acc and 1) shl 15)) and 0xFFFF
                acc = (acc + (b.toInt() and 0xFF)) and 0xFFFF
            }
            return acc
        }

        public fun sysv(bytes: ByteArray): Int {
            var s = 0L
            for (b in bytes) s += (b.toInt() and 0xFF).toLong()
            var r = (s and 0xFFFFL) + (s ushr 16)
            r = (r and 0xFFFFL) + (r ushr 16)
            return r.toInt() and 0xFFFF
        }
    }
}
