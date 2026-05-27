package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.Statement
import com.accucodeai.kash.parser.ParseException
import com.accucodeai.kash.parser.ParseWarning
import com.accucodeai.kash.parser.StatementStream

/**
 * Canonical statement-source abstraction for [Interpreter.runStreaming].
 * Single polymorphism point so one execution loop drives both
 * read-parse-execute and AST-replay paths without branching on the
 * input shape — the same shape POSIX shells take for their reader
 * loops (one loop, an input-getter abstraction underneath).
 *
 * **Do not delete this interface as "thin wrapper."** The two impls
 * genuinely diverge — see method docs — and the alternative is
 * duplicating the ~330-line [Interpreter.runStreaming] body. Bash takes
 * the same approach (one loop, getter redirection at the input layer).
 *
 * Two impls:
 *
 *  - [ScriptStatementSource] — eager, used by paths that already hold
 *    a parsed AST (function bodies, REPL chunks that already passed
 *    `Incomplete`-detection parse, deferred re-parses, snapshot
 *    restore). Has nothing to "stream" — it's an iterator over a
 *    [List].
 *  - [StreamStatementSource] — lazy, wraps a [StatementStream] which
 *    re-parses the source against the live interpreter state before
 *    each statement. `set -o posix` / `shopt extglob` / alias
 *    mutations mid-script affect subsequent statements' lex. This is
 *    the read-and-parse-per-statement variant POSIX shells use.
 *
 * Statements come in source order. [peekNextLine] returns the line of
 * the next statement (used by [Interpreter] to flush lex warnings up
 * to the boundary); [pendingError] surfaces an end-of-stream parse
 * error AFTER all valid statements have been yielded — bash's
 * "earlier commands run, then the syntax error fires" semantic.
 */
public interface StatementSource {
    /** Next statement, or null when the source is drained. */
    public fun next(): Statement?

    /** Line of the next statement, or [Int.MAX_VALUE] when drained. */
    public fun peekNextLine(): Int

    /** Parse error that terminated the stream, or null. */
    public fun pendingError(): ParseException?

    /**
     * Source line of the LAST char that contributed to the most-
     * recently-yielded statement. For a statement at line 181 whose
     * heredoc body extends to line 184, returns 184. Used by
     * [Interpreter.runStreaming] to flush lex warnings whose `line`
     * falls within the just-yielded statement's source span — bash's
     * "warning right before the affected command's output" semantic.
     * Returns 0 before the first [next] call or if no statement is
     * being tracked. */
    public fun lastConsumedLine(): Int = 0

    /**
     * Lexer-collected parse warnings available at the source's initial
     * scan. Called once by [Interpreter.runStreaming] before the first
     * statement so warnings get interleaved with execution via
     * `flushLexWarningsBefore(nextLine)`. Returning an empty list is
     * always safe.
     */
    public fun initialWarnings(): List<ParseWarning> = emptyList()

    /**
     * Drain recoverable mid-stream parse errors collected since the last
     * drain. Each error represents a discarded-and-continued statement —
     * bash's "syntax error on line N, run line N+1" semantic. Returns an
     * empty list when no recoverable errors have fired.
     *
     * The terminal error (stream gave up entirely) still surfaces via
     * [pendingError]; this list is only for mid-stream recovery.
     */
    public fun drainRecoveredErrors(): List<ParseException> = emptyList()
}

/** Eager source over an in-memory list of pre-parsed statements. */
public class ScriptStatementSource(
    private val statements: List<Statement>,
) : StatementSource {
    private var idx: Int = 0
    private var lastLine: Int = 0

    override fun next(): Statement? {
        val s = statements.getOrNull(idx) ?: return null
        idx++
        lastLine = s.line
        return s
    }

    override fun peekNextLine(): Int = statements.getOrNull(idx)?.line ?: Int.MAX_VALUE

    override fun lastConsumedLine(): Int = lastLine

    override fun pendingError(): ParseException? = null
}

/**
 * Lazy source that pulls from a [StatementStream]. V2 cursor-based
 * [StatementStream] re-parses the source against the LIVE interpreter
 * state on each [next] call — peeking ahead via a cached head would
 * freeze the parse against state-before-current-stmt-executes,
 * stranding subsequent statements that depend on the just-run stmt's
 * side effects (e.g. `alias bar=baz` defining bar before `foo bar`
 * parses). So [peekNextLine] does NOT pre-parse; it returns
 * [Int.MAX_VALUE], which causes [Interpreter.runStreaming] to
 * `flushLexWarningsBefore(MAX_VALUE)` — equivalent to flushing all
 * pending warnings. The cost: warnings are emitted slightly less
 * precisely interleaved with statement output, but the per-statement
 * parse semantics stay correct.
 */
public class StreamStatementSource(
    private val stream: StatementStream,
) : StatementSource {
    private var error: ParseException? = null

    // Single-shot cache for the FIRST yield only. [initialWarnings]
    // pre-fetches the first statement so warnings are available BEFORE
    // the first statement runs. Subsequent yields go straight through
    // [stream.next] with no caching — peeking later would freeze the
    // parse against state-before-just-executed-stmt and break alias
    // mutations / set -o posix mid-script (the V2 motivation).
    private var firstFetch: StatementStream.NextResult? = null
    private var firstFetchYielded: Boolean = false

    private fun primeFirstFetch() {
        if (firstFetch == null) firstFetch = stream.next()
    }

    override fun initialWarnings(): List<ParseWarning> {
        primeFirstFetch()
        return when (val f = firstFetch) {
            is StatementStream.NextResult.Statement -> f.warnings
            is StatementStream.NextResult.Error -> f.warnings
            else -> emptyList()
        }
    }

    private var lastConsumedLine: Int = 0
    private val recoveredErrors: MutableList<ParseException> = mutableListOf()

    override fun next(): Statement? {
        // Loop: if [StatementStream] recovered past a syntax error (cursor
        // advanced past the failing line), the next call yields the next
        // statement. Stash the error so the consumer can surface it via
        // [drainRecoveredErrors] before running the next statement.
        while (true) {
            val r =
                if (!firstFetchYielded && firstFetch != null) {
                    firstFetchYielded = true
                    firstFetch!!
                } else {
                    stream.next()
                }
            when (r) {
                is StatementStream.NextResult.Statement -> {
                    lastConsumedLine = r.consumedToLine
                    return r.statement
                }

                is StatementStream.NextResult.Error -> {
                    // If [StatementStream] kept going past the error (cursor
                    // moved on), collect it and loop. Otherwise stash as the
                    // terminal error and stop.
                    if (stream.done()) {
                        error = r.error
                        return null
                    }
                    recoveredErrors += r.error
                }

                is StatementStream.NextResult.Eof -> {
                    return null
                }
            }
        }
    }

    override fun drainRecoveredErrors(): List<ParseException> {
        if (recoveredErrors.isEmpty()) return emptyList()
        val r = recoveredErrors.toList()
        recoveredErrors.clear()
        return r
    }

    override fun lastConsumedLine(): Int = lastConsumedLine

    /** V2 doesn't pre-parse the next statement (doing so would freeze
     *  parse-time state against an out-of-date interpreter). Returning
     *  [Int.MAX_VALUE] causes the runStreaming loop's
     *  `flushLexWarningsBefore(nextLine)` to behave like flush-all
     *  — slightly less precise warning/output interleaving, but
     *  correct semantics. */
    override fun peekNextLine(): Int = Int.MAX_VALUE

    override fun pendingError(): ParseException? = error
}
