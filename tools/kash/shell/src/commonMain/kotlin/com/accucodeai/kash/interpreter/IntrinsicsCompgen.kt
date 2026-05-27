package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.asSuspendSink
import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.api.util.matchGlob
import com.accucodeai.kash.api.util.splitPath
import com.accucodeai.kash.completion.BASH_BUILTIN_NAMES
import com.accucodeai.kash.completion.BASH_HELPTOPICS
import com.accucodeai.kash.completion.CompleteAction
import com.accucodeai.kash.completion.KNOWN_SHOPT_NAMES
import com.accucodeai.kash.interpreter.Interpreter.Stdio
import com.accucodeai.kash.intrinsics.IntrinsicCatalog
import com.accucodeai.kash.parser.RESERVED_WORDS
import kotlinx.io.readString

private const val COMPGEN_USAGE =
    "compgen: usage: compgen [-V varname] [-abcdefgjksuv] [-o option] " +
        "[-A action] [-G globpat] [-W wordlist] [-F function] " +
        "[-C command] [-X filterpat] [-P prefix] [-S suffix] [word]"

/**
 * Bash extension [`compgen`](https://www.gnu.org/software/bash/manual/html_node/Programmable-Completion-Builtins.html):
 * enumerate completion candidates without performing the completion.
 *
 * Supported in v1: every action flag bash knows (a/b/c/d/e/f/g/j/k/s/u/v
 * + -A name), -W "wordlist", -G "globpat", -X "filterpat", -P prefix,
 * -S suffix, -V arrayvar, -F function-callback, -C command-callback.
 * Actions whose data is not available in kash (helptopic, hostname,
 * signal, user, group, service, ...) yield a best-effort or empty list.
 *
 * Exit status: 0 if at least one match emitted; 1 if no matches; 2 on
 * usage error.
 */
internal suspend fun Interpreter.runCompgenIntrinsic(
    args: List<String>,
    stdio: Stdio,
): Int {
    val parsed =
        parseCompleteArgs(
            args = args,
            stdio = stdio,
            progName = "compgen",
            usage = COMPGEN_USAGE,
            allowDashV = true,
            allowDashR = false,
            allowDashDEI = false,
            diagPrefix = shellDiagPrefix(),
        ) ?: return 2

    val operand = parsed.names.firstOrNull() ?: ""

    // Enumerate per action.
    val raw = mutableListOf<String>()
    for (act in parsed.actions) raw += enumerate(act)

    parsed.wordlist?.let { wl ->
        // Bash splits -W on $IFS (default: space, tab, newline). Each
        // IFS char is a separator; consecutive separators produce no
        // empty fields per POSIX §2.6.5.
        raw += splitOnIfs(wl, env["IFS"] ?: " \t\n")
    }

    parsed.glob?.let { g ->
        raw += globExpand(g)
    }

    parsed.function?.let { fnName ->
        // -F func: programmable completion. Set COMP_WORDS / COMP_CWORD
        // / COMP_LINE / COMP_POINT, invoke `func cmd cur prev`, then
        // read COMPREPLY back. Bash man page section "Programmable
        // Completion".
        val fn = functions[fnName]
        if (fn == null) {
            stdio.stderr.writeUtf8(
                "${shellDiagPrefix()}compgen: $fnName: function not found\n",
            )
            return 1
        }
        raw += invokeCompletionFunction(fn, fnName, operand, parsed, stdio)
    }

    parsed.command?.let { cmd ->
        // -C cmd: run cmd in a subshell, treat its stdout lines as
        // completions. Bash man page: "If a command is specified with
        // the -C option, it is executed in an environment equivalent
        // to command substitution."
        raw += runCompletionCommand(cmd, operand, parsed, stdio)
    }

    // Filter by operand prefix (bash compgen semantics).
    var matches =
        raw
            .filter { it.startsWith(operand) }
            .distinct()

    // -X filterpat: REMOVE items matching the pattern (bash treats it as a
    // negative filter unless prefixed with `!`, which inverts the sense).
    parsed.filter?.let { pat ->
        matches = filterByPattern(matches, pat)
    }

    // -P / -S decoration.
    val out =
        matches.map { m ->
            (parsed.prefix ?: "") + m + (parsed.suffix ?: "")
        }

    if (parsed.arrayVarName != null) {
        // Store in a bash indexed array — kash already has this layer
        // (`Interpreter.indexedArrays`). Element [0] is the scalar value;
        // `${name[@]}` expands to every element separately.
        if (out.isEmpty()) return 1
        val arr = mutableMapOf<Int, String>()
        for ((idx, value) in out.withIndex()) arr[idx] = value
        indexedArrays[parsed.arrayVarName] = arr
        // Bash also sets the scalar value to the first element so
        // `$name` (no subscript) round-trips. Keep env in sync.
        env[parsed.arrayVarName] = out[0]
        return 0
    }

    if (out.isEmpty()) return 1
    for (m in out) {
        stdio.stdout.writeUtf8(m)
        stdio.stdout.writeUtf8("\n")
    }
    return 0
}

