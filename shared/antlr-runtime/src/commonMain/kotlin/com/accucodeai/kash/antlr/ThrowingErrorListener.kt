package com.accucodeai.kash.antlr

import org.antlr.v4.kotlinruntime.BaseErrorListener
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.Recognizer

/**
 * A [BaseErrorListener] that fails fast on the first syntax error by throwing
 * an [AntlrSyntaxException].
 *
 * Why this exists: [org.antlr.v4.kotlinruntime.BailErrorStrategy] only covers
 * the *parser*. Lexer-level errors (unrecognized chars, bad escapes when the
 * ANTLR lexer owns the language) reach the listener channel instead, and the
 * default `ConsoleErrorListener` just prints to stderr. After the usual
 * `removeErrorListeners()` call to silence the console listener, a grammar
 * with no replacement listener will *silently* drop lexer errors and continue
 * parsing garbage. Install this on both `Lexer` and `Parser` so neither path
 * goes quiet.
 */
public object ThrowingErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?,
    ): Unit = throw AntlrSyntaxException(line, charPositionInLine, msg, e)
}
