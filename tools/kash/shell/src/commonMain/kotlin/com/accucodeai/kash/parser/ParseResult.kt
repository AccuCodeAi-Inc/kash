package com.accucodeai.kash.parser

import com.accucodeai.kash.ast.Script

// Outcome of parsing a single chunk of shell source.
//
//  - [Ok] — a complete, well-formed script.
//  - [Incomplete] — the source ended in the middle of a construct (open quote,
//    unfinished `if`, pending heredoc, …). The REPL uses this signal to print
//    a continuation prompt instead of treating it as a syntax error.
//  - [Error] — a real syntax error that more input cannot fix.

/** A diagnostic notice produced during parsing — bash's "warning: …" messages. */
public data class ParseWarning(
    val line: Int,
    val message: String,
    /**
     * When true, the interpreter emits the warning with no `line N:` prefix
     * — for messages that already carry their own context (e.g. bash's
     * `command substitution: line N: …` which it prefixes only with the
     * script name).
     */
    val suppressLinePrefix: Boolean = false,
)

public sealed interface ParseResult {
    public data class Ok(
        val script: Script,
        val warnings: List<ParseWarning> = emptyList(),
    ) : ParseResult

    public data class Incomplete(
        val reason: String,
        val line: Int,
    ) : ParseResult

    public data class Error(
        val message: String,
        val line: Int,
        val column: Int,
        /**
         * Text of the token that triggered the failure, if ANTLR could
         * identify one. Used by `sh -c` / script load diagnostics to format
         * `syntax error near unexpected token '<tok>'` per bash convention
         * (POSIX §2.8.1 mandates a diagnostic; format is unspecified).
         */
        val offendingToken: String? = null,
    ) : ParseResult
}
