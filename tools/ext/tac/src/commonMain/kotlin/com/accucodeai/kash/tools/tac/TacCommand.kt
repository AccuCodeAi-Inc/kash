package com.accucodeai.kash.tools.tac

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.io.readAllBytes
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `tac` — concatenate input(s) and print the records in reverse order.
 *
 * Records default to `\n`-terminated lines. With `-s STR` the separator is
 * the literal STR; with `-r -s REGEX` it is a regex. The `-b` flag attaches
 * the separator to the *beginning* of each record rather than the default
 * end.
 *
 * Multiple operands are concatenated into a single stream which is then
 * reversed — this matches GNU tac, not "reverse each file independently".
 * An operand of `-` reads stdin; with no operands stdin is read.
 *
 * Trailing-partial-record handling (input ending without a separator):
 * the partial record becomes the *first* line emitted on output, with no
 * synthetic separator appended. Symmetrically, an empty trailing record
 * (input that *does* end with the separator) emits nothing extra at the
 * front of the output.
 */
public class TacCommand :
    Command,
    CommandSpec {
    override val name: String = "tac"
    override val kind: CommandKind = CommandKind.TOOL
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var before = false
        var regex = false
        var separator = "\n"
        val operands = mutableListOf<String>()
        var endOfOpts = false

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                operands += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    operands += a
                }

                a == "-b" || a == "--before" -> {
                    before = true
                }

                a == "-r" || a == "--regex" -> {
                    regex = true
                }

                a == "-s" -> {
                    if (i + 1 >= args.size) {
                        ctx.stderr.writeUtf8("tac: option requires an argument -- 's'\n")
                        return CommandResult(exitCode = 2)
                    }
                    separator = args[i + 1]
                    i++
                }

                a.startsWith("--separator=") -> {
                    separator = a.substring("--separator=".length)
                }

                a.startsWith("-s") && a.length > 2 -> {
                    separator = a.substring(2)
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Combined short flags (e.g. -br).
                    var j = 1
                    while (j < a.length) {
                        when (a[j]) {
                            'b' -> {
                                before = true
                            }

                            'r' -> {
                                regex = true
                            }

                            's' -> {
                                // Rest of the arg, or next arg, is the separator value.
                                if (j + 1 < a.length) {
                                    separator = a.substring(j + 1)
                                    j = a.length
                                } else {
                                    if (i + 1 >= args.size) {
                                        ctx.stderr.writeUtf8("tac: option requires an argument -- 's'\n")
                                        return CommandResult(exitCode = 2)
                                    }
                                    separator = args[i + 1]
                                    i++
                                }
                            }

                            else -> {
                                ctx.stderr.writeUtf8("tac: invalid option -- '${a[j]}'\n")
                                return CommandResult(exitCode = 2)
                            }
                        }
                        j++
                    }
                }

                else -> {
                    operands += a
                }
            }
            i++
        }

        if (separator.isEmpty()) {
            ctx.stderr.writeUtf8("tac: separator cannot be empty\n")
            return CommandResult(exitCode = 1)
        }

        // Slurp all inputs in operand order (this is GNU tac's behavior — the
        // whole concatenated stream is reversed as a unit).
        val sb = StringBuilder()
        var exit = 0
        if (operands.isEmpty()) {
            sb.append(ctx.stdin.readAllBytes().decodeToString())
        } else {
            for (op in operands) {
                if (op == "-") {
                    sb.append(ctx.stdin.readAllBytes().decodeToString())
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, op)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("tac: $op: No such file or directory\n")
                        exit = 1
                        continue
                    }
                    try {
                        sb.append(
                            ctx.process.fs
                                .source(abs)
                                .readAllBytes()
                                .decodeToString(),
                        )
                    } catch (e: Exception) {
                        ctx.stderr.writeUtf8("tac: $op: ${e.message ?: "read error"}\n")
                        exit = 1
                    }
                }
            }
        }

        val text = sb.toString()
        if (text.isEmpty()) return CommandResult(exitCode = exit)

        val (records, seps) =
            if (regex) {
                splitByRegex(text, separator)
            } else {
                splitByLiteral(text, separator)
            }

        // Build output tokens.
        // Default (after): token_i = records[i] + (seps[i] if i < seps.size else "")
        // -b (before):     token_i = (seps[i-1] if i > 0 else "") + records[i]
        // Then reverse and concatenate.
        val tokens = ArrayList<String>(records.size)
        if (before) {
            for (k in records.indices) {
                val pre = if (k > 0) seps[k - 1] else ""
                tokens += pre + records[k]
            }
        } else {
            for (k in records.indices) {
                val post = if (k < seps.size) seps[k] else ""
                tokens += records[k] + post
            }
        }
        tokens.reverse()
        val outSb = StringBuilder(text.length)
        for (t in tokens) outSb.append(t)
        ctx.stdout.writeUtf8(outSb.toString())
        return CommandResult(exitCode = exit)
    }

    /**
     * Split [text] on literal [sep]. Returns (records, separators). For a
     * string with n separators, returns n+1 records and n separators (each
     * equal to [sep]).
     */
    private fun splitByLiteral(
        text: String,
        sep: String,
    ): Pair<List<String>, List<String>> {
        val records = ArrayList<String>()
        val seps = ArrayList<String>()
        var pos = 0
        while (true) {
            val idx = text.indexOf(sep, pos)
            if (idx < 0) {
                records += text.substring(pos)
                break
            }
            records += text.substring(pos, idx)
            seps += sep
            pos = idx + sep.length
        }
        return records to seps
    }

    /**
     * Split [text] using regex [pattern] as the separator. Each match yields
     * one separator string (the actual matched substring, which may differ
     * across matches). Zero-width matches advance one character to guarantee
     * progress, matching GNU tac's defensive behavior.
     */
    private fun splitByRegex(
        text: String,
        pattern: String,
    ): Pair<List<String>, List<String>> {
        val re = Regex(pattern)
        val records = ArrayList<String>()
        val seps = ArrayList<String>()
        var pos = 0
        while (pos <= text.length) {
            val m = re.find(text, pos) ?: break
            val start = m.range.first
            val end = m.range.last + 1
            if (end == start) {
                // Zero-width match — record up to here, advance by one.
                records += text.substring(pos, start)
                seps += ""
                if (start >= text.length) {
                    pos = text.length + 1
                    break
                }
                pos = start + 1
                continue
            }
            records += text.substring(pos, start)
            seps += m.value
            pos = end
        }
        records += text.substring(pos.coerceAtMost(text.length))
        return records to seps
    }
}