/**
 * Split [s] into fields using [ifs] (each char a separator). Per POSIX
 * §2.6.5, consecutive whitespace IFS characters collapse into a single
 * separator; consecutive non-whitespace IFS characters each delimit
 * an empty field. compgen's -W splitting needs the same semantics.
 */
private fun splitOnIfs(
    s: String,
    ifs: String,
): List<String> {
    if (s.isEmpty()) return emptyList()
    if (ifs.isEmpty()) return listOf(s)
    val whitespaceIfs = ifs.filter { it == ' ' || it == '\t' || it == '\n' }
    val out = mutableListOf<String>()
    val current = StringBuilder()
    var lastWasNonWsSep = false
    var sawAnyContent = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c in ifs && c in whitespaceIfs -> {
                if (sawAnyContent && current.isNotEmpty()) {
                    out += current.toString()
                    current.clear()
                }
                lastWasNonWsSep = false
            }

            c in ifs -> {
                // Non-whitespace IFS separator: always delimits, even
                // back-to-back (each produces an empty field between).
                out += current.toString()
                current.clear()
                lastWasNonWsSep = true
                sawAnyContent = true
            }

            else -> {
                current.append(c)
                sawAnyContent = true
            }
        }
        i++
    }
    if (current.isNotEmpty() || lastWasNonWsSep) out += current.toString()
    return out
}

/**
 * Invoke a shell function as a programmable-completion callback. Sets
 * COMP_WORDS / COMP_CWORD / COMP_LINE / COMP_POINT bash specials and
 * positional args `$1`=cmd `$2`=cur `$3`=prev, runs the function via
 * [runFunctionCall], then reads COMPREPLY back as the candidate list.
 *
 * Reference: bash man page section "Programmable Completion".
 */
private suspend fun Interpreter.invokeCompletionFunction(
    fn: com.accucodeai.kash.ast.FunctionDef,
    fnName: String,
    operand: String,
    parsed: ParsedCompleteArgs,
    stdio: Interpreter.Stdio,
): List<String> {
    // bash compgen passes the cmd-being-completed via the `-F` callback
    // args. The pattern is: `$1` = command, `$2` = current word being
    // completed, `$3` = word before $2. compgen invoked directly outside
    // readline doesn't have a real cmd/prev context; bash's man page
    // documents only one word arg ("the word currently being completed").
    // We accept that single operand as `$2`/cur; `$1`/cmd defaults to
    // an empty string and `$3`/prev to empty too. Callers that need
    // richer context should set COMP_WORDS themselves before calling.
    val cmd = ""
    val cur = operand
    val prev = ""
    // Set COMP_* shell vars. Use varTable so types are correct.
    val priorCompWords = indexedArrays["COMP_WORDS"]
    val priorCompCword = env["COMP_CWORD"]
    val priorCompLine = env["COMP_LINE"]
    val priorCompPoint = env["COMP_POINT"]
    indexedArrays["COMP_WORDS"] = mutableMapOf(0 to cmd, 1 to cur)
    env["COMP_CWORD"] = "1"
    val compLine = "$cmd $cur"
    env["COMP_LINE"] = compLine
    env["COMP_POINT"] = compLine.length.toString()
    // Clear COMPREPLY before invocation so we read only what the
    // callback wrote.
    indexedArrays.remove("COMPREPLY")
    try {
        runFunctionCall(fnName, fn, listOf(cmd, cur, prev), stdio)
    } finally {
        // Restore the COMP_* specials.
        if (priorCompWords != null) {
            indexedArrays["COMP_WORDS"] = priorCompWords
        } else {
            indexedArrays.remove("COMP_WORDS")
        }
        if (priorCompCword != null) env["COMP_CWORD"] = priorCompCword else env.remove("COMP_CWORD")
        if (priorCompLine != null) env["COMP_LINE"] = priorCompLine else env.remove("COMP_LINE")
        if (priorCompPoint != null) env["COMP_POINT"] = priorCompPoint else env.remove("COMP_POINT")
    }
    val reply = indexedArrays["COMPREPLY"] ?: return emptyList()
    return reply.entries.sortedBy { it.key }.map { it.value }
}

/**
 * Run [cmd] in a subshell and treat its stdout as a newline-separated
 * list of completion candidates. Equivalent to bash's `mapfile -t <
 * <(cmd)` shape. Reference: bash man page section "complete -C cmd".
 */
