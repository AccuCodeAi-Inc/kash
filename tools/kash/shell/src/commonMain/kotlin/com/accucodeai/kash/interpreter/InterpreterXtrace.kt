package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.io.writeUtf8
import com.accucodeai.kash.parser.Parser

/**
 * `set -x` (xtrace) emit helpers — one central choke point so the
 * dispatcher's many command-shape branches don't each carry their own
 * stderr-write logic. Bash emits a `<PS4><reconstructed-command>\n` line
 * to stderr immediately BEFORE running each command; we mirror that.
 *
 * PS4 semantics (bash): the variable's value is parameter- and arith-
 * expanded (no cmdsub — that would recurse). The *first* character of
 * the expanded value is repeated `BASH_SUBSHELL + 1` times to mark
 * subshell nesting depth (so default `"+ "` becomes `"+ "` at top
 * level, `"++ "` inside one subshell, `"+++ "` inside two, etc.).
 * Implemented here by expanding `env["PS4"]` through [Word]-style
 * parameter expansion via the runtime [Expander], then prepending
 * extra copies of the first char.
 *
 * Output target: by default stderr. If `BASH_XTRACEFD` is set to a
 * valid open fd in [process.fdTable], xtrace lines write to that fd's
 * sink instead. Bash's same redirection trick lets scripts split
 * xtrace output from real stderr.
 *
 * Word quoting: bash xtrace quotes words containing shell-significant
 * characters (whitespace, `$`, `'`, etc.) — see [xtraceQuote].
 */
internal suspend fun Interpreter.emitXtrace(
    rendered: String,
    sink: com.accucodeai.kash.api.io.SuspendSink? = null,
) {
    if (!xtrace) return
    val ps4 = expandPs4Prefix()
    // BASH_XTRACEFD wins over the caller-supplied stderr — that's the
    // whole point of the variable, scripts use it to split xtrace
    // diagnostics from real stderr. Caller's [sink] is the fallback
    // when BASH_XTRACEFD isn't pointed at a valid open fd; then we
    // walk down to [errSink] if no caller sink either.
    val out = redirectedXtraceSink() ?: sink ?: errSink
    out.writeUtf8(ps4 + rendered + "\n")
}

/**
 * Compute the PS4 prefix with the subshell-depth `+` repetition applied.
 * Defaults to `"+ "` when unset; expansion failures are silently
 * tolerated (we fall back to the raw scalar) since xtrace is a
 * diagnostic and shouldn't fail-load.
 */
private suspend fun Interpreter.expandPs4Prefix(): String {
    val raw = env["PS4"] ?: "+ "
    if (raw.isEmpty()) return raw
    val expanded =
        try {
            // Bash performs parameter / arith / cmdsub expansion on PS4 by
            // treating the value as a double-quoted string. Easiest way to
            // get the right tokenisation is to parse a throwaway
            // `"<raw>"` assignment and reuse the expander.
            val ast = Parser("__ps4=\"$raw\"", aliasResolver, posixModeRuntime).parseScript()
            val stmt = ast.statements.firstOrNull()
            val pipeline = stmt?.pipelines?.firstOrNull()
            val simple = pipeline?.commands?.firstOrNull() as? com.accucodeai.kash.ast.SimpleCommand
            val assign = simple?.assignments?.firstOrNull() as? com.accucodeai.kash.ast.InlineAssignment.Scalar
            if (assign != null) expandAssignmentValue(assign.value) else raw
        } catch (_: Throwable) {
            raw
        }
    if (expanded.isEmpty()) return expanded
    // Bash's PS4 first-character replication: the first char is repeated
    // `indirection_level` times to indicate the script's depth of
    // indirection. Indirection bumps come from sourced scripts, `eval`,
    // and (per bash trace_command_internal_func) running inside a trap
    // handler. Pipeline stages and cmdsub forks render single-`+`.
    val depth = indirectionLevel()
    if (depth <= 1) return expanded
    val first = expanded[0]
    return first.toString().repeat(depth) + expanded.substring(1)
}

/**
 * Bash's `indirection_level` — number of nested contexts that contribute
 * one extra PS4-first-char to the xtrace prefix. Counts:
 *  - Sourced-script frames (`sourcingDepth`).
 *  - Active trap handler (one extra level while [inTrapHandler]).
 *
 * Pipeline-stage and cmdsub-fork contexts are NOT counted — bash uses
 * a flat `+` in those, matching the test corpus (trap3.sub, etc.).
 */
