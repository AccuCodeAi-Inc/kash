package com.accucodeai.kash.parser.antlr

import com.accucodeai.kash.parser.Lexer
import com.accucodeai.kash.parser.Token
import com.accucodeai.kash.parser.WordChunk
import org.antlr.v4.kotlinruntime.CommonToken
import org.antlr.v4.kotlinruntime.ListTokenSource

/**
 * Wraps a kash [Token] so the visitor can recover the original payload after
 * ANTLR has matched it through the grammar. ANTLR's runtime works on integer
 * token types and string text; the structured fields (word parts, heredoc
 * bodies, raw arithmetic text, …) live here.
 */
internal class KashAntlrToken(
    type: Int,
    text: String,
    val kashSource: Token?,
    /** Root-source end position (exclusive — points one past the
     *  token's last contributing character) recorded by the kash lexer.
     *  For heredoc bodies this may be LARGER than the surrounding
     *  tokens' positions because `reorderHeredocBodies` moves them
     *  out of source order. V2 [com.accucodeai.kash.parser.StatementStream]
     *  reads `sourceEnd` of the last consumed token and takes a
     *  running-max with previously-consumed tokens to advance its
     *  cursor — that side-channel computation keeps ANTLR's own
     *  position model (`startIndex` / `stopIndex`) untouched and
     *  preserves the standard `start ≤ stop` invariant the runtime
     *  assumes. */
    val sourceEnd: Int = -1,
) : CommonToken(type, text) {
    init {
        line = kashSource?.line ?: 1
    }
}

/**
 * Translates a kash source string through the hand-written [Lexer] and into a
 * stream of ANTLR tokens. Returns a [ListTokenSource] ready to feed into a
 * `CommonTokenStream`. The trailing [Token.Eof] is dropped — `ListTokenSource`
 * synthesizes its own EOF when the list is exhausted.
 */
internal fun kashTokenSource(source: String): ListTokenSource {
    val lexer = Lexer(source)
    val tokens = lexer.tokenize()
    return kashTokenSourceFrom(tokens, lexer.tokenEndPositions())
}

/**
 * Same as [kashTokenSource] but accepts a pre-tokenized list plus the
 * parallel per-token end-positions array (from [Lexer.tokenEndPositions]).
 * The positions are set as ANTLR's `startIndex` / `stopIndex` on each
 * [KashAntlrToken] so V2 [com.accucodeai.kash.parser.StatementStream]
 * can advance its source cursor from the last consumed token's
 * `stopIndex + 1`.
 *
 * Pass an empty IntArray to skip position population — legacy callers
 * that don't need cursor advancement (e.g. one-shot diagnostic parses).
 */
internal fun kashTokenSourceFrom(
    kashTokens: List<Token>,
    tokenEnds: IntArray = IntArray(0),
): ListTokenSource {
    val antlrTokens = mutableListOf<org.antlr.v4.kotlinruntime.Token>()
    val havePositions = tokenEnds.isNotEmpty()
    for ((i, t) in kashTokens.withIndex()) {
        when (t) {
            is Token.Eof -> {
                Unit
            }

            // Filtered in callers that care about it as a parse diagnostic
            // ([parseStatementStream] strips it before getting here, and
            // [parseViaAntlr] short-circuits to ParseResult.Error). Any
            // leaked LexError is silently dropped — ListTokenSource emits
            // EOF on exhaustion, so dropping the sentinel just makes the
            // parser see end-of-input where the lex aborted.
            is Token.LexError -> {
                Unit
            }

            // ListTokenSource emits EOF on exhaustion
            else -> {
                val rawEnd = if (havePositions && i < tokenEnds.size) tokenEnds[i] else -1
                antlrTokens += mapToken(t, rawEnd)
            }
        }
    }
    return ListTokenSource(antlrTokens, "kash")
}

