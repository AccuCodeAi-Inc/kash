package com.accucodeai.kash.tools.make.parser

import com.accucodeai.kash.antlr.AntlrSyntaxException
import com.accucodeai.kash.antlr.configureForFailFast
import com.accucodeai.kash.antlr.toAntlrDiagnostic
import com.accucodeai.kash.antlr.twoStageParse
import com.accucodeai.kash.tools.make.Assignment
import com.accucodeai.kash.tools.make.CondBranch
import com.accucodeai.kash.tools.make.CondKind
import com.accucodeai.kash.tools.make.ConditionalStmt
import com.accucodeai.kash.tools.make.DefineStmt
import com.accucodeai.kash.tools.make.ExportStmt
import com.accucodeai.kash.tools.make.IncludeStmt
import com.accucodeai.kash.tools.make.MacroFlavor
import com.accucodeai.kash.tools.make.MakeParseError
import com.accucodeai.kash.tools.make.MakeStmt
import com.accucodeai.kash.tools.make.Makefile
import com.accucodeai.kash.tools.make.RuleStmt
import com.accucodeai.kash.tools.make.UnexportStmt
import com.accucodeai.kash.tools.make.parser.antlr.MakefileLexer
import com.accucodeai.kash.tools.make.parser.antlr.MakefileParser
import org.antlr.v4.kotlinruntime.CharStreams
import org.antlr.v4.kotlinruntime.CommonTokenStream
import org.antlr.v4.kotlinruntime.RecognitionException
import org.antlr.v4.kotlinruntime.misc.ParseCancellationException

public fun parseMakefile(source: String): Makefile {
    val normalized = preprocess(source)
    val lexer = MakefileLexer(CharStreams.fromString(normalized)).also { configureForFailFast(it) }
    val stream = CommonTokenStream(lexer)
    val parser = MakefileParser(stream).also { configureForFailFast(it) }
    val tree =
        try {
            twoStageParse(parser, stream) { parser.program() }
        } catch (e: ParseCancellationException) {
            throw e.toMakeParseError()
        } catch (e: RecognitionException) {
            throw e.toMakeParseError()
        } catch (e: AntlrSyntaxException) {
            throw MakeParseError("parse error: ${e.message}", e.line)
        }
    val flat = FlatLineBuilder().build(tree)
    return assembleMakefile(flat)
}

private fun preprocess(source: String): String {
    val sb = StringBuilder(source.length)
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '\r') {
            if (i + 1 < source.length && source[i + 1] == '\n') {
                sb.append('\n')
                i += 2
                continue
            }
            sb.append('\n')
            i++
            continue
        }
        sb.append(c)
        i++
    }
    if (sb.isEmpty() || sb[sb.length - 1] != '\n') sb.append('\n')
    return sb.toString()
}

private fun Throwable.toMakeParseError(): MakeParseError {
    if (this is ParseCancellationException) {
        val inner = cause as? RecognitionException
        if (inner != null) return inner.toMakeParseError()
        return MakeParseError("parse error: ${message ?: "syntax error"}")
    }
    if (this is RecognitionException) {
        val d = toAntlrDiagnostic()
        val where = if (d.line > 0) " at line ${d.line}:${d.column}" else ""
        val what = d.offendingText?.let { "near `$it`" } ?: (message ?: "syntax error")
        return MakeParseError("parse error: $what$where", d.line)
    }
    return MakeParseError("parse error: ${message ?: "syntax error"}")
}
