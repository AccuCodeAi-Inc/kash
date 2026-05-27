package com.accucodeai.kash.tools.pkill

import com.accucodeai.kash.api.Command
import com.accucodeai.kash.api.CommandContext
import com.accucodeai.kash.api.CommandKind
import com.accucodeai.kash.api.CommandResult
import com.accucodeai.kash.api.CommandSpec
import com.accucodeai.kash.api.CommandTag
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.signal.KashSignal
import com.accucodeai.kash.tools.pgrep.PatternException
import com.accucodeai.kash.tools.pgrep.PgrepFilter

/**
 * `pkill` — like `pgrep`, but instead of printing matching pids, "signals"
 * them. In kash there is no kernel-level signal delivery; we model
 * SIGTERM/SIGKILL/etc as **removal from the machine's process table** via
 * [com.accucodeai.kash.api.KashMachine.unregisterProcess]. The coroutine
 * doing the work isn't preempted (kash has no SIGKILL — see KashSignal
 * docs), but the process disappears from `ps` and from any pgrep that
 * follows, which is the observable effect most scripts care about.
 *
 * SIGSTOP/SIGCONT/SIGHUP and friends are accepted at the CLI but treated as
 * "best-effort no-op" — we print a diagnostic on stderr only when stderr
 * is interactive and the user passed `-e`.
 *
 * Exit codes:
 *   - 0 if at least one process was signalled
 *   - 1 if nothing matched
 *   - 2 on usage error / invalid pattern / invalid signal name
 */
public class PkillCommand :
    Command,
    CommandSpec {
    override val name: String = "pkill"
    override val kind: CommandKind = CommandKind.TOOL
    override val tags: Set<CommandTag> = setOf(CommandTag.POSIX, CommandTag.IMPURE)
    override val command: Command get() = this

    override suspend fun run(
        args: List<String>,
        ctx: CommandContext,
    ): CommandResult {
        val opts =
            try {
                parsePkillArgs(args)
            } catch (e: PkillUsageException) {
                ctx.stderr.writeUtf8("pkill: ${e.message}\n")
                return CommandResult(2)
            }
        if (opts.showHelp) {
            ctx.stdout.writeUtf8(HELP)
            return CommandResult(0)
        }
        if (opts.showVersion) {
            ctx.stdout.writeUtf8("pkill (kash) 1.0\n")
            return CommandResult(0)
        }
        if (opts.patterns.isEmpty() && opts.user == null) {
            ctx.stderr.writeUtf8("pkill: no matching criteria specified\n")
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
                ctx.stderr.writeUtf8("pkill: invalid pattern: ${e.message}\n")
                return CommandResult(2)
            }

        val machine = ctx.process.machine
        val selfPid = ctx.process.pid
        val all = machine.processTable.values.sortedBy { it.pid }
        val matches = all.filter { it.pid != selfPid && filter.matches(it) }
        val picked =
            when {
                opts.newestOnly -> matches.maxByOrNull { it.startTimeMillis }?.let { listOf(it) } ?: emptyList()
                opts.oldestOnly -> matches.minByOrNull { it.startTimeMillis }?.let { listOf(it) } ?: emptyList()
                else -> matches
            }
        if (picked.isEmpty()) return CommandResult(1)

        // Map every signal we model to "remove the process from the table".
        // No real preemption — see class kdoc.
        val killedPids = mutableListOf<Int>()
        for (p in picked) {
            machine.unregisterProcess(p.pid)
            killedPids += p.pid
        }

        if (opts.echoKilled) {
            ctx.stdout.writeUtf8(killedPids.joinToString("\n"))
            ctx.stdout.writeUtf8("\n")
        }
        return CommandResult(if (killedPids.isEmpty()) 1 else 0)
    }

    private companion object {
        const val HELP: String =
            "Usage: pkill [options] PATTERN [PATTERN...]\n" +
                "  -SIGNAL       signal to send (TERM by default)\n" +
                "  --signal=SIG  same as -SIGNAL\n" +
                "  -e            echo pids of killed processes\n" +
                "  -f            match against full command line\n" +
                "  -n            most recently started match only\n" +
                "  -o            oldest match only\n" +
                "  -u USER       match by user\n" +
                "  -x            exact (anchored) match\n" +
                "  -i            case-insensitive match\n" +
                "  -v            invert match\n" +
                "  -h, --help    show this help\n" +
                "  -V, --version show version\n"
    }
}

internal data class PkillOptions(
    val patterns: List<String> = emptyList(),
    val signal: KashSignal = com.accucodeai.kash.api.signal.SigTerm,
    val echoKilled: Boolean = false,
    val matchFull: Boolean = false,
    val newestOnly: Boolean = false,
    val oldestOnly: Boolean = false,
    val user: String? = null,
    val exact: Boolean = false,
    val caseInsensitive: Boolean = false,
    val invert: Boolean = false,
    val showHelp: Boolean = false,
    val showVersion: Boolean = false,
)

