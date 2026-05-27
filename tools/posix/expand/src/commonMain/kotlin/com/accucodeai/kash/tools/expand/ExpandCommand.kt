package com.accucodeai.kash.tools.expand

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.SuspendSource
import com.accucodeai.kash.api.io.readUtf8Text
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.fs.Paths

/**
 * `expand` — POSIX tabs-to-spaces filter.
 *
 * Flags:
 * - `-t LIST`: tab-stop list. Either a single positive integer (uniform
 *   stop every N columns) or a comma/whitespace-separated ascending list
 *   of 1-indexed stop positions. After the last explicit stop, tabs map
 *   to a single space.
 * - `-i`: only expand leading tabs (the run of tabs / blanks preceding
 *   the first non-blank-non-tab character on a line).
 *
 * Column counting is codepoint-based (UTF-8 internal; see CLAUDE.md).
 * Backspace and carriage return reset/decrement the column to keep
 * aligned output usable.
 */
public class ExpandCommand :
    Command,
    CommandSpec {
    override val name: String = "expand"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Opts(
        val stops: List<Int>,
        val uniform: Int,
        val initialOnly: Boolean,
        val files: List<String>,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = parse(args, ctx) ?: return CommandResult(exitCode = 2)

        var anyErr = false
        if (opts.files.isEmpty()) {
            expandSource(ctx.stdin, opts, ctx)
        } else {
            for (f in opts.files) {
                if (f == "-") {
                    expandSource(ctx.stdin, opts, ctx)
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, f)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("expand: $f: No such file or directory\n")
                        anyErr = true
                        continue
                    }
                    expandSource(ctx.process.fs.source(abs), opts, ctx)
                }
            }
        }
        return CommandResult(exitCode = if (anyErr) 1 else 0)
    }

    private suspend fun expandSource(
        src: SuspendSource,
        o: Opts,
        ctx: CommandContext,
    ) {
        val text = src.readUtf8Text()
        if (text.isEmpty()) return
        val out = StringBuilder()
        var col = 0
        var leading = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\n' -> {
                    out.append('\n')
                    col = 0
                    leading = true
                }

                '\t' -> {
                    if (o.initialOnly && !leading) {
                        out.append('\t')
                        // Column accounting still advances to next tab stop for
                        // consistency, though only used for subsequent leading
                        // checks on this line — which don't happen since leading
                        // is already false.
                        col = nextStop(col, o)
                    } else {
                        val target = nextStop(col, o)
                        repeat(target - col) { out.append(' ') }
                        col = target
                    }
                }

                '\b' -> {
                    out.append('\b')
                    if (col > 0) col--
                    // backspace doesn't break leading-blank status
                }

                '\r' -> {
                    out.append('\r')
                    col = 0
                }

                else -> {
                    // High surrogate: emit the full surrogate pair as a single
                    // column to keep codepoint accounting honest.
                    if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                        out.append(c)
                        out.append(text[i + 1])
                        i++
                        col++
                    } else {
                        out.append(c)
                        col++
                    }
                    if (c != ' ') leading = false
                }
            }
            i++
        }
        ctx.stdout.writeUtf8(out.toString())
    }

    /** Next tab-stop column strictly greater than [col] (0-indexed cols). */
    private fun nextStop(
        col: Int,
        o: Opts,
    ): Int {
        if (o.stops.isEmpty()) {
            val step = if (o.uniform > 0) o.uniform else 8
            return ((col / step) + 1) * step
        }
        for (s in o.stops) {
            // stops are 1-indexed positions; column N means "next char goes at N+1"
            // i.e. tab moves from col `col` to col `stop` (treat stop as 1-indexed
            // column for the next char). We translate to 0-indexed: stop - 1 is the
            // column to advance to ... but POSIX semantics are: tab advances the
            // cursor to position list[i] (1-based). If col < list[i]-1 advance to list[i]-1? No—
            // The classic interpretation: tab stops at columns 8, 16, ... means after
            // the tab you're at column 8 (1-indexed) i.e. you've consumed 8 cols. We use
            // 0-indexed col such that "at the start of column 8 (1-based)" = col 7,
            // and a tab advances col to 7. But everyone tests expand by counting spaces
            // emitted: -t 4 means tab→4 spaces from col 0. So nextStop(0)=4 (0-indexed: at col 4).
            // So treat the list as 1-indexed positions where the *next char after the tab*
            // sits, equivalent to "advance col to value-1+1 = value" in 0-indexed cumulative
            // chars emitted. Simplest: tab from col c advances to stop s iff s > c.
            if (s > col) return s
        }
        return col + 1
    }

    private suspend fun parse(
        args: List<String>,
        ctx: CommandContext,
    ): Opts? {
        var stops: List<Int> = emptyList()
        var uniform = 0
        var initialOnly = false
        val files = mutableListOf<String>()
        var endOfOpts = false

        suspend fun usage(msg: String): Opts? {
            ctx.stderr.writeUtf8("expand: $msg\n")
            return null
        }

        suspend fun setTabs(spec: String): Boolean {
            // Either a single positive integer (uniform) or comma/whitespace
            // separated ascending list of 1-indexed positions.
            val parts =
                spec
                    .split(',', ' ', '\t')
                    .filter { it.isNotEmpty() }
            if (parts.isEmpty()) {
                usage("tab size cannot be empty")
                return false
            }
            if (parts.size == 1) {
                val v = parts[0].toIntOrNull()
                if (v == null || v <= 0) {
                    usage("tab size contains invalid character(s): '${parts[0]}'")
                    return false
                }
                uniform = v
                stops = emptyList()
                return true
            }
            val list = mutableListOf<Int>()
            var prev = 0
            for (p in parts) {
                val v = p.toIntOrNull()
                if (v == null || v <= 0) {
                    usage("tab size contains invalid character(s): '$p'")
                    return false
                }
                if (v <= prev) {
                    usage("tab sizes must be ascending")
                    return false
                }
                list += v
                prev = v
            }
            stops = list
            uniform = 0
            return true
        }

        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (endOfOpts) {
                files += a
                i++
                continue
            }
            when {
                a == "--" -> {
                    endOfOpts = true
                }

                a == "-" -> {
                    files += a
                }

                a == "--help" -> {
                    ctx.stdout.writeUtf8("usage: expand [-i] [-t tablist] [file ...]\n")
                    return Opts(emptyList(), 0, false, emptyList())
                }

                a == "-i" || a == "--initial" -> {
                    initialOnly = true
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) {
                        return usage("option requires an argument -- 't'")
                    }
                    if (!setTabs(args[++i])) return null
                }

                a.startsWith("--tabs=") -> {
                    if (!setTabs(a.substringAfter("="))) return null
                }

                a == "--tabs" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument -- 'tabs'")
                    if (!setTabs(args[++i])) return null
                }

                a.startsWith("-t") && a.length > 2 -> {
                    if (!setTabs(a.substring(2))) return null
                }

                // Legacy GNU form: -<digits> as shorthand for -t<digits>
                a.length > 1 && a[0] == '-' && a.drop(1).all { it.isDigit() } -> {
                    if (!setTabs(a.substring(1))) return null
                }

                a.startsWith("-") && a.length > 1 -> {
                    // Combined letter flags
                    for (c in a.drop(1)) {
                        when (c) {
                            'i' -> initialOnly = true
                            else -> return usage("invalid option -- '$c'")
                        }
                    }
                }

                else -> {
                    files += a
                }
            }
            i++
        }
        return Opts(stops = stops, uniform = uniform, initialOnly = initialOnly, files = files)
    }
}
