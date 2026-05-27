package com.accucodeai.kash.tools.shuf

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSink
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.FileNotFound
import com.accucodeai.kash.fs.Paths
import kotlin.random.Random

/**
 * `shuf` — generate random permutations.
 *
 * Modes (mutually exclusive):
 *   - default: read newline-separated items from FILE (or stdin if absent).
 *   - `-e` / `--echo`: each positional argument is an input item.
 *   - `-i LO-HI` / `--input-range=LO-HI`: integers in `[LO, HI]` (inclusive).
 *
 * Options:
 *   - `-n N` / `--head-count=N`: emit at most N items.
 *   - `-r`     / `--repeat`     : sample with replacement (N picks may repeat).
 *   - `-z`     / `--zero-terminated`: NUL line terminator on input and output.
 *   - `-o FILE`/ `--output=FILE`: write to FILE instead of stdout.
 *
 * `--random-source=FILE` is parsed and ignored — v1 always uses Kotlin's
 * `Random.Default`. A reproducible seed env hook may come later.
 */
public class ShufCommand :
    Command,
    CommandSpec {
    override val name: String = "shuf"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private enum class Mode { FILE, ECHO, RANGE }

    private class Opts {
        var mode: Mode = Mode.FILE
        var head: Long = -1L // -1 ⇒ unlimited
        var repeat: Boolean = false
        var zero: Boolean = false
        var output: String? = null
        var rangeLo: Long = 0
        var rangeHi: Long = -1
        var operands: MutableList<String> = mutableListOf()
    }

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = Opts()
        var endOfOpts = false
        var sawEcho = false
        var sawRange = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                opts.operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-e" || a == "--echo" -> {
                    sawEcho = true
                    opts.mode = Mode.ECHO
                }

                a.startsWith("--echo=") -> {
                    sawEcho = true
                    opts.mode = Mode.ECHO
                    opts.operands += a.substring("--echo=".length)
                }

                a == "-i" -> {
                    sawRange = true
                    opts.mode = Mode.RANGE
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("shuf: option requires an argument -- 'i'\n")
                        return CommandResult(exitCode = 2)
                    }
                    if (!parseRange(args[i + 1], opts, ctx)) return CommandResult(exitCode = 2)
                    i++
                }

                a.startsWith("--input-range=") -> {
                    sawRange = true
                    opts.mode = Mode.RANGE
                    if (!parseRange(a.substring("--input-range=".length), opts, ctx)) {
                        return CommandResult(exitCode = 2)
                    }
                }

                a == "-n" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("shuf: option requires an argument -- 'n'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val v = args[i + 1].toLongOrNull()
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("shuf: invalid count: '${args[i + 1]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.head = v
                    i++
                }

                a.startsWith("--head-count=") -> {
                    val v = a.substring("--head-count=".length).toLongOrNull()
                    if (v == null || v < 0) {
                        ctx.stderr.writeUtf8("shuf: invalid count\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.head = v
                }

                a == "-r" || a == "--repeat" -> {
                    opts.repeat = true
                }

                a == "-z" || a == "--zero-terminated" -> {
                    opts.zero = true
                }

                a == "-o" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("shuf: option requires an argument -- 'o'\n")
                        return CommandResult(exitCode = 2)
                    }
                    opts.output = args[i + 1]
                    i++
                }

                a.startsWith("--output=") -> {
                    opts.output = a.substring("--output=".length)
                }

                a.startsWith("--random-source=") -> { /* accepted, ignored */ }

                a == "--random-source" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("shuf: option requires an argument\n")
                        return CommandResult(exitCode = 2)
                    }
                    i++ // ignore value
                }

                a == "-" -> {
                    opts.operands += a
                }

                a.startsWith("-") && a.length > 1 -> {
                    ctx.stderr.writeUtf8("shuf: invalid option -- '${a.drop(1)}'\n")
                    return CommandResult(exitCode = 2)
                }

                else -> {
                    opts.operands += a
                }
            }
            i++
        }

        if (sawEcho && sawRange) {
            ctx.stderr.writeUtf8("shuf: cannot combine -e and -i\n")
            return CommandResult(exitCode = 2)
        }

        // Gather the input multiset.
        val items: List<String> =
            when (opts.mode) {
                Mode.ECHO -> {
                    opts.operands
                }

                Mode.RANGE -> {
                    if (opts.operands.isNotEmpty()) {
                        ctx.stderr.writeUtf8("shuf: extra operand '${opts.operands[0]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val n = opts.rangeHi - opts.rangeLo + 1
                    if (n <= 0) {
                        emptyList()
                    } else {
                        (opts.rangeLo..opts.rangeHi).map { it.toString() }
                    }
                }

                Mode.FILE -> {
                    val path = opts.operands.firstOrNull()
                    if (opts.operands.size > 1) {
                        ctx.stderr.writeUtf8("shuf: extra operand '${opts.operands[1]}'\n")
                        return CommandResult(exitCode = 2)
                    }
                    val bytes =
                        if (path == null || path == "-") {
                            ctx.stdin.readAllBytes()
                        } else {
                            val abs = Paths.resolve(ctx.process.cwd, path)
                            try {
                                ctx.process.fs
                                    .source(abs)
                                    .readAllBytes()
                            } catch (_: FileNotFound) {
                                ctx.stderr.writeUtf8("shuf: $path: No such file or directory\n")
                                return CommandResult(exitCode = 1)
                            }
                        }
                    splitItems(bytes, opts.zero)
                }
            }

        // Pick items.
        val picked: List<String> =
            if (opts.repeat) {
                if (opts.head < 0) {
                    ctx.stderr.writeUtf8("shuf: -r requires -n in kash (would otherwise loop forever)\n")
                    return CommandResult(exitCode = 2)
                }
                if (items.isEmpty()) {
                    if (opts.head == 0L) {
                        emptyList()
                    } else {
                        ctx.stderr.writeUtf8("shuf: no lines to repeat\n")
                        return CommandResult(exitCode = 1)
                    }
                } else {
                    List(opts.head.toInt()) { items[Random.Default.nextInt(items.size)] }
                }
            } else {
                val shuffled = items.toMutableList()
                // Fisher-Yates in place.
                for (k in shuffled.indices.reversed()) {
                    val j = Random.Default.nextInt(k + 1)
                    val tmp = shuffled[k]
                    shuffled[k] = shuffled[j]
                    shuffled[j] = tmp
                }
                if (opts.head < 0) shuffled else shuffled.take(opts.head.toInt())
            }

        // Emit.
        val term = if (opts.zero) " " else "\n"
        val outBytes =
            buildString {
                for (line in picked) {
                    append(line)
                    append(term)
                }
            }.encodeToByteArray()

        val outPath = opts.output
        if (outPath == null) {
            ctx.stdout.writeBytesAll(outBytes)
        } else {
            val abs = Paths.resolve(ctx.process.cwd, outPath)
            val sink = ctx.process.fs.sink(abs, append = false)
            try {
                sink.writeBytes(outBytes)
            } finally {
                sink.close()
            }
        }
        return CommandResult()
    }

    private suspend fun SuspendSink.writeBytesAll(b: ByteArray) {
        if (b.isNotEmpty()) this.writeBytes(b)
    }

    private suspend fun parseRange(
        spec: String,
        opts: Opts,
        ctx: CommandContext,
    ): Boolean {
        // Hi may contain '-'? No — POSIX-ish LO-HI integers only.
        val dash = spec.indexOf('-')
        if (dash <= 0 || dash == spec.length - 1) {
            // Could still parse if no dash but starts with negative? Skip neg.
            // Allow LO-HI only.
            return invalidRange(spec, ctx)
        }
        val lo = spec.substring(0, dash).toLongOrNull()
        val hi = spec.substring(dash + 1).toLongOrNull()
        if (lo == null || hi == null) return invalidRange(spec, ctx)
        if (hi < lo) return invalidRange(spec, ctx)
        opts.rangeLo = lo
        opts.rangeHi = hi
        return true
    }

    private suspend fun invalidRange(
        spec: String,
        ctx: CommandContext,
    ): Boolean {
        ctx.stderr.writeUtf8("shuf: invalid input range: '$spec'\n")
        return false
    }

    private fun splitItems(
        bytes: ByteArray,
        zero: Boolean,
    ): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val delim: Byte = if (zero) 0x00 else 0x0A
        val out = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == delim) {
                out += bytes.decodeToString(start, i)
                start = i + 1
            }
            i++
        }
        // Trailing partial item — POSIX shuf treats trailing non-newline as an
        // item too (matching coreutils behavior).
        if (start < bytes.size) {
            out += bytes.decodeToString(start, bytes.size)
        }
        return out
    }
}
