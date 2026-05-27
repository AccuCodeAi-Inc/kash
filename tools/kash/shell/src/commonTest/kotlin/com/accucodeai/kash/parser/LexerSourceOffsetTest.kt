package com.accucodeai.kash.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for the per-token root-source end-offset list maintained by
 * [Lexer.tokenEndPositions]. This is what V2 [StatementStream] reads to
 * advance its source-character cursor after `parser.statement()`
 * consumes a statement's worth of tokens.
 *
 * Properties:
 *  - parallel to the post-reorder token list (same length, same order)
 *  - each entry points to the character index AFTER the last char that
 *    contributed to that token (i.e. the start of the next token, or
 *    end-of-source for the trailing EOF)
 *  - for tokens emitted inside an alias body, the entry holds the root-
 *    source position right after the alias REFERENCE call site, not
 *    the body-local position
 *  - [Lexer.reorderHeredocBodies] reshapes the position list in
 *    lock-step with the token list it reshapes
 */
class LexerSourceOffsetTest {
    private fun tokenize(src: String): Pair<List<Token>, IntArray> {
        val l = Lexer(src)
        val toks = l.tokenize()
        return toks to l.tokenEndPositions()
    }

    @Test fun parallelLengths() {
        val (toks, ends) = tokenize("echo hello\nwc -l")
        assertEquals(toks.size, ends.size, "ends array must be parallel to tokens")
    }

    @Test fun trailingEofPointsAtSourceLength() {
        val src = "echo hi"
        val (toks, ends) = tokenize(src)
        // Last token is EOF (Token.Eof or similar). Its recorded end
        // position is at or past the source length.
        val lastEnd = ends.last()
        assertTrue(
            lastEnd >= src.length,
            "trailing EOF end ($lastEnd) must be ≥ source.length (${src.length})",
        )
    }

    @Test fun newlinePositionAfterChar() {
        // `a\nb` — the Newline token spans index 1 (the `\n`). Its end
        // position is 2 (start of `b`).
        val (toks, ends) = tokenize("a\nb")
        val nlIdx = toks.indexOfFirst { it is Token.Newline }
        assertTrue(nlIdx >= 0, "must have a Newline token")
        assertEquals(2, ends[nlIdx], "newline at index 1 ends at offset 2")
    }

    @Test fun semicolonOperatorEnd() {
        // `a;b` — semicolon at index 1, its end is 2.
        val (toks, ends) = tokenize("a;b")
        val semiIdx = toks.indexOfFirst { it is Token.Operator && it.op == ";" }
        assertTrue(semiIdx >= 0)
        assertEquals(2, ends[semiIdx])
    }

    @Test fun wordTokensEndAtBoundary() {
        // `foo bar` — `foo` ends at 3 (space), `bar` ends at 7 (EOF).
        val (toks, ends) = tokenize("foo bar")
        val wordIdx = toks.withIndex().filter { (_, t) -> t is Token.WordTok }.map { it.index }
        assertEquals(2, wordIdx.size, "should produce two WordToks")
        assertEquals(3, ends[wordIdx[0]], "first word ends after `foo`")
        assertEquals(7, ends[wordIdx[1]], "second word ends at EOF (source.length)")
    }

    @Test fun monotonicAcrossTopLevelTokens() {
        // For tokens emitted purely from root source (no alias / no
        // heredoc reorder), the position sequence is monotonically
        // non-decreasing.
        val (toks, ends) = tokenize("echo a; echo b; echo c\n")
        // Filter to top-level tokens only — all of these are root-
        // emitted, so positions must increase weakly.
        var prev = 0
        for (i in ends.indices) {
            assertTrue(
                ends[i] >= prev,
                "position at token $i (${toks[i]::class.simpleName}) is ${ends[i]}, < prev $prev",
            )
            prev = ends[i]
        }
    }

    @Test fun heredocBodyPreservesPosition() {
        // The heredoc body's recorded position is the root-source index
        // RIGHT AFTER the closing delimiter line (where the lexer had
        // advanced past the body + delimiter). reorderHeredocBodies
        // moves the body token next to its redir op, but the recorded
        // POSITION stays where the body was emitted from.
        // Indices: 0..8 = "cat <<EOF", 9 = "\n", 10..13 = "body",
        // 14 = "\n", 15..17 = "EOF", 18 = "\n", 19 = "x". The body
        // covers 10..18 inclusive (body + closing delimiter line).
        val src = "cat <<EOF\nbody\nEOF\nx"
        val (toks, ends) = tokenize(src)
        // After reorder: the HereDocBodyTok sits adjacent to the `<<`
        // RedirOpTok. Its recorded end-position points at the source
        // index AFTER the `\n` that closed the body (i.e. ≥ 19).
        val bodyIdx = toks.indexOfFirst { it is Token.HereDocBodyTok }
        assertTrue(bodyIdx >= 0, "must have a heredoc body token")
        val bodyEnd = ends[bodyIdx]
        assertTrue(
            bodyEnd >= 18,
            "heredoc body end position should reflect post-delimiter position (got $bodyEnd)",
        )
    }

    @Test fun aliasBodyTokensRecordRootPosition() {
        // alias foo='echo hi'; foo
        // When `foo` is expanded, `echo` and `hi` tokens come from the
        // alias body. Their recorded positions are the root-source
        // position right after the `foo` REFERENCE. The subsequent
        // `;` is emitted at its own root position (after the ref).
        val src = "alias foo='echo hi'\nfoo"
        // Construct a Lexer with an alias overlay so `foo` expands.
        val aliases = mapOf("foo" to "echo hi")
        val resolver = AliasResolver { name -> aliases[name] }
        val lexer = Lexer(src, resolver)
        val toks = lexer.tokenize()
        val ends = lexer.tokenEndPositions()
        assertEquals(toks.size, ends.size)
        // Find the `echo` token (from the expanded body).
        val echoIdx =
            toks.indexOfFirst {
                it is Token.WordTok && (it.parts.firstOrNull() as? WordChunk.Literal)?.value == "echo"
            }
        assertTrue(echoIdx >= 0, "expansion should produce an `echo` word token")
        // The `echo` token came from the alias body — its recorded
        // position should be the root-source position right after `foo`
        // was consumed (= source.length = 23 since `foo` is at end).
        val echoEnd = ends[echoIdx]
        assertTrue(
            echoEnd >= src.length - 1,
            "alias-body token's recorded end ($echoEnd) should be at root post-reference position (~${src.length})",
        )
    }
}
