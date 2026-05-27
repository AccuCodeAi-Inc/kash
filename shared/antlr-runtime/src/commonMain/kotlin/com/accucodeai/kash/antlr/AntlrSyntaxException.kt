package com.accucodeai.kash.antlr

/**
 * Thrown by [ThrowingErrorListener] when ANTLR's lexer or parser reports a
 * syntax error through the error-listener channel (the lexer's only error
 * path; for the parser, [org.antlr.v4.kotlinruntime.BailErrorStrategy]
 * already throws a `ParseCancellationException` first).
 *
 * Position fields mirror the listener callback signature so callers can
 * format diagnostics without re-parsing the message.
 */
public class AntlrSyntaxException(
    public val line: Int,
    public val charPositionInLine: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
