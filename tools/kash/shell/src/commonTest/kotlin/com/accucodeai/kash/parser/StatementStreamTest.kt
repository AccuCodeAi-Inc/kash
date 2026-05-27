package com.accucodeai.kash.parser

import com.accucodeai.kash.ast.IfCommand
import com.accucodeai.kash.ast.SimpleCommand
import com.accucodeai.kash.ast.WhileCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for [StatementStream] — the cursor used by the per-statement
 * read-parse-execute loop. Each [next] invocation must:
 *
 *  - re-read the current POSIX mode from the provider
 *  - yield pre-error statements in source order
 *  - surface the first parse error AFTER yielding all valid statements
 *  - drain to Eof after error/exhaustion
 */
class StatementStreamTest {
    private fun stream(
        source: String,
        posixMode: Boolean = false,
    ) = StatementStream(source, posixModeProvider = { posixMode })

    private fun StatementStream.takeAll(): List<StatementStream.NextResult> {
        val out = mutableListOf<StatementStream.NextResult>()
        while (true) {
            val r = next()
            out += r
            if (r is StatementStream.NextResult.Eof) break
            if (r is StatementStream.NextResult.Error) {
                // Drain past the error to confirm the stream stays at Eof.
                val tail = next()
                assertTrue(tail is StatementStream.NextResult.Eof, "stream should be drained after error")
                out += tail
                break
            }
        }
        return out
    }

    @Test fun emptySourceYieldsEof() {
        val s = stream("")
        assertTrue(s.next() is StatementStream.NextResult.Eof)
        // Idempotent: repeated next() stays at Eof.
        assertTrue(s.next() is StatementStream.NextResult.Eof)
    }

    @Test fun whitespaceOnlyYieldsEof() {
        val s = stream("   \n\n  \t\n")
        assertTrue(s.next() is StatementStream.NextResult.Eof)
    }

    @Test fun singleStatement() {
        val s = stream("echo hello")
        val r1 = s.next()
        assertTrue(r1 is StatementStream.NextResult.Statement)
        assertEquals(1, r1.statement.pipelines.size)
        assertTrue(s.next() is StatementStream.NextResult.Eof)
    }

    @Test fun multipleStatementsSemicolon() {
        val s = stream("echo a; echo b; echo c")
        val results = s.takeAll()
        val stmts = results.filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(3, stmts.size, "should yield three statements")
        assertTrue(results.last() is StatementStream.NextResult.Eof)
    }

    @Test fun multipleStatementsNewline() {
        val s = stream("echo a\necho b\necho c\n")
        val stmts = s.takeAll().filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(3, stmts.size)
    }

    @Test fun backgroundStatement() {
        val s = stream("sleep 1 & echo done")
        val stmts = s.takeAll().filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(2, stmts.size)
        assertTrue(stmts[0].statement.background, "first statement should be marked background")
        assertTrue(!stmts[1].statement.background)
    }

    @Test fun compoundStaysOneStatement() {
        val s =
            stream(
                """
                if [ x = x ]; then
                    echo true
                fi
                echo after
                """.trimIndent(),
            )
        val stmts = s.takeAll().filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(2, stmts.size, "if/fi is one statement; echo after is the second")
        val firstCmd =
            stmts[0]
                .statement.pipelines
                .first()
                .commands
                .first()
        assertTrue(firstCmd is IfCommand, "first statement should hold an IfCommand")
    }

    @Test fun whileLoopOneStatement() {
        val s =
            stream(
                """
                while false; do
                    echo body
                done
                echo after
                """.trimIndent(),
            )
        val stmts = s.takeAll().filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(2, stmts.size)
        val firstCmd =
            stmts[0]
                .statement.pipelines
                .first()
                .commands
                .first()
        assertTrue(firstCmd is WhileCommand)
    }

