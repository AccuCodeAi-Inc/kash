package com.accucodeai.kash.tools.awk.parser

import com.accucodeai.kash.antlr.AntlrSyntaxException
import com.accucodeai.kash.antlr.configureForFailFast
import com.accucodeai.kash.antlr.toAntlrDiagnostic
import com.accucodeai.kash.antlr.twoStageParse
import com.accucodeai.kash.tools.awk.AwkParseError
import com.accucodeai.kash.tools.awk.ast.AwkProgram
import com.accucodeai.kash.tools.awk.parser.antlr.AwkAstBuilder
import com.accucodeai.kash.tools.awk.parser.antlr.AwkLexer
import com.accucodeai.kash.tools.awk.parser.antlr.AwkParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException

/**
 * Parse an awk source program. Same shape as `parseJqProgram`: hand off to
 * the ANTLR-Kotlin lexer + parser, configure both for fail-fast diagnostics
 * via `:shared:antlr-runtime`, drive a two-stage SLL→LL parse, pipe the
 * tree through [AwkAstBuilder].
 */
internal fun parseAwkProgram(source: String): AwkProgram {
    val lexer = AwkLexer(CharStreams.fromString(source)).also { configureForFailFast(it) }
    val stream = CommonTokenStream(lexer)
    val parser = AwkParser(stream).also { configureForFailFast(it) }
    val tree =
        try {
            twoStageParse(parser, stream) { parser.program() }
        } catch (e: ParseCancellationException) {
            throw e.toAwkParseError()
        } catch (e: RecognitionException) {
            throw e.toAwkParseError()
        } catch (e: AntlrSyntaxException) {
            throw e.toAwkParseError()
        }
    return AwkAstBuilder().program(tree)
}

private fun Throwable.toAwkParseError(): AwkParseError {
    val diag = toAntlrDiagnostic()
    val where = if (diag.line > 0) " at line ${diag.line}:${diag.column}" else ""
    val what = diag.offendingText?.let { "near `$it`" } ?: (message ?: "syntax error")
    return AwkParseError("awk: parse error: $what$where")
}
