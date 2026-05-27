package com.accucodeai.kash.tools.paste

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
import com.accucodeai.kash.fs.Paths

/**
 * `paste` — merge corresponding (parallel) or successive (serial) lines from files.
 *
 * Flags:
 * - `-d LIST` delimiter list, cycled per column boundary. Default `\t`. Escapes:
 *   `\n`, `\t`, `\\`, `\0` (NUL — emitted as no separator per POSIX).
 * - `-s` serial mode: for each file, concatenate its lines with cycled delimiters,
 *   producing one output line per input file. Without `-s`, parallel (lock-step)
 *   mode aligns lines across all inputs, padding short inputs with empty fields.
 * - `--` ends option parsing.
 *
 * Operand `-` reads from stdin. Multiple `-` operands all share the same stdin
 * stream and consume from it in turn (in parallel mode each `-` reads one line
 * per round; in serial mode the first `-` consumes all of stdin and subsequent
 * `-` operands produce an empty row).
 */
public class PasteCommand :
    Command,
    CommandSpec {
    override val name: String = "paste"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        var serial = false
        var delimSpec: String? = null
        val operands = mutableListOf<String>()
        var endOfOpts = false
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                operands += a
            } else if (a == "--") {
                endOfOpts = true
            } else if (a == "-") {
                operands += a
            } else if (a == "-s") {
                serial = true
            } else if (a == "-d") {
                if (i + 1 >= args.size) {
                    ctx.stderr.writeUtf8("paste: option requires an argument -- 'd'\n")
                    return CommandResult(exitCode = 2)
                }
                delimSpec = args[i + 1]
                i++
            } else if (a.startsWith("-d") && a.length > 2) {
                delimSpec = a.substring(2)
            } else if (a.startsWith("-") && a.length > 1) {
                // Combined short flags; only -s is non-arg.
                var j = 1
                while (j < a.length) {
                    val c = a[j]
                    when (c) {
                        's' -> {
                            serial = true
                        }

                        'd' -> {
                            val rest = a.substring(j + 1)
                            if (rest.isNotEmpty()) {
                                delimSpec = rest
                                j = a.length
                            } else {
                                if (i + 1 >= args.size) {
                                    ctx.stderr.writeUtf8("paste: option requires an argument -- 'd'\n")
                                    return CommandResult(exitCode = 2)
                                }
                                delimSpec = args[i + 1]
                                i++
                                j = a.length
                            }
                        }

                        else -> {
                            ctx.stderr.writeUtf8("paste: invalid option -- '$c'\n")
                            return CommandResult(exitCode = 2)
                        }
                    }
                    j++
                }
            } else {
                operands += a
            }
            i++
        }

        val delims: List<String> = parseDelimList(delimSpec)
        val effectiveOperands = if (operands.isEmpty()) listOf("-") else operands

        // Resolve each operand to a SuspendSource. All `-` map to ctx.stdin (same instance).
        var anyErr = false
        val sources = mutableListOf<SuspendSource>()
        for (op in effectiveOperands) {
            if (op == "-") {
                sources += ctx.stdin
            } else {
                val abs = Paths.resolve(ctx.process.cwd, op)
                if (!ctx.process.fs.exists(abs)) {
                    ctx.stderr.writeUtf8("paste: $op: No such file or directory\n")
                    anyErr = true
                    continue
                }
                try {
                    sources += ctx.process.fs.source(abs)
                } catch (e: Exception) {
                    ctx.stderr.writeUtf8("paste: $op: ${e.message ?: "read error"}\n")
                    anyErr = true
                    continue
                }
            }
        }

        if (sources.isNotEmpty()) {
            if (serial) {
                runSerial(sources, delims, ctx)
            } else {
                runParallel(sources, delims, ctx)
            }
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    /** Parse a `-d` argument into a list of delimiter strings. Each escape or
     * literal char becomes one entry; a `\0` becomes the empty string (no
     * separator). Empty list ⇒ default to a single TAB. */
    private fun parseDelimList(spec: String?): List<String> {
        if (spec == null || spec.isEmpty()) return listOf("\t")
        val out = mutableListOf<String>()
        var i = 0
        while (i < spec.length) {
            val c = spec[i]
            if (c == '\\' && i + 1 < spec.length) {
                when (spec[i + 1]) {
                    'n' -> out += "\n"

                    't' -> out += "\t"

                    '\\' -> out += "\\"

                    '0' -> out += ""

                    // empty = no separator
                    else -> out += spec[i + 1].toString()
                }
                i += 2
            } else {
                out += c.toString()
                i++
            }
        }
        return out
    }

    private suspend fun runParallel(
        sources: List<SuspendSource>,
        delims: List<String>,
        ctx: CommandContext,
    ) {
        val n = sources.size
        if (n == 0) return
        val done = BooleanArray(n)
        while (true) {
            val cols = arrayOfNulls<String>(n)
            var anyLive = false
            for (k in 0 until n) {
                if (done[k]) continue
                val line = sources[k].readUtf8LineOrNull()
                if (line == null) {
                    done[k] = true
                } else {
                    cols[k] = line
                    anyLive = true
                }
            }
            if (!anyLive) break
            val sb = StringBuilder()
            for (k in 0 until n) {
                if (k > 0) sb.append(delims[(k - 1) % delims.size])
                sb.append(cols[k] ?: "")
            }
            ctx.stdout.writeLine(sb.toString())
        }
    }

    private suspend fun runSerial(
        sources: List<SuspendSource>,
        delims: List<String>,
        ctx: CommandContext,
    ) {
        for (src in sources) {
            val sb = StringBuilder()
            var idx = 0
            while (true) {
                val line = src.readUtf8LineOrNull() ?: break
                if (idx > 0) sb.append(delims[(idx - 1) % delims.size])
                sb.append(line)
                idx++
            }
            // POSIX: even empty input (no lines) for a serial source still emits
            // an empty output line. Matches GNU paste.
            ctx.stdout.writeLine(sb.toString())
        }
    }
}