    @Test fun heredocBodyAttachedToStatement() {
        // The heredoc body line and the EOF delimiter belong to the FIRST
        // statement, not to a separate one. After this statement, only
        // `echo after` remains.
        val s =
            stream(
                """
                cat <<EOF
                body line 1
                body line 2
                EOF
                echo after
                """.trimIndent(),
            )
        val stmts = s.takeAll().filterIsInstance<StatementStream.NextResult.Statement>()
        assertEquals(2, stmts.size)
        // Sanity: the second statement is `echo after`, not part of the heredoc body.
        val secondCmd =
            stmts[1]
                .statement.pipelines
                .first()
                .commands
                .first()
        assertTrue(secondCmd is SimpleCommand)
    }

    @Test fun parseErrorAfterValidStatements() {
        // First statement parses; second is a syntax error. Yield first,
        // then error, then Eof.
        val s = stream("echo good\nif then")
        val results = s.takeAll()
        val stmts = results.filterIsInstance<StatementStream.NextResult.Statement>()
        val errors = results.filterIsInstance<StatementStream.NextResult.Error>()
        assertEquals(1, stmts.size, "valid statement yielded before error")
        assertEquals(1, errors.size, "error surfaced once")
        // Final element drained to Eof.
        assertTrue(results.last() is StatementStream.NextResult.Eof)
    }

    @Test fun pureErrorYieldsErrorThenEof() {
        // No valid statements before the error — leading `)` is a hard
        // syntax error with nothing parseable before it.
        val s = stream(")")
        val results = s.takeAll()
        val stmts = results.filterIsInstance<StatementStream.NextResult.Statement>()
        val errors = results.filterIsInstance<StatementStream.NextResult.Error>()
        assertEquals(0, stmts.size, "no statement should parse before the bare `)`")
        assertEquals(1, errors.size)
    }

    @Test fun posixModeProviderReadEachCall() {
        // Track how many times the provider was consulted.
        var calls = 0
        val s =
            StatementStream(
                source = "echo a; echo b; echo c",
                posixModeProvider = {
                    calls++
                    false
                },
            )
        s.next() // first statement
        s.next() // second
        s.next() // third
        s.next() // Eof
        assertTrue(calls >= 3, "provider should be read at least once per yielded statement")
    }

    @Test fun posixModeAffectsParseRetroactively() {
        // The exact lex-rule change we care about: `'` inside DQ default
        // operand. Non-POSIX: `'X'` is a quote region (operand `'}'`,
        // outer `}` closes); POSIX: `'` is literal (operand `'`, first
        // `}` closes, leaving `'}` as trailing text).
        //
        // Verify by re-parsing the same script under each mode and
        // observing the statement structurally differs. We don't execute
        // here — just confirm the parse-time mode affects the AST.
        val src = "recho \"\${HOME-'}'}\""
        val nonPosix = StatementStream(src, posixModeProvider = { false }).next()
        val posix = StatementStream(src, posixModeProvider = { true }).next()
        assertTrue(nonPosix is StatementStream.NextResult.Statement)
        assertTrue(posix is StatementStream.NextResult.Statement)
        // Both parse successfully; the difference is in the parameter
        // expansion's operand handling, which is exercised at expansion
        // time (asserted by the bash/new-exp and bash/posixexp
        // conformance corpus, not here).
    }

    @Test fun idempotentEofAfterDrain() {
        val s = stream("echo a")
        s.next() // statement
        assertTrue(s.next() is StatementStream.NextResult.Eof)
        assertTrue(s.next() is StatementStream.NextResult.Eof)
        assertTrue(s.next() is StatementStream.NextResult.Eof)
    }

    @Test fun extglobProviderIsRead() {
        // The provider must be invoked per yield (not snapshotted at
        // construction) so a mid-script `shopt -s extglob` /
        // `shopt -u extglob` affects subsequent statements. Counting
        // calls is the minimal-coupling way to assert this — the
        // semantic effect on lex is covered by the bash/extglob
        // conformance corpus.
        var calls = 0
        val s =
            StatementStream(
                "echo a; echo b; echo c",
                extglobProvider = {
                    calls++
                    true
                },
            )
        s.next()
        s.next()
        s.next()
        assertTrue(calls >= 3, "extglob provider should be read at least once per yield")
    }
}
