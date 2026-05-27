package com.accucodeai.kash.interpreter

import com.accucodeai.kash.api.user.UserDatabase
import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart
import com.accucodeai.kash.fs.FileSystem
import com.accucodeai.kash.parser.isShellIdentifier

/**
 * Word expansion.
 *
 * Pipeline (bash-style):
 *   0. brace expansion (`{a,b,c}`, `{1..5}`) — purely textual pre-pass,
 *      driven from [Interpreter.flatExpandArgs] before any per-word stage.
 *   1. parameter expansion (`$VAR`, `${VAR}`, `$?`, `$@`, `$#`, `$1`..)
 *   2. word splitting on IFS, for the *unquoted* results of step 1
 *   3. pathname expansion (globbing): `*`, `?`, `[...]`
 *
 * We carry per-character quote bits through splitting so globbing only acts
 * on the unquoted regions — matching real bash behavior.
 */
internal class Expander(
    internal val env: MutableMap<String, String>,
    internal val positional: List<String>,
    internal val lastExit: Int,
    internal val fs: FileSystem,
    internal val cwd: String,
    internal val scriptName: String = "kash",
    /** Most-recent background job id; backs `$!`. Null before any `&`. */
    internal val lastBgPid: Int? = null,
    /**
     * `$$` — the shell's pid (frozen at session startup; sticky across
     * subshell forks per POSIX). 0 means "no associated process" — used
     * only by FS-only test scaffolds.
     */
    internal val shellPid: Int = 0,
    /**
     * `$BASHPID` — the current process's pid, distinct in every
     * subshell. 0 means "no associated process".
     */
    internal val bashPid: Int = 0,
    /**
     * `$PPID` — the shell process's parent pid (sticky across subshells
     * per bash semantics). 0 when the shell has no recorded parent.
     */
    internal val shellPpid: Int = 0,
    /** Indexed-array storage (read-only view from interpreter). */
    internal val indexedArrays: Map<String, Map<Int, String>> = emptyMap(),
    /** Associative-array storage (read-only view from interpreter). */
    internal val assocArrays: Map<String, Map<String, String>> = emptyMap(),
    /** Source line of the command currently in flight; backs `$LINENO`. */
    internal val currentLine: Int = 0,
    /** Drives `~user` tilde-prefix lookups. */
    internal val userDb: UserDatabase = UserDatabase.Default,
    /**
     * POSIX-mode at the moment of expansion. Suppresses bash's
     * "assignment-shape-after-the-fact" tilde rule, where words like
     * `foo=bar:~` on a command line get tilde-after-`:` expansion even
     * though they aren't actual assignments.
     */
    internal val posixMode: Boolean = false,
    /**
     * Backs `$RANDOM`. Returns 0..32767 per bash. Injectable so embedders
     * (tests, replay) can pin a deterministic seed; default is the platform
     * RNG.
     */
    internal val randomSource: () -> Int = { kotlin.random.Random.nextInt(0, 32768) },
    /**
     * Backs `$SECONDS` — integer seconds elapsed since shell start. The
     * caller (Interpreter) supplies a closure over its injected
     * [com.accucodeai.kash.api.clock.ShellClock] so conformance tests share
     * the same time domain as `sleep` / virtual coroutine time.
     */
    internal val shellSecondsElapsed: () -> Long = { 0L },
    /**
     * Backs `$EPOCHSECONDS` (whole seconds since the Unix epoch) and
     * `$EPOCHREALTIME` (seconds.microseconds). Adapter over the injected
     * [com.accucodeai.kash.api.clock.ShellClock] so a single time source
     * pins both wall-epoch reads and `$SECONDS`.
     */
    internal val wallClock: kotlin.time.Clock = kotlin.time.Clock.System,
    /**
     * `shopt` option state — passed through from [Interpreter.shoptOptions].
     * Read-only view; the Expander never writes back. Wired-through entries:
     * `globskipdots`, `patsub_replacement`, `bash_source_fullpath`,
     * `array_expand_once`. Unrecognized keys map to false (off).
     */
    internal val shoptOptions: Map<String, Boolean> = emptyMap(),
    /**
     * Backs `$-` — the string of single-letter option flags currently in
     * effect on the shell (e.g. `ehB`). Closure over [Interpreter]'s
     * mutable flag fields so the value is read at each expansion.
     */
    internal val currentDashFlags: () -> String = { "hB" },
    /**
     * `set -f` / `set -o noglob`. Sampled per-expansion (closure) so a
     * mid-script `set -f` affects subsequent expansions without
     * reconstructing the Expander. POSIX §2.14.1.
     */
    internal val noglob: () -> Boolean = { false },
    /**
     * Whether a line editor (emacs or vi) is active — affects `${var@P}`
     * prompt decode, which keeps `\[`/`\]` as `\001`/`\002` markers when
     * an editor is on, but strips them when no editor is active (the
     * markers are only meaningful to readline).
     */
    internal val lineEditorActive: () -> Boolean = { false },
    /**
     * `${!name}` over a [VarAttr.NameRef] binding returns the target
     * NAME (the nameref's raw scalar), not the target's value. This
     * closure exposes that lookup without giving the Expander a full
     * VarTable dependency. Returns `null` when [name] isn't a nameref
     * (or isn't bound), letting the caller fall back to the normal
     * follow-the-chain lookup.
     */
    internal val nameRefRawTarget: (String) -> String? = { null },
    /**
     * Mutable buffer that collects non-fatal expansion diagnostics in
     * source order. Used for recoverable errors that print but don't
     * terminate the script — e.g. `${!badname}: invalid variable name`,
     * `${$N=val}: cannot assign in this way`. The wrapping Interpreter
     * flushes this list to stderr (with `script: line N:` prefix) after
     * each Expander invocation. Default is a throwaway list so direct
     * Expander instantiations in tests don't need to wire it.
     */
    internal val pendingDiagnostics: MutableList<String> = mutableListOf(),
    /**
     * Set true by recoverable expansion errors that should *also* abort
     * the enclosing simple command — e.g. a substring-offset arith
     * failure or "substring expression < 0". The wrapping Interpreter
     * reads this after each Expander invocation and pipes it into the
     * existing `arithSubstFailedInWordExpansion` flag so the simple-
     * command driver skips the (otherwise-running) command.
     */
    internal val commandAbortFlag: BooleanArray = BooleanArray(1),
    /**
     * Set by [subscriptValue] / [subscriptIsSet] when a negative array
     * subscript translates out of range (translated index < 0). Holds
     * the raw subscript text so callers can format the diagnostic two
     * different ways: bash emits `name: bad array subscript` for the
     * regular `${A[-N]}` read, but `[-N]: bad array subscript` (and
     * aborts the simple command) for the length form `${#A[-N]}`.
     * Slot 0 = the raw subscript, slot 1 = "1" when set, "" otherwise
     * (using a tiny mutable list so callers can clear after consuming).
     */
    internal val lastBadSubscript: MutableList<String> = mutableListOf(),
    /**
     * Callback for `${name[sub]=value}` / `${name[sub]:=value}` parameter
     * expansion that needs to assign to an *element* (not the scalar).
     * The interpreter wires this into `setIndexedElement` / `setAssocElement`.
     * Default is a no-op so direct Expander instantiations in tests don't
     * need to wire it.
     */
    internal val assignArrayElement: (name: String, sub: String, value: String) -> String =
        { _, _, v -> v },
    /**
     * Variable-aware parameter-transform hook for `${name@OP}` operators
     * that need access to the variable's storage and attribute set —
     * `A` (declare-recreate line), `a` (attr flag string), `K` / `k`
     * (key-value dump for arrays). Returns `null` to fall through to the
     * value-only transform path in [transformOp]. Other operators
     * (`Q`/`E`/`L`/`U`/`u`/`C`/`P`) are handled inline by
     * [transformOp] and don't consult this closure.
     */
    internal val parameterTransformOp: (op: String, name: String) -> String? = { _, _ -> null },
    /**
     * `set -u` / `set -o nounset` enforcement: when on, parameter
     * expansion of an unset variable (outside the default-value family
     * `-`/`+`/`=`/`?` and their `:` variants) throws [ParameterError]
     * with `unbound variable`. See [appendParameterExpansion].
     */
    internal val nounset: Boolean = false,
) {
    /**
     * Set to true while expanding the operand of `${var-WORD}` /
     * `${var=WORD}` / `${var:-WORD}` / `${var:=WORD}` / `${var:+WORD}` /
     * `${var+WORD}` (default-value family). An unquoted `$@` inside a
     * default-value operand collapses to one space-joined field when
     * IFS is non-null, but preserves per-positional boundaries when
     * IFS is null — the documented "inconsistent" bash rule that
     * posixexp4.sub pins. emitSpread reads this flag to choose between
     * the two.
     */
    internal var inDefaultValueOperand: Boolean = false

    /**
     * Set during [expandAssignmentValue] / `${var=...}` rhs expansion so
     * the per-element preserve-boundaries rule for unquoted `*`-spreads
     * with per-element ops under IFS=null doesn't fire — assignment
     * context collapses to a single IFS-joined scalar regardless of
     * splittable-fragment shape.
     */
    internal var inAssignmentRhs: Boolean = false

    /** Expand an argument: tilde → parameter expansion → splitting → globbing. */
    fun expandArg(word: Word): List<String> {
        val assignShape = !posixMode && isAssignmentShape(word)
        val tilded = applyTilde(word, assignmentContext = assignShape, argShape = assignShape)
        val groups = expandToFragments(tilded)
        val splits = splitOnIfs(groups)
        return splits.flatMap { glob(it) }
    }

    /**
     * Bash extends tilde expansion to words that *look like* an assignment
     * (a valid identifier head followed by `=` in the leading literal),
     * even when used as a regular argument. POSIX mode disables this.
     */
    private fun isAssignmentShape(word: Word): Boolean {
        val first = word.parts.firstOrNull() as? WordPart.Literal ?: return false
        val s = first.value
        val eq = s.indexOf('=')
        if (eq <= 0) return false
        return isShellIdentifier(s.substring(0, eq))
    }

    /** Expand without splitting/globbing — for assignments / redirection targets. */
    fun expandSingle(word: Word): String {
        val tilded = applyTilde(word, assignmentContext = false)
        val groups = expandToFragments(tilded)
        // `"$@"` produces one group per positional; in single-string contexts
        // (here-string, redirection target, assignment RHS) they join with
        // the first IFS char (default ' '), matching bash's documented
        // behavior for `"$@"` in non-argv positions.
        val sep = (env["IFS"] ?: " \t\n").firstOrNull()?.toString() ?: ""
        return groups.joinToString(sep) { group -> group.joinToString("") { it.text } }
    }

    /**
     * Expand without tilde / splitting / globbing — for heredoc bodies, where
     * parameter / command / arithmetic expansion run but tilde and pathname
     * expansion do not (POSIX §2.7.4).
     */
    fun expandSingleNoTilde(word: Word): String {
        val groups = expandToFragments(word)
        return groups.joinToString("") { group -> group.joinToString("") { it.text } }
    }

    /**
     * Pattern-mode expansion: same as [expandSingle] but characters from
     * source-level quoted spans (`\x`, `'x'`, `"x"`) are prefixed with `\`
     * so the glob matcher treats them literally. Used by `case` patterns,
     * `[[ x == pat ]]`, and `${var/pat/repl}`.
     */
    fun expandPattern(word: Word): String {
        val tilded = applyTilde(word, assignmentContext = false)
        val groups = expandToFragments(tilded)
        val sep = (env["IFS"] ?: " \t\n").firstOrNull()?.toString() ?: ""
        return groups.joinToString(sep) { group ->
            buildString {
                for (frag in group) {
                    if (frag.quoted) {
                        for (c in frag.text) {
                            append('\\')
                            append(c)
                        }
                    } else {
                        append(frag.text)
                    }
                }
            }
        }
    }

    /** Expand an assignment RHS — like [expandSingle] but tilde also expands after `:`. */
    fun expandAssignmentValue(word: Word): String {
        val tilded = applyTilde(word, assignmentContext = true)
        val saved = inAssignmentRhs
        inAssignmentRhs = true
        val groups =
            try {
                expandToFragments(tilded)
            } finally {
                inAssignmentRhs = saved
            }
        // Multi-element `$@` joins with a single space in an assignment
        // RHS regardless of IFS — bash's documented assignment-context
        // rule. `$*` already arrives as one IFS-joined group so the
        // separator is a no-op for it.
        return groups.joinToString(" ") { group -> group.joinToString("") { it.text } }
    }

    internal fun expandToFragments(word: Word): List<List<Fragment>> {
        val groups = mutableListOf<MutableList<Fragment>>(mutableListOf())
        for (part in word.parts) appendPart(groups, part, quoted = false)
        return groups
    }

    internal class ParameterError(
        val name: String,
        val msg: String,
    ) : RuntimeException("$name: ${msg.ifEmpty { "parameter null or not set" }}")

    internal fun lookup(name: String): String {
        val n = name.toIntOrNull()
        if (n != null) {
            // `$0` reflects BASH_ARGV0 when set — bash special variable that
            // dynamically aliases the script name. Reseed-only; if unset, fall
            // through to the static scriptName.
            if (n == 0) return env["BASH_ARGV0"]?.takeIf { it.isNotEmpty() } ?: scriptName
            return positional.getOrNull(n - 1) ?: ""
        }
        // Single-char specials: bash exposes these via `${!name}` indirect
        // lookup too, so the lookup path needs to know about them. The
        // bare-${} dispatch in [appendParameterExpansion] has its own
        // direct handling (it must, since the values are derived rather
        // than stored); this branch is what makes `${!?}` (= indirect of
        // `?`) produce `$<lastExit>` rather than `$<empty>`.
        when (name) {
            "?" -> return lastExit.toString()
            "#" -> return positional.size.toString()
            "!" -> return lastBgPid?.toString() ?: ""
            "$" -> return shellPid.toString()
            "-" -> return currentDashFlags()
        }
        // Bash dynamic specials (LINENO, RANDOM, BASHPID, PPID,
        // SECONDS, EPOCHSECONDS, EPOCHREALTIME, `$-`) are hook-backed
        // [Variable]s registered at session init — see
        // [Interpreter.registerSpecialVariables]. The env adapter
        // resolves them through the getter, so `env[name]` returns
        // the live dynamic value without a per-name special case.
        env[name]?.let { return it }
        // Bash extension: a bare `$NAME` reference where NAME is an
        // indexed array reads element 0 (so `$arr` ≡ `${arr[0]}`).
        // The scalar `env` value is intentionally cleared on array
        // assignment elsewhere, so the array fallback wins when both
        // somehow exist. Assoc arrays follow the same rule with key "0".
        indexedArrays[name]?.get(0)?.let { return it }
        assocArrays[name]?.get("0")?.let { return it }
        return ""
    }
}
