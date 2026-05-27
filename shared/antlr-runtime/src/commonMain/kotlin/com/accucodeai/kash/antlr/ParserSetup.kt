package com.accucodeai.kash.antlr

import org.antlr.v4.kotlinruntime.BailErrorStrategy
import org.antlr.v4.kotlinruntime.Lexer
import org.antlr.v4.kotlinruntime.Parser
import org.antlr.v4.kotlinruntime.atn.PredictionMode

/**
 * Replace ANTLR's default `ConsoleErrorListener` with [ThrowingErrorListener]
 * so a syntax error reaches the caller instead of silently going to stderr.
 * Safe to call on any [org.antlr.v4.kotlinruntime.Recognizer] subclass.
 */
public fun configureForFailFast(lexer: Lexer) {
    lexer.removeErrorListeners()
    lexer.addErrorListener(ThrowingErrorListener)
}

/**
 * Parser flavor of [configureForFailFast]: removes the default
 * `ConsoleErrorListener`, installs [BailErrorStrategy] (so the first error
 * throws a `ParseCancellationException` instead of being silently recovered
 * from), and starts the parser in SLL prediction mode — the fast path of
 * the two-stage SLL→LL strategy in [twoStageParse].
 *
 * Intentionally does *not* install [ThrowingErrorListener]. ANTLR's generated
 * parser code calls `errorHandler.reportError(this, re)` *before*
 * `errorHandler.recover(this, re)`, and the default `reportError`
 * implementation (which `BailErrorStrategy` does not override) dispatches to
 * the error listeners. If [ThrowingErrorListener] is on the parser, it
 * throws an [AntlrSyntaxException] with only the listener-provided message
 * text — shadowing the [org.antlr.v4.kotlinruntime.misc.ParseCancellationException]
 * that `BailErrorStrategy` is about to throw with the original
 * [org.antlr.v4.kotlinruntime.RecognitionException] (and its `offendingToken`)
 * as the cause. Downstream diagnostics that need the offending token text
 * would then lose it.
 *
 * Lexers have no equivalent of `errorHandler`, so [configureForFailFast]
 * for `Lexer` *does* install the listener — that's the only signal path
 * for lexer-side errors.
 */
public fun configureForFailFast(parser: Parser) {
    parser.removeErrorListeners()
    parser.errorHandler = BailErrorStrategy()
    parser.interpreter.predictionMode = PredictionMode.SLL
}