internal class PkillUsageException(
    message: String,
) : RuntimeException(message)

private val FLAG_LETTERS = setOf('e', 'f', 'n', 'o', 'x', 'i', 'v', 'l', 'a', 'c')

internal fun parsePkillArgs(args: List<String>): PkillOptions {
    var o = PkillOptions()
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
        when {
            a == "--" -> {
                endOfOpts = true
                i++
            }

            a == "-h" || a == "--help" -> {
                o = o.copy(showHelp = true)
                i++
            }

            a == "-V" || a == "--version" -> {
                o = o.copy(showVersion = true)
                i++
            }

            a == "-e" -> {
                o = o.copy(echoKilled = true)
                i++
            }

            a == "-f" -> {
                o = o.copy(matchFull = true)
                i++
            }

            a == "-n" -> {
                o = o.copy(newestOnly = true)
                i++
            }

            a == "-o" -> {
                o = o.copy(oldestOnly = true)
                i++
            }

            a == "-x" -> {
                o = o.copy(exact = true)
                i++
            }

            a == "-i" -> {
                o = o.copy(caseInsensitive = true)
                i++
            }

            a == "-v" -> {
                o = o.copy(invert = true)
                i++
            }

            a == "-u" -> {
                if (i + 1 >= args.size) throw PkillUsageException("-u requires an argument")
                o = o.copy(user = args[i + 1])
                i += 2
            }

            a == "-s" || a == "--signal" -> {
                if (i + 1 >= args.size) throw PkillUsageException("$a requires an argument")
                o = o.copy(signal = parseSignal(args[i + 1]))
                i += 2
            }

            a.startsWith("--signal=") -> {
                o = o.copy(signal = parseSignal(a.removePrefix("--signal=")))
                i++
            }

            // -SIGNAL (name) or -NNN (number) — distinguish from bundled flags.
            // If the token starts with a digit, it's a numeric signal.
            // If it starts with a letter but is not a recognized bundled flag set,
            // try it as a signal name.
            a.length > 1 && a[1].isDigit() -> {
                o = o.copy(signal = parseSignal(a.drop(1)))
                i++
            }

            a.length > 1 && a[1] !in FLAG_LETTERS && a[1] != '-' -> {
                // -TERM, -HUP, -USR1, etc.
                o = o.copy(signal = parseSignal(a.drop(1)))
                i++
            }

            else -> {
                // Bundled short flags like -ef, -nfx
                val flags = a.drop(1)
                var j = 0
                while (j < flags.length) {
                    when (val c = flags[j]) {
                        'e' -> o = o.copy(echoKilled = true)
                        'f' -> o = o.copy(matchFull = true)
                        'n' -> o = o.copy(newestOnly = true)
                        'o' -> o = o.copy(oldestOnly = true)
                        'x' -> o = o.copy(exact = true)
                        'i' -> o = o.copy(caseInsensitive = true)
                        'v' -> o = o.copy(invert = true)
                        else -> throw PkillUsageException("unknown option: -$c")
                    }
                    j++
                }
                i++
            }
        }
    }
    return o.copy(patterns = pats)
}

/**
 * Resolve a signal token. We accept the superset of names that real
 * pkill(1) accepts so scripts don't blow up on `-9` / `-KILL`. Names
 * kash doesn't model collapse to [com.accucodeai.kash.api.signal.SigTerm]
 * — the observable behaviour in kash is the same anyway (process
 * disappears from the table); the surviving distinction is the diagnostic.
 */
private fun parseSignal(token: String): KashSignal {
    KashSignal.parse(token)?.let { return it }
    val t = token.uppercase().removePrefix("SIG")
    val knownExtras =
        setOf(
            "KILL",
            "ABRT",
            "ALRM",
            "BUS",
            "CHLD",
            "CLD",
            "FPE",
            "ILL",
            "IO",
            "POLL",
            "PROF",
            "PWR",
            "SEGV",
            "SYS",
            "TRAP",
            "TTIN",
            "TTOU",
            "URG",
            "VTALRM",
            "WINCH",
            "XCPU",
            "XFSZ",
            "IOT",
            "EMT",
        )
    if (t in knownExtras) return com.accucodeai.kash.api.signal.SigTerm
    // Numeric signals in the POSIX range — accept silently.
    val n = t.toIntOrNull()
    if (n != null && n in 0..64) return com.accucodeai.kash.api.signal.SigTerm
    throw PkillUsageException("invalid signal: $token")
}