private fun Interpreter.indirectionLevel(): Int {
    var depth = 1
    depth += sourcingDepth
    if (inTrapHandler) depth++
    return depth
}

/**
 * Resolve a non-stderr xtrace sink iff `BASH_XTRACEFD` names a valid
 * writable fd. Returns `null` when unset / non-numeric / fd missing /
 * fd not writable, letting [emitXtrace] fall back to its passed sink.
 */
private fun Interpreter.redirectedXtraceSink(): com.accucodeai.kash.api.io.SuspendSink? {
    val raw = env["BASH_XTRACEFD"] ?: return null
    val fd = raw.toIntOrNull() ?: return null
    val entry = process.fdTable[fd] ?: return null
    return entry.ofd.sink
}

/**
 * Format a simple command (inline-env prefix + args) for xtrace.
 * Matches bash: `+ NAME=val NAME2=val2 cmd arg1 arg2` with no quoting
 * applied to "clean" words. Quoted-empty stays as `''`.
 */
internal fun renderSimpleForXtrace(
    inlineEnv: Map<String, String>,
    args: List<String>,
): String =
    buildString {
        var first = true
        for ((name, value) in inlineEnv) {
            if (!first) append(' ')
            append(name).append('=').append(xtraceQuote(value))
            first = false
        }
        for (a in args) {
            if (!first) append(' ')
            append(xtraceQuote(a))
            first = false
        }
    }

// Bash xtrace quoting: words containing whitespace, `$`, `'`, `"`,
// `\`, `*`, `?`, `[`, `;`, `<`, `>`, `|`, `&`, `(`, `)`, `{`, `}`,
// backtick, tab, or newline get single-quoted; otherwise emit raw.
// Empty string renders as `''`.

/**
 * Bash xtrace word-quoting. Three regimes:
 *
 *   - empty → `''`
 *   - any non-printable / control character → `$'…'` (ANSI-C) with
 *     `\t`, `\n`, `\r`, `\b`, `\f`, `\a`, `\v`, `\\`, `\'` escapes for
 *     the well-known ones and `\xHH` for the rest. Bash uses this
 *     form so xtrace stays one-line even when values contain `\t`/`\n`.
 *   - any shell-significant printable (whitespace, `$`, `'`, etc.) →
 *     `'…'` single-quoted with `'\''` escapes for embedded single
 *     quotes.
 *   - otherwise emit raw.
 */
internal fun xtraceQuote(s: String): String {
    if (s.isEmpty()) return "''"
    val hasCtrl = s.any { it.code < 0x20 || it.code == 0x7f }
    if (hasCtrl) return ansicQuote(s)
    val needsQuote = s.any { c -> c.isWhitespace() || c in "$'\"\\*?[];<>|&(){}`" }
    if (!needsQuote) return s
    val sb = StringBuilder("'")
    for (c in s) {
        if (c == '\'') sb.append("'\\''") else sb.append(c)
    }
    sb.append('\'')
    return sb.toString()
}

/**
 * ANSI-C `$'…'` quoting: render control characters with their canonical
 * backslash escapes (`\t`, `\n`, …) and fall back to `\xHH` otherwise.
 * Used by [xtraceQuote] for any value containing a control character,
 * and by [com.accucodeai.kash.interpreter.formatDeclareElement] for
 * array element rendering in `declare -p` output.
 */
internal fun ansicQuote(s: String): String {
    val sb = StringBuilder("$'")
    for (c in s) {
        when {
            c == '\\' -> {
                sb.append("\\\\")
            }

            c == '\'' -> {
                sb.append("\\'")
            }

            c == '\n' -> {
                sb.append("\\n")
            }

            c == '\t' -> {
                sb.append("\\t")
            }

            c == '\r' -> {
                sb.append("\\r")
            }

            c == '\b' -> {
                sb.append("\\b")
            }

            c.code == 0x0C -> {
                sb.append("\\f")
            }

            c.code == 0x07 -> {
                sb.append("\\a")
            }

            c.code == 0x0B -> {
                sb.append("\\v")
            }

            c.code < 0x20 || c.code == 0x7f -> {
                // bash emits 3-digit octal (`\001`), not hex, for arbitrary
                // control chars in `declare -p` / `${var@A}` output.
                sb.append("\\").append(c.code.toString(8).padStart(3, '0'))
            }

            else -> {
                sb.append(c)
            }
        }
    }
    sb.append('\'')
    return sb.toString()
}
