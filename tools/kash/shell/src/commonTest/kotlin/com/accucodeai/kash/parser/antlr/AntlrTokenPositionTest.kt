package com.accucodeai.kash.parser.antlr

import com.accucodeai.kash.parser.Lexer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [KashAntlrToken.sourceEnd] carries each token's
 * root-source end position from the kash lexer. This is the side-
 * channel V2 [com.accucodeai.kash.parser.StatementStream] reads to
 * advance its source-character cursor.
 *
 * We keep ANTLR's own `startIndex` / `stopIndex` at their CommonToken
 * defaults — they don't survive `reorderHeredocBodies`'s out-of-source-
 * order shuffle of heredoc body tokens, and the runtime would complain
 * about a `startIndex > stopIndex` token. The kash-specific `sourceEnd`
 * field has no such invariant.
 */
class AntlrTokenPositionTest {
    private fun antlrTokensFor(src: String): List<KashAntlrToken> {
        val lexer = Lexer(src)
        val tokens = lexer.tokenize()
        val source = kashTokenSourceFrom(tokens, lexer.tokenEndPositions())
        val out = mutableListOf<KashAntlrToken>()
        while (true) {
            val t = source.nextToken()
            if (t.type == org.antlr.v4.kotlinruntime.Token.EOF) break
            out += t as KashAntlrToken
        }
        return out
    }

    @Test fun sourceEndCarriesLexerOffset() {
        // `echo hello`: WORD `echo` at 0..3 (sourceEnd=4), WORD `hello`
        // at 5..9 (sourceEnd=10).
        val antlr = antlrTokensFor("echo hello")
        assertTrue(antlr.size >= 2, "should produce at least two antlr tokens")
        assertEquals(4, antlr[0].sourceEnd, "first word sourceEnd is at the space")
        assertEquals(10, antlr[1].sourceEnd, "second word sourceEnd is at source.length")
    }

    @Test fun separatorTokenSourceEnd() {
        // `a;b` → WORD/SEMI/WORD with sourceEnds 1, 2, 3.
        val antlr = antlrTokensFor("a;b")
        assertEquals(3, antlr.size)
        assertEquals(1, antlr[0].sourceEnd)
        assertEquals(2, antlr[1].sourceEnd)
        assertEquals(3, antlr[2].sourceEnd)
    }

    @Test fun heredocBodyEndsAfterDelimiterLine() {
        // For `cat <<EOF\nbody\nEOF\nx`, the heredoc body's sourceEnd
        // points past the closing `EOF\n` (~index 19), even though
        // reorderHeredocBodies moved its position in the token list to
        // right after the `<<EOF` RedirOp.
        val antlr = antlrTokensFor("cat <<EOF\nbody\nEOF\nx")
        val bodyIdx = antlr.indexOfFirst { it.kashSource is com.accucodeai.kash.parser.Token.HereDocBodyTok }
        assertTrue(bodyIdx >= 0, "must have a heredoc body token in the stream")
        assertTrue(
            antlr[bodyIdx].sourceEnd >= 18,
            "body sourceEnd should reflect post-delimiter position (got ${antlr[bodyIdx].sourceEnd})",
        )
    }

    @Test fun emptyPositionsArrayLeavesSourceEndDefault() {
        // Backward-compat: callers passing no positions get sourceEnd=-1.
        val src = "x"
        val lexer = Lexer(src)
        val tokens = lexer.tokenize()
        val source = kashTokenSourceFrom(tokens) // no ends arg → empty array default
        val first = source.nextToken() as KashAntlrToken
        assertEquals(-1, first.sourceEnd, "default sentinel when no positions passed")
    }
}
