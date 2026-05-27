package com.accucodeai.kash.interpreter

import com.accucodeai.kash.ast.FunctionDef
import com.accucodeai.kash.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Round-trip property tests for [AstPrinter.functionBody]: parse →
 * print → parse → print must reach a fixed point (string1 == string2).
 *
 * This catches semantic divergence in the printer (e.g. dropped
 * redirections, wrong heredoc placement, missing operators) without
 * requiring us to know bash's exact byte-for-byte format.
 *
 * The pre-existing `<FUNCBODY>` normalize rule in
 * `bash-tests-normalize.txt` was erasing function bodies on BOTH
 * sides of the conformance diff, so divergences here were invisible
 * to the test corpus. Round-trip is independent of that rule.
 *
 * Reference: bash `print_cmd.c::make_command_string` is the analogous
 * AST → source pretty-printer; behavior follows from POSIX 2.10
 * (Shell Grammar) and bash man page section "Functions".
 */
class AstPrinterRoundTripTest {
    private fun extractFunction(script: String): FunctionDef {
        val parsed = Parser(script).parseScript()
        for (stmt in parsed.statements) {
            for (pipeline in stmt.pipelines) {
                for (cmd in pipeline.commands) {
                    if (cmd is FunctionDef) return cmd
                }
            }
        }
        fail("no FunctionDef parsed from: `$script`")
    }

    private fun roundTrip(script: String) {
        val fn1 = extractFunction(script)
        val printed1 = AstPrinter.functionBody(fn1)
        val fn2 =
            try {
                extractFunction(printed1)
            } catch (t: Throwable) {
                fail(
                    "AstPrinter output failed to re-parse.\nOriginal: `$script`\nPrinted: `$printed1`\nError: ${t.message}",
                )
            }
        val printed2 = AstPrinter.functionBody(fn2)
        assertEquals(
            printed1,
            printed2,
            "round-trip not fixed-point for `$script`. First print:\n$printed1\nSecond print:\n$printed2",
        )
    }

    @Test fun emptyBody() {
        roundTrip("f() { :; }")
    }

    @Test fun multiStatementSemicolon() {
        roundTrip("f() { a; b; c; }")
    }

    @Test fun andOrChain() {
        roundTrip("f() { a && b || c; }")
    }

    @Test fun pipeline() {
        roundTrip("f() { echo hi | cat | wc -l; }")
    }

    @Test fun heredocInside() {
        roundTrip("f() { cat <<EOF\nhello\nEOF\n}")
    }

    @Test fun nestedFunction() {
        roundTrip("f() { g() { echo nested; }; g; }")
    }

    @Test fun functionWithRedirections() {
        roundTrip("f() { echo hi; } > /tmp/out")
    }

    @Test fun caseStatement() {
        roundTrip("f() { case \"\$1\" in a) echo A;; b) echo B;; esac; }")
    }

    @Test fun arithmetic() {
        roundTrip("f() { (( x = 1 + 2 )); }")
    }

    @Test fun forLoop() {
        roundTrip("f() { for i in a b c; do echo \$i; done; }")
    }

    @Test fun whileLoop() {
        roundTrip("f() { while [ \$x -gt 0 ]; do x=\$((x-1)); done; }")
    }

    @Test fun ifElse() {
        roundTrip("f() { if [ \$x = a ]; then echo A; else echo B; fi; }")
    }

    @Test fun subshellBody() {
        roundTrip("f() (a; b; c)")
    }

    @Test fun assignmentsAndExpansions() {
        roundTrip("f() { local x=1 y=\$X; echo \"\${y:-default}\"; }")
    }

    @Test fun complexHeredoc() {
        // heredoc inside a pipeline inside a function — exercises both
        // heredoc placement and pipeline ordering in the printer.
        roundTrip("f() { cat <<EOF | wc -l\nline1\nline2\nEOF\n}")
    }

    @Test fun heredocInIfCondition() {
        // Bash declare -f convention: heredoc body flushes BEFORE `then`,
        // and `then` lands on its own line (no inline `; then`).
        roundTrip("f() { if cat <<HERE\ncontents\nHERE\nthen echo ok; fi; }")
    }

    @Test fun heredocInWhileCondition() {
        // Same convention as the `if` variant — `do` on its own line
        // after the heredoc body has flushed.
        roundTrip("f() { while read var <<HERE\ncontents\nHERE\ndo echo \$var; done; }")
    }
}
