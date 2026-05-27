package com.accucodeai.kash.parser

import com.accucodeai.kash.ast.Word
import com.accucodeai.kash.ast.WordPart

/**
 * Lexer → AST conversion helpers, shared between the legacy recursive-descent
 * parser and the new ANTLR-driven [AstBuilder][com.accucodeai.kash.parser.antlr.AstBuilder].
 *
 * `WordChunk.CommandSubstitution` / `ProcessSubstitution` are NOT parsed
 * here — their bodies are stored as raw text on the AST and parsed at
 * expansion time, against the runtime alias / function tables. Without
 * this deferral, `alias foo=…; r=$(foo)` on one line silently misses the
 * alias.
 */
internal fun Token.WordTok.toWord(resolver: AliasResolver = AliasResolver.Empty): Word =
    Word(parts.map { chunkToPart(it, resolver) }, line)

internal fun chunksToWord(
    parts: List<WordChunk>,
    line: Int,
    resolver: AliasResolver = AliasResolver.Empty,
): Word = Word(parts.map { chunkToPart(it, resolver) }, line)

internal fun chunkToPart(
    chunk: WordChunk,
    resolver: AliasResolver = AliasResolver.Empty,
): WordPart =
    when (chunk) {
        is WordChunk.Literal -> {
            WordPart.Literal(chunk.value)
        }

        is WordChunk.SingleQuoted -> {
            WordPart.SingleQuoted(chunk.value)
        }

        is WordChunk.Escaped -> {
            WordPart.Escaped(chunk.value)
        }

        is WordChunk.DoubleQuoted -> {
            WordPart.DoubleQuoted(chunk.parts.map { chunkToPart(it, resolver) })
        }

        is WordChunk.ParameterExpansion -> {
            WordPart.ParameterExpansion(
                parameter = chunk.parameter,
                lengthOf = chunk.lengthOf,
                op = chunk.op,
                arg1 = chunk.arg1?.let { Word(it.map { c -> chunkToPart(c, resolver) }) },
                arg2 = chunk.arg2?.let { Word(it.map { c -> chunkToPart(c, resolver) }) },
                rawArg1 = chunk.rawArg1,
                outerDqAtLex = chunk.outerDqAtLex,
                subscript = chunk.subscript,
                indirect = chunk.indirect,
                nameGlob = chunk.nameGlob,
                braced = chunk.braced,
            )
        }

        is WordChunk.CommandSubstitution -> {
            // Deferred parse: store raw body + open-line. The interpreter
            // re-parses at expansion time so the runtime alias/function
            // table is consulted. The bash-style "while looking for
            // matching `)'" diagnostic rewrite lives on the expansion-time
            // wrapper (see InterpreterExpand.parseCommandSubstitutionBody).
            WordPart.CommandSubstitution(rawText = chunk.rawText, openLine = chunk.openLine)
        }

        is WordChunk.ProcessSubstitution -> {
            // WordChunk.ProcessSubstitution doesn't track openLine today;
            // default to 1 — procsub parse errors are rare and the line
            // hint is best-effort.
            WordPart.ProcessSubstitution(
                direction = chunk.direction,
                rawText = chunk.rawText,
                openLine = 1,
            )
        }

        is WordChunk.ArithmeticExpansion -> {
            WordPart.ArithmeticExpansion(chunk.rawText)
        }
    }
