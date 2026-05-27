package com.accucodeai.kash.tools.pgrep

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.KashProcess
import com.accucodeai.kash.api.io.writeUtf8

/**
 * `pgrep` — look up processes by name (or full command line) and print the
 * matching pids. Mirrors procps-ng's interface; flag handling and output
 * shape match `pgrep(1)`.
 *
 * Reads [com.accucodeai.kash.api.KashMachine.processTable], the same source
 * `ps` consults. Pattern is POSIX ERE; multiple positional patterns are
 * AND-combined (procps-ng behaviour).
 *
 * Exit codes: 0 on at least one match, 1 if nothing matched, 2 on usage
 * error / invalid pattern.
 */
public class PgrepCommand :
    Command,
    CommandSpec {
    override val name: String = "pgrep"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            try {
                parsePgrepArgs(args)
            } catch (e: UsageException) {
                ctx.stderr.writeUtf8("pgrep: ${e.message}\n")
                return CommandResult(2)
            }
        if (opts.showHelp) {
            ctx.stdout.writeUtf8(HELP)
            return CommandResult(0)
        }
        if (opts.showVersion) {
            ctx.stdout.writeUtf8("pgrep (kash) 1.0\n")
            return CommandResult(0)
        }
        if (opts.patterns.isEmpty() && opts.user == null) {
            ctx.stderr.writeUtf8("pgrep: no matching criteria specified\n")
            return CommandResult(2)
        }
        val filter =
            try {
                PgrepFilter(
                    patterns = opts.patterns,
                    matchFull = opts.matchFull,
                    exact = opts.exact,
                    caseInsensitive = opts.caseInsensitive,
                    invert = opts.invert,
                    user = opts.user,
                )
            } catch (e: PatternException) {
                ctx.stderr.writeUtf8("pgrep: invalid pattern: ${e.message}\n")
                return CommandResult(2)
            }

        val all =
            ctx.process.machine.processTable.values
                .sortedBy { it.pid }
        // Procps-ng excludes the pgrep process itself by default.
        val selfPid = ctx.process.pid
        val matches = all.filter { it.pid != selfPid && filter.matches(it) }
        val picked =
            when {
                opts.newestOnly -> matches.maxByOrNull { it.startTimeMillis }?.let { listOf(it) } ?: emptyList()
                opts.oldestOnly -> matches.minByOrNull { it.startTimeMillis }?.let { listOf(it) } ?: emptyList()
                else -> matches
            }

        if (opts.countOnly) {
            ctx.stdout.writeUtf8("${picked.size}\n")
            return CommandResult(if (picked.isEmpty()) 1 else 0)
        }
        if (picked.isEmpty()) return CommandResult(1)

        val lines = picked.map { formatRow(it, opts) }
        ctx.stdout.writeUtf8(lines.joinToString(opts.delimiter))
        // Procps always finishes with one trailing newline.
        ctx.stdout.writeUtf8("\n")
        return CommandResult(0)
    }

    private fun formatRow(
        p: KashProcess,
        o: PgrepOptions,
    ): String =
        when {
            o.printFullCmd -> {
                val cmd =
                    when {
                        p.argv.isNotEmpty() -> p.argv.joinToString(" ")
                        p.commandName.isNotEmpty() -> p.commandName
                        else -> ""
                    }
                "${p.pid} $cmd"
            }

            o.listNames -> {
                val n = p.commandName.ifEmpty { p.argv.firstOrNull() ?: "" }
                "${p.pid} $n"
            }

            else -> {
                p.pid.toString()
            }
        }

    private companion object {
        const val HELP: String =
            "Usage: pgrep [options] PATTERN [PATTERN...]\n" +
                "  -l            list pid and process name\n" +
                "  -a            list pid and full command line\n" +
                "  -c            count of matching processes\n" +
                "  -f            match against full command line\n" +
                "  -n            most recently started match only\n" +
                "  -o            oldest match only\n" +
                "  -u USER       match by user\n" +
                "  -x            exact (anchored) match\n" +
                "  -i            case-insensitive match\n" +
                "  -v            invert match\n" +
                "  -d STR        output delimiter (default newline)\n" +
                "  -h, --help    show this help\n" +
                "  -V, --version show version\n"
    }
}