private suspend fun Interpreter.runCompletionCommand(
    cmd: String,
    operand: String,
    parsed: ParsedCompleteArgs,
    stdio: Interpreter.Stdio,
): List<String> {
    val compLine = "${parsed.names.firstOrNull() ?: ""} $operand"
    val priorCompLine = env["COMP_LINE"]
    val priorCompPoint = env["COMP_POINT"]
    env["COMP_LINE"] = compLine
    env["COMP_POINT"] = compLine.length.toString()
    val out = StringBuilder()
    try {
        val buf = kotlinx.io.Buffer()
        val sink = buf.asSuspendSink()
        // Run the command through the shell. The simplest path is the
        // existing [runShellScript] entry, which spawns a fork subshell.
        runShellScript(
            script = cmd,
            stdin = com.accucodeai.kash.api.io.EmptySuspendSource,
            stdout = sink,
            stderr = stdio.stderr,
            scriptName = "compgen",
            isCLine = true,
        )
        out.append(buf.readString())
    } finally {
        if (priorCompLine != null) env["COMP_LINE"] = priorCompLine else env.remove("COMP_LINE")
        if (priorCompPoint != null) env["COMP_POINT"] = priorCompPoint else env.remove("COMP_POINT")
    }
    return out.toString().split('\n').filter { it.isNotEmpty() }
}

/**
 * Enumerate candidates for [action]. Returns sorted, de-duplicated names.
 */
private fun Interpreter.enumerate(action: CompleteAction): List<String> =
    when (action) {
        CompleteAction.Alias -> {
            aliases.keys.sorted()
        }

        CompleteAction.Builtin -> {
            BASH_BUILTIN_NAMES
        }

        CompleteAction.Function -> {
            functions.keys.sorted()
        }

        CompleteAction.Variable -> {
            env.keys.sorted()
        }

        CompleteAction.Export -> {
            env.keys.sorted()
        }

        // Bash's `compgen -k` walks the reserved-word table in declaration
        // order, not sorted; we mirror upstream for conformance fidelity.
        CompleteAction.Keyword -> {
            BASH_KEYWORDS
        }

        CompleteAction.Command -> {
            commandSourceUnion()
        }

        CompleteAction.File -> {
            listEntries(cwd, dirsOnly = false)
        }

        CompleteAction.Directory -> {
            listEntries(cwd, dirsOnly = true)
        }

        CompleteAction.HelpTopic -> {
            BASH_HELPTOPICS
        }

        CompleteAction.Hostname -> {
            emptyList()
        }

        CompleteAction.Signal -> {
            SIGNAL_NAMES
        }

        CompleteAction.SetOpt -> {
            SETOPTS
        }

        CompleteAction.Shopt -> {
            KNOWN_SHOPT_NAMES
        }

        CompleteAction.Stopped, CompleteAction.Running, CompleteAction.Job -> {
            emptyList()
        }

        CompleteAction.Service -> {
            emptyList()
        }

        CompleteAction.User, CompleteAction.Group -> {
            emptyList()
        }

        CompleteAction.Binding -> {
            emptyList()
        }

        CompleteAction.Enabled -> {
            BASH_BUILTIN_NAMES.filter { it !in disabledIntrinsics }
        }

        // bash list — disabled-via-`enable -n` names.
        CompleteAction.Disabled -> {
            disabledIntrinsics.sorted()
        }

        CompleteAction.ArrayVar -> {
            emptyList()
        } // we don't track array-typed vars yet
    }

private fun Interpreter.commandSourceUnion(): List<String> {
    val names = mutableSetOf<String>()
    names += IntrinsicCatalog.names
    names += registry.names()
    names += functions.keys
    names += aliases.keys
    for (dir in splitPath(env["PATH"])) {
        val resolved = if (dir.isEmpty()) cwd else dir
        val entries =
            try {
                fs.list(resolved)
            } catch (_: Throwable) {
                continue
            }
        for (entry in entries) {
            val full = if (resolved == "/") "/$entry" else "$resolved/$entry"
            val ok =
                try {
                    fs.exists(full) && !fs.isDirectory(full)
                } catch (_: Throwable) {
                    false
                }
            if (ok) names += entry
        }
    }
    return names.sorted()
}

private fun Interpreter.listEntries(
    dir: String,
    dirsOnly: Boolean,
): List<String> {
    val entries =
        try {
            fs.list(dir)
        } catch (_: Throwable) {
            return emptyList()
        }
    val out = mutableListOf<String>()
    for (entry in entries) {
        val full = if (dir == "/") "/$entry" else "$dir/$entry"
        val isDir =
            try {
                fs.isDirectory(full)
            } catch (_: Throwable) {
                false
            }
        if (dirsOnly && !isDir) continue
        out += entry
    }
    return out.sorted()
}

