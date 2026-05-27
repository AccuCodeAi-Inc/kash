package com.accucodeai.kash.tools.unexpand

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
 * `unexpand` — POSIX spaces-to-tabs filter, inverse of `expand`.
 *
 * Flags:
 * - `-a`: convert blanks anywhere on the line; default is leading-only.
 * - `-t LIST`: tab-stop list (same syntax as `expand`). Implies `-a`.
 *
 * Algorithm: walk each line tracking column position; whenever the run of
 * blanks (spaces, and tabs counted as advancing to next stop) crosses a
 * tab-stop, emit a `\t` for each stop crossed and the residual spaces
 * after the last stop. With `-a` off, processing reverts to verbatim emit
 * after the first non-blank on the line.
 */
public class UnexpandCommand :
    Command,
    CommandSpec {
    override val name: String = "unexpand"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX)
    override val command: Command get() = this

    private data class Opts(
        val stops: List<Int>,
        val uniform: Int,
        val all: Boolean,
        val files: List<String>,
    )

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts = parse(args, ctx) ?: return CommandResult(exitCode = 2)

        if (opts.files.isEmpty()) {
            unexpandSource(ctx.stdin, opts, ctx)
        } else {
            for (f in opts.files) {
                if (f == "-") {
                    unexpandSource(ctx.stdin, opts, ctx)
                } else {
                    val abs = Paths.resolve(ctx.process.cwd, f)
                    if (!ctx.process.fs.exists(abs)) {
                        ctx.stderr.writeUtf8("unexpand: $f: No such file or directory\n")
                        return CommandResult(exitCode = 1)
                    }
                    unexpandSource(ctx.process.fs.source(abs), opts, ctx)
                }
            }
        }
        return CommandResult()
    }

    private suspend fun unexpandSource(
        src: SuspendSource,
        o: Opts,
        ctx: CommandContext,
    ) {
        val text = src.readUtf8Text()
        if (text.isEmpty()) return
        // Split on '\n' preserving the last partial line.
        val out = StringBuilder()
        var lineStart = 0
        var i = 0
        while (i <= text.length) {
            val atEnd = i == text.length
            val isNl = !atEnd && text[i] == '\n'
            if (isNl || atEnd) {
                unexpandLine(text, lineStart, i, o, out)
                if (isNl) out.append('\n')
                lineStart = i + 1
            }
            if (atEnd) break
            i++
        }
        ctx.stdout.writeUtf8(out.toString())
    }

    /**
     * Process [s] in indices [start, end) — a single logical line with no
     * trailing newline — emitting into [out].
     */
    private fun unexpandLine(
        s: String,
        start: Int,
        end: Int,
        o: Opts,
        out: StringBuilder,
    ) {
        // We buffer runs of blanks (' ' and '\t') and at run end (or column
        // boundary) decide how many tabs / trailing spaces best represent
        // the column-distance covered.
        var col = 0
        var runStartCol = 0
        var runSpaces = 0 // count of physical chars consumed in the current run
        // The actual position the cursor advances to as we consume blanks.
        var runEndCol = 0
        var leadingOver = false // false until we hit a non-blank in non-'-a' mode
        var i = start

        fun flushRun(keepTrailingSpaces: Boolean) {
            if (runSpaces == 0) return
            // Emit tabs to cross every tab stop between runStartCol and runEndCol,
            // then spaces for the remainder.
            var c = runStartCol
            val targetCol = runEndCol
            val tabsAllowed = !leadingOver || o.all
            if (tabsAllowed) {
                while (true) {
                    val ns = nextStop(c, o)
                    // Only emit a tab if it lands at or before target AND the tab
                    // would cover at least one column (it always does), AND the
                    // tab stop is meaningful (within bounded stops list, we'd
                    // still emit). Standard rule: emit tab iff ns <= targetCol AND
                    // the run had at least 2 characters of blank to merge (POSIX
                    // unexpand only merges if it shortens). However GNU's rule is
                    // simpler: convert every blank run that crosses ≥1 stop into
                    // tabs+residual-spaces. We follow GNU.
                    if (ns > targetCol) break
                    out.append('\t')
                    c = ns
                }
            }
            val residual = targetCol - c
            if (keepTrailingSpaces) {
                repeat(residual) { out.append(' ') }
            } else {
                // unreachable in current flow; kept for clarity
                repeat(residual) { out.append(' ') }
            }
            runSpaces = 0
        }

        while (i < end) {
            val c = s[i]
            when {
                c == ' ' -> {
                    if (runSpaces == 0) {
                        runStartCol = col
                        runEndCol = col
                    }
                    runSpaces++
                    runEndCol = col + 1
                    col++
                }

                c == '\t' -> {
                    if (runSpaces == 0) {
                        runStartCol = col
                        runEndCol = col
                    }
                    runSpaces++
                    val ns = nextStop(col, o)
                    runEndCol = ns
                    col = ns
                }

                else -> {
                    // Flush blank run before this non-blank character.
                    flushRun(keepTrailingSpaces = true)
                    if (!o.all) leadingOver = true
                    if (c == '\b') {
                        out.append(c)
                        if (col > 0) col--
                    } else if (c == '\r') {
                        out.append(c)
                        col = 0
                    } else if (c.isHighSurrogate() && i + 1 < end && s[i + 1].isLowSurrogate()) {
                        out.append(c)
                        out.append(s[i + 1])
                        i++
                        col++
                    } else {
                        out.append(c)
                        col++
                    }
                }
            }
            i++
        }
        // End of line: flush any trailing blank run. We still tab-fold it
        // (consistent with leading runs) when allowed; this matches GNU
        // unexpand's behavior under -a / -t.
        if (runSpaces > 0) {
            flushRun(keepTrailingSpaces = true)
        }
    }

    private fun nextStop(
        col: Int,
        o: Opts,
    ): Int {
        if (o.stops.isEmpty()) {
            val step = if (o.uniform > 0) o.uniform else 8
            return ((col / step) + 1) * step
        }
        for (s in o.stops) {
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
        var all = false
        var tabsExplicit = false
        val files = mutableListOf<String>()
        var endOfOpts = false

        suspend fun usage(msg: String): Opts? {
            ctx.stderr.writeUtf8("unexpand: $msg\n")
            return null
        }

        suspend fun setTabs(spec: String): Boolean {
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
                    ctx.stdout.writeUtf8("usage: unexpand [-a] [-t tablist] [file ...]\n")
                    return Opts(emptyList(), 0, false, emptyList())
                }

                a == "-a" || a == "--all" -> {
                    all = true
                }

                a == "-t" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument -- 't'")
                    if (!setTabs(args[++i])) return null
                    tabsExplicit = true
                }

                a.startsWith("--tabs=") -> {
                    if (!setTabs(a.substringAfter("="))) return null
                    tabsExplicit = true
                }

                a == "--tabs" -> {
                    if (i + 1 >= args.size) return usage("option requires an argument -- 'tabs'")
                    if (!setTabs(args[++i])) return null
                    tabsExplicit = true
                }

                a.startsWith("-t") && a.length > 2 -> {
                    if (!setTabs(a.substring(2))) return null
                    tabsExplicit = true
                }

                a.startsWith("-") && a.length > 1 -> {
                    for (c in a.drop(1)) {
                        when (c) {
                            'a' -> all = true
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
        // -t implies -a per GNU. (POSIX says default scope is leading only;
        // both -a and -t override that; -t and -a together is fine.)
        if (tabsExplicit) all = true
        return Opts(stops = stops, uniform = uniform, all = all, files = files)
    }
}
