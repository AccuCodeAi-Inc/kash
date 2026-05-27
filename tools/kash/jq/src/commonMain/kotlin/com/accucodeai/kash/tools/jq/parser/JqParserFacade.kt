package com.accucodeai.kash.tools.jq.parser

import com.accucodeai.kash.antlr.AntlrSyntaxException
import com.accucodeai.kash.antlr.configureForFailFast
import com.accucodeai.kash.antlr.toAntlrDiagnostic
import com.accucodeai.kash.antlr.twoStageParse
import com.accucodeai.kash.tools.jq.JqParseError
import com.accucodeai.kash.tools.jq.ast.JqExpr
import com.accucodeai.kash.tools.jq.parser.antlr.JqAstBuilder
import com.accucodeai.kash.tools.jq.parser.antlr.JqLexer
import com.accucodeai.kash.tools.jq.parser.antlr.JqParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException

/**
 * Parse a jq filter into the typed AST. Throws [JqParseError] on any
 * syntactic problem (lexer or parser).
 *
 * Mechanics mirror the bash setup in `:core`: hand off to the ANTLR-Kotlin
 * lexer + parser, configure both for fail-fast diagnostics via the shared
 * `:shared:antlr-runtime` helpers, drive a two-stage SLL→LL parse, and pipe
 * the resulting parse tree into [JqAstBuilder] which produces [JqExpr].
 */
internal fun parseJqProgram(source: String): JqExpr {
    val lexer = JqLexer(CharStreams.fromString(source)).also { configureForFailFast(it) }
    val stream = CommonTokenStream(lexer)
    val parser = JqParser(stream).also { configureForFailFast(it) }
    val tree =
        try {
            twoStageParse(parser, stream) { parser.program() }
        } catch (e: ParseCancellationException) {
            throw e.toJqParseError()
        } catch (e: RecognitionException) {
            throw e.toJqParseError()
        } catch (e: AntlrSyntaxException) {
            throw e.toJqParseError()
        }
    return JqAstBuilder().program(tree)
}

private fun Throwable.toJqParseError(): JqParseError {
    val diag = toAntlrDiagnostic()
    val where = if (diag.line > 0) " at line ${diag.line}:${diag.column}" else ""
    val what = diag.offendingText?.let { "`$it`" } ?: (message ?: "syntax error")
    return JqParseError("jq: parse error: $what$where")
}