/**
 * -G globpat: one-level relative glob expansion. We don't yet replicate
 * bash's full multi-segment glob walk here; treat the pattern as a single
 * basename against [Interpreter.cwd] entries. Multi-segment patterns and
 * CDPATH integration are deferred.
 */
private fun Interpreter.globExpand(pattern: String): List<String> {
    // If the pattern contains a slash, bail to keep the simple
    // single-segment matcher honest. Multi-segment globbing is a polish
    // item; until then -G on `dir/*.kt` produces no matches.
    if ('/' in pattern) return emptyList()
    val entries =
        try {
            fs.list(cwd)
        } catch (_: Throwable) {
            return emptyList()
        }
    return entries.filter { matchGlob(pattern, it) }.sorted()
}

/**
 * -X filterpat: bash semantics — drop candidates that match [pattern].
 * A leading `!` inverts the sense (keep matches, drop non-matches).
 * Pattern uses shell-glob syntax.
 */
private fun filterByPattern(
    candidates: List<String>,
    pattern: String,
): List<String> {
    val negate = pattern.startsWith("!")
    val pat = if (negate) pattern.substring(1) else pattern
    return candidates.filter { s ->
        val m = matchGlob(pat, s)
        if (negate) m else !m
    }
}

/**
 * Bash reserved words in `compgen -k` iteration order. We keep this here
 * (not in `BashCompatLists.kt`) because the parser already has its own
 * RESERVED_WORDS set in [com.accucodeai.kash.parser]; this constant is
 * specifically about *iteration order* for compgen.
 */
private val BASH_KEYWORDS: List<String> =
    listOf(
        "if",
        "then",
        "else",
        "elif",
        "fi",
        "case",
        "esac",
        "for",
        "select",
        "while",
        "until",
        "do",
        "done",
        "in",
        "function",
        "time",
        "{",
        "}",
        "!",
        "[[",
        "]]",
        "coproc",
    )

// Lists used for actions whose true source we don't model in full but
// where having *something* helpful keeps `.bashrc` completion scripts
// from breaking. Values come from POSIX / bash documentation.

private val SIGNAL_NAMES: List<String> =
    listOf(
        "ABRT",
        "ALRM",
        "BUS",
        "CHLD",
        "CONT",
        "FPE",
        "HUP",
        "ILL",
        "INT",
        "IO",
        "IOT",
        "KILL",
        "PIPE",
        "POLL",
        "PROF",
        "PWR",
        "QUIT",
        "SEGV",
        "STKFLT",
        "STOP",
        "SYS",
        "TERM",
        "TRAP",
        "TSTP",
        "TTIN",
        "TTOU",
        "URG",
        "USR1",
        "USR2",
        "VTALRM",
        "WINCH",
        "XCPU",
        "XFSZ",
        "SIGABRT",
        "SIGALRM",
        "SIGBUS",
        "SIGCHLD",
        "SIGCONT",
        "SIGFPE",
        "SIGHUP",
        "SIGILL",
        "SIGINT",
        "SIGIO",
        "SIGIOT",
        "SIGKILL",
        "SIGPIPE",
        "SIGPOLL",
        "SIGPROF",
        "SIGPWR",
        "SIGQUIT",
        "SIGSEGV",
        "SIGSTKFLT",
        "SIGSTOP",
        "SIGSYS",
        "SIGTERM",
        "SIGTRAP",
        "SIGTSTP",
        "SIGTTIN",
        "SIGTTOU",
        "SIGURG",
        "SIGUSR1",
        "SIGUSR2",
        "SIGVTALRM",
        "SIGWINCH",
        "SIGXCPU",
        "SIGXFSZ",
    ).sorted()

private val SETOPTS: List<String> =
    listOf(
        "allexport",
        "braceexpand",
        "emacs",
        "errexit",
        "errtrace",
        "functrace",
        "hashall",
        "histexpand",
        "history",
        "ignoreeof",
        "interactive-comments",
        "keyword",
        "monitor",
        "noclobber",
        "noexec",
        "noglob",
        "nolog",
        "notify",
        "nounset",
        "onecmd",
        "physical",
        "pipefail",
        "posix",
        "privileged",
        "verbose",
        "vi",
        "xtrace",
    )

// The bash 5.x shopt list lives in `completion/BashCompatLists.kt`
// (KNOWN_SHOPT_NAMES) as a single source of truth, also used to seed
// Interpreter.shoptOptions.