internal data class PgrepOptions(
    val patterns: List<String> = emptyList(),
    val listNames: Boolean = false,
    val printFullCmd: Boolean = false,
    val countOnly: Boolean = false,
    val matchFull: Boolean = false,
    val newestOnly: Boolean = false,
    val oldestOnly: Boolean = false,
    val user: String? = null,
    val exact: Boolean = false,
    val caseInsensitive: Boolean = false,
    val invert: Boolean = false,
    val delimiter: String = "\n",
    val showHelp: Boolean = false,
    val showVersion: Boolean = false,
)

internal class UsageException(
    message: String,
) : RuntimeException(message)

internal fun parsePgrepArgs(args: List<String>): PgrepOptions {
    var o = PgrepOptions()
    val pats = mutableListOf<String>()
    var i = 0
    var endOfOpts = false
    while (i < args.size) {
        val a = args[i]
        if (endOfOpts || !a.startsWith("-") || a == "-") {
            pats += a
            i++
            continue
        }
        when (a) {
            "--" -> {
                endOfOpts = true
                i++
            }

            "-h", "--help" -> {
                o = o.copy(showHelp = true)
                i++
            }

            "-V", "--version" -> {
                o = o.copy(showVersion = true)
                i++
            }

            "-l" -> {
                o = o.copy(listNames = true)
                i++
            }

            "-a" -> {
                o = o.copy(printFullCmd = true)
                i++
            }

            "-c" -> {
                o = o.copy(countOnly = true)
                i++
            }

            "-f" -> {
                o = o.copy(matchFull = true)
                i++
            }

            "-n" -> {
                o = o.copy(newestOnly = true)
                i++
            }

            "-o" -> {
                o = o.copy(oldestOnly = true)
                i++
            }

            "-x" -> {
                o = o.copy(exact = true)
                i++
            }

            "-i" -> {
                o = o.copy(caseInsensitive = true)
                i++
            }

            "-v" -> {
                o = o.copy(invert = true)
                i++
            }

            "-u" -> {
                if (i + 1 >= args.size) throw UsageException("-u requires an argument")
                o = o.copy(user = args[i + 1])
                i += 2
            }

            "-d" -> {
                if (i + 1 >= args.size) throw UsageException("-d requires an argument")
                o = o.copy(delimiter = args[i + 1])
                i += 2
            }

            else -> {
                // Unknown long option (--foo) — treat as a positional pattern
                // rather than erroring, so `pgrep -f --config` works without
                // needing an explicit `--` separator.
                if (a.startsWith("--")) {
                    pats += a
                    i++
                    continue
                }
                // bundled short opts like -lc
                if (a.startsWith("-") && a.length > 1 && a[1] != '-') {
                    val flags = a.drop(1)
                    var j = 0
                    while (j < flags.length) {
                        when (val c = flags[j]) {
                            'l' -> o = o.copy(listNames = true)
                            'a' -> o = o.copy(printFullCmd = true)
                            'c' -> o = o.copy(countOnly = true)
                            'f' -> o = o.copy(matchFull = true)
                            'n' -> o = o.copy(newestOnly = true)
                            'o' -> o = o.copy(oldestOnly = true)
                            'x' -> o = o.copy(exact = true)
                            'i' -> o = o.copy(caseInsensitive = true)
                            'v' -> o = o.copy(invert = true)
                            else -> throw UsageException("unknown option: -$c")
                        }
                        j++
                    }
                    i++
                } else {
                    throw UsageException("unknown option: $a")
                }
            }
        }
    }
    return o.copy(patterns = pats)
}
