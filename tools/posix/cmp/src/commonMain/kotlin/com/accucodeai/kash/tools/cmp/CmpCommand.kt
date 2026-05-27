package com.accucodeai.kash.tools.cmp

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * POSIX `cmp FILE1 FILE2` — byte-by-byte file comparison.
 *
 * Exit codes:
 *   0 — files are identical (nothing written).
 *   1 — files differ (write `FILE1 FILE2 differ: char N, line M` to stdout).
 *   2 — usage / I/O error (diagnostic to stderr).
 *
 * Flags:
 *   -s / --silent / --quiet  suppress the differ message; only the exit code
 *                            communicates the result.
 *   -l                       print every differing byte instead of stopping
 *                            at the first.
 *
 * A `-` operand reads from stdin. Either operand can be `-`, but not both.
 */
public class CmpCommand :
    Command,
    CommandSpec {
    override val name: String = "cmp"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var silent = false
        var verbose = false
        val operands = mutableListOf<String>()
        var optionsEnded = false
        for (a in args) {
            when {
                optionsEnded || a == "-" || !a.startsWith("-") || a.length == 1 -> {
                    operands += a
                }

                a == "--" -> {
                    optionsEnded = true
                }

                a == "-s" || a == "--silent" || a == "--quiet" -> {
                    silent = true
                }

                a == "-l" || a == "--verbose" -> {
                    verbose = true
                }

                else -> {
                    ctx.stderr.writeUtf8("cmp: invalid option -- '${a.drop(1)}'\n")
                    return CommandResult(exitCode = 2)
                }
            }
        }
        if (operands.size < 2) {
            ctx.stderr.writeUtf8("cmp: missing operand\n")
            return CommandResult(exitCode = 2)
        }
        if (operands.size > 4) {
            ctx.stderr.writeUtf8("cmp: too many operands\n")
            return CommandResult(exitCode = 2)
        }

        val name1 = operands[0]
        val name2 = operands[1]
        val srcA: SuspendSource
        val srcB: SuspendSource
        try {
            srcA = openOperand(operands[0], ctx)
            srcB = openOperand(operands[1], ctx)
        } catch (e: ReadFailure) {
            ctx.stderr.writeUtf8("cmp: ${e.message}\n")
            return CommandResult(exitCode = 2)
        }
        try {
            // Streaming compare. Pull chunks from both sources in parallel,
            // walk the overlap byte-by-byte, refill whichever side ran out.
            // No buffering past the current chunk — `cmp /dev/urandom /dev/zero`
            // returns on the first mismatch instead of OOMing.
            val bufA = Buffer()
            val bufB = Buffer()
            var pos = 0L
            var line = 1
            var firstDiffByte = -1L
            var firstDiffLine = -1
            var eofA = false
            var eofB = false

            while (true) {
                if (bufA.size == 0L && !eofA) {
                    val n = srcA.readAtMostTo(bufA, READ_CHUNK)
                    if (n == -1L) eofA = true
                }
                if (bufB.size == 0L && !eofB) {
                    val n = srcB.readAtMostTo(bufB, READ_CHUNK)
                    if (n == -1L) eofB = true
                }
                if (bufA.size == 0L || bufB.size == 0L) break

                val len = minOf(bufA.size, bufB.size).toInt()
                val chunkA = bufA.readByteArray(len)
                val chunkB = bufB.readByteArray(len)
                for (i in 0 until len) {
                    val bA = chunkA[i]
                    val bB = chunkB[i]
                    if (bA != bB) {
                        if (firstDiffByte < 0) {
                            firstDiffByte = pos + i.toLong()
                            firstDiffLine = line
                        }
                        if (verbose) {
                            val byteA = bA.toInt() and 0xFF
                            val byteB = bB.toInt() and 0xFF
                            ctx.stdout.writeUtf8(
                                "${pos + i + 1} ${byteA.toString(8).padStart(3, '0')} " +
                                    "${byteB.toString(8).padStart(3, '0')}\n",
                            )
                        } else {
                            // Stop on first mismatch; drop remaining input.
                            if (!silent) {
                                ctx.stdout.writeUtf8(
                                    "$name1 $name2 differ: char ${pos + i + 1}, line $firstDiffLine\n",
                                )
                            }
                            return CommandResult(exitCode = 1)
                        }
                    }
                    if (bA == '\n'.code.toByte()) line++
                }
                pos += len
            }

            if (firstDiffByte < 0 && eofA && eofB && bufA.size == 0L && bufB.size == 0L) {
                return CommandResult(exitCode = 0)
            }
            if (firstDiffByte < 0) {
                // One side ran out first → it's a prefix of the other.
                val shorter = if (eofA && bufA.size == 0L) name1 else name2
                if (!silent) {
                    ctx.stderr.writeUtf8("cmp: EOF on $shorter\n")
                }
                return CommandResult(exitCode = 1)
            }
            if (!silent && !verbose) {
                ctx.stdout.writeUtf8(
                    "$name1 $name2 differ: char ${firstDiffByte + 1}, line $firstDiffLine\n",
                )
            }
            return CommandResult(exitCode = 1)
        } finally {
            try {
                srcA.close()
            } catch (_: Throwable) {
            }
            try {
                srcB.close()
            } catch (_: Throwable) {
            }
        }
    }

    private class ReadFailure(
        msg: String,
    ) : RuntimeException(msg)

    private fun openOperand(
        name: String,
        ctx: CommandContext,
    ): SuspendSource =
        if (name == "-") {
            ctx.stdin
        } else {
            val path = Paths.resolve(ctx.process.cwd, name)
            try {
                ctx.process.fs.source(path)
            } catch (_: FileNotFound) {
                throw ReadFailure("$name: No such file or directory")
            }
        }

    private companion object {
        const val READ_CHUNK: Long = 8 * 1024L
    }
}