private fun mapToken(
    t: Token,
    endPos: Int = -1,
): KashAntlrToken =
    when (t) {
        is Token.WordTok -> {
            // Surface the single-literal text so grammar-level predicates can
            // detect unary/binary cond ops by text. Quoted/expanded words
            // become empty-text and won't match any op set.
            val singleLit = (t.parts.singleOrNull() as? WordChunk.Literal)?.value ?: ""
            KashAntlrToken(KashParser.Tokens.WORD, singleLit, t, endPos)
        }

        is Token.AssignmentTok -> {
            KashAntlrToken(KashParser.Tokens.ASSIGN, t.name, t, endPos)
        }

        is Token.ArrayAssignTok -> {
            KashAntlrToken(KashParser.Tokens.ARRAY_ASSIGN, t.name, t, endPos)
        }

        is Token.IndexedAssignTok -> {
            KashAntlrToken(KashParser.Tokens.INDEXED_ASSIGN, t.name, t, endPos)
        }

        is Token.HereDocBodyTok -> {
            KashAntlrToken(KashParser.Tokens.HEREDOC_BODY, "", t, endPos)
        }

        is Token.ArithCmdTok -> {
            KashAntlrToken(KashParser.Tokens.ARITH_CMD, t.rawText, t, endPos)
        }

        is Token.Newline -> {
            KashAntlrToken(KashParser.Tokens.NL, "\n", t, endPos)
        }

        is Token.Keyword -> {
            KashAntlrToken(keywordType(t.word), t.word, t, endPos)
        }

        is Token.RedirOpTok -> {
            KashAntlrToken(redirType(t.op), t.op, t, endPos)
        }

        is Token.Operator -> {
            KashAntlrToken(operatorType(t.op), t.op, t, endPos)
        }

        is Token.Eof -> {
            error("Eof should be filtered before mapping")
        }

        is Token.LexError -> {
            error("LexError should be filtered before mapping")
        }
    }

private fun keywordType(word: String): Int =
    when (word) {
        "if" -> KashParser.Tokens.KW_IF
        "then" -> KashParser.Tokens.KW_THEN
        "elif" -> KashParser.Tokens.KW_ELIF
        "else" -> KashParser.Tokens.KW_ELSE
        "fi" -> KashParser.Tokens.KW_FI
        "for" -> KashParser.Tokens.KW_FOR
        "select" -> KashParser.Tokens.KW_SELECT
        "in" -> KashParser.Tokens.KW_IN
        "do" -> KashParser.Tokens.KW_DO
        "done" -> KashParser.Tokens.KW_DONE
        "while" -> KashParser.Tokens.KW_WHILE
        "until" -> KashParser.Tokens.KW_UNTIL
        "case" -> KashParser.Tokens.KW_CASE
        "esac" -> KashParser.Tokens.KW_ESAC
        "function" -> KashParser.Tokens.KW_FUNCTION
        "{" -> KashParser.Tokens.KW_LBRACE
        "}" -> KashParser.Tokens.KW_RBRACE
        "!" -> KashParser.Tokens.KW_BANG
        "[[" -> KashParser.Tokens.KW_DLBRACK
        "]]" -> KashParser.Tokens.KW_DRBRACK
        "coproc" -> KashParser.Tokens.KW_COPROC
        "time" -> KashParser.Tokens.KW_TIME
        else -> error("unknown keyword from lexer: $word")
    }

private fun redirType(op: String): Int =
    when (op) {
        "<" -> KashParser.Tokens.REDIR_LT
        ">" -> KashParser.Tokens.REDIR_GT
        ">>" -> KashParser.Tokens.REDIR_DGT
        ">|" -> KashParser.Tokens.REDIR_GT_PIPE
        "<>" -> KashParser.Tokens.REDIR_LT_GT
        ">&" -> KashParser.Tokens.REDIR_GT_AMP
        "<&" -> KashParser.Tokens.REDIR_LT_AMP
        "&>" -> KashParser.Tokens.REDIR_AMP_GT
        "&>>" -> KashParser.Tokens.REDIR_AMP_DGT
        "<<<" -> KashParser.Tokens.REDIR_HSTRING
        "<<" -> KashParser.Tokens.REDIR_HEREDOC
        "<<-" -> KashParser.Tokens.REDIR_HEREDOC_STRIP
        else -> error("unknown redir op from lexer: $op")
    }

private fun operatorType(op: String): Int =
    when (op) {
        "|" -> KashParser.Tokens.OP_PIPE
        "|&" -> KashParser.Tokens.OP_PIPE_AMP
        "&&" -> KashParser.Tokens.OP_AND_AND
        "||" -> KashParser.Tokens.OP_OR_OR
        "&" -> KashParser.Tokens.OP_AMP
        ";" -> KashParser.Tokens.OP_SEMI
        ";;" -> KashParser.Tokens.OP_DSEMI
        ";&" -> KashParser.Tokens.OP_DSEMI_AMP
        ";;&" -> KashParser.Tokens.OP_DSEMI_SEMI
        "(" -> KashParser.Tokens.OP_LPAREN
        ")" -> KashParser.Tokens.OP_RPAREN
        else -> error("unknown operator from lexer: $op")
    }
