package com.accucodeai.kash.parser

import com.accucodeai.kash.ast.ForCommand
import com.accucodeai.kash.ast.IfCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression guard for the [com.accucodeai.kash.parser] grammar surgery
 * that moved `sep*` slop out of `compoundList` and into its callers
 * (`ifCmd`, `body`, `groupCmd`, etc.). The surgery is invisible to
 * `AstBuilder.statementsFromChildren` because that helper filters for
 * `StatementContext` and ignores everything else — but a regression in
 * how the parser routes separators would either drop statements,
 * mis-pair the `&` backgrounding flag, or fail to parse degenerate
 * empty/sep-only bodies. These tests pin those shapes.
 */
class CompoundListShapeTest {
    private companion object {
        // Upper bound for the deep-nesting parse tripwires. These guard
        // against *exponential* blowup (the old LL-backtracking bug), where
        // a regression takes seconds-to-OOM while healthy linear parsing is
        // sub-millisecond. Kept generous on purpose so a CPU-starved CI box
        // doesn't false-fail on constant-factor jitter — the only thing that
        // can actually exceed this is a true algorithmic regression.
        const val PARSE_BLOWUP_GUARD_MS = 5_000L
    }

    private fun parse(src: String) = Parser(src).parseScript()

    private fun firstFor(src: String): ForCommand =
        parse(src)
            .statements
            .single()
            .pipelines
            .single()
            .commands
            .single() as ForCommand

    private fun firstIf(src: String): IfCommand =
        parse(src)
            .statements
            .single()
            .pipelines
            .single()
            .commands
            .single() as IfCommand

    @Test fun emptyForBody() {
        // Caller's trailing `sep*` between `do` and `done` absorbs nothing;
        // body is the empty list.
        val f = firstFor("for x in 1; do done")
        assertEquals(emptyList(), f.body)
    }

    @Test fun emptyIfBranches() {
        // `if … then … fi` with no statements in either branch. The seps
        // surrounding the (absent) then-body all sit at the caller now.
        val i = firstIf("if true; then fi")
        assertEquals(1, i.clauses.size)
        assertEquals(emptyList(), i.clauses[0].body)
        // Sanity: the condition is the single `true` builtin.
        assertEquals(1, i.clauses[0].condition.size)
    }

    @Test fun forBodyWithOnlySeps() {
        // `do ; ; ; done` — all seps absorbed by caller's `sep*` wrappers,
        // compoundList itself matches empty.
        val f = firstFor("for x in 1; do ; ; ; done")
        assertEquals(emptyList(), f.body)
    }

    @Test fun ifBodyWithLeadingSeps() {
        // Leading separators before the first statement of the then-body
        // belong to the caller's `sep*` now — the body still has exactly
        // one statement.
        val i = firstIf("if true; then\n\n\nfoo\nfi")
        assertEquals(1, i.clauses[0].body.size)
    }

    @Test fun forBodyWithTrailingSeps() {
        // Trailing seps after the last statement belong to the caller's
        // `sep*` now — the body still has exactly one statement. Use
        // mixed sep tokens (NL and ;) since `;;` would tokenize as the
        // case-terminator token bash also rejects here.
        val f = firstFor("for x in 1; do foo\n;\n; done")
        assertEquals(1, f.body.size)
    }

    @Test fun backgroundingPreservedAcrossSurgery() {
        // The `&` between `foo` and `bar` lives in an inter-statement
        // `sep+` inside compoundList — which we did NOT move. The flag
        // must still pair with `foo`, not `bar`. This is the regression
        // guard for AstBuilder.statementsFromChildren's OP_AMP scan.
        val f = firstFor("for x in 1; do foo & bar; done")
        assertEquals(2, f.body.size)
        assertTrue(f.body[0].background, "foo should be backgrounded")
        assertTrue(!f.body[1].background, "bar should not be backgrounded")
    }

    @Test fun deepNestingParsesFast() {
        // 64 nested for-loops — depth above bash's MAX_NESTING_LEVEL.
        // Before the surgery + per-rule LL fallback this OOMed at depth=20
        // under the default 512m test heap.
        val script = buildNestedFor(64)
        val t0 =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        parse(script)
        val ms = t0.elapsedNow().inWholeMilliseconds
        // Tripwire for *exponential* parse blowup (the old backtracking bug),
        // not a microbenchmark: linear parsing is sub-ms, an explosion is
        // seconds-to-OOM. The bound is deliberately generous so it survives a
        // loaded CI box — even O(n³) at this depth stays well under it.
        assertTrue(ms < PARSE_BLOWUP_GUARD_MS, "expected <${PARSE_BLOWUP_GUARD_MS}ms, got ${ms}ms")
    }

    @Test fun pathologicalDepthParsesFast() {
        // exportfunc2.sub's shape (CVE-2014-7187 parser stress, 200 deep).
        // Source of bash/exportfunc's prior 38s conformance time.
        val script = buildNestedFor(200)
        val t0 =
            kotlin.time.TimeSource.Monotonic
                .markNow()
        parse(script)
        val ms = t0.elapsedNow().inWholeMilliseconds
        // See [deepNestingParsesFast]: generous exponential-blowup tripwire,
        // not a constant-factor perf bound.
        assertTrue(ms < PARSE_BLOWUP_GUARD_MS, "expected <${PARSE_BLOWUP_GUARD_MS}ms, got ${ms}ms")
    }

    private fun buildNestedFor(depth: Int): String =
        buildString {
            repeat(depth) { i -> append("for x${i + 1} in ; do :\n") }
            repeat(depth) { append("done\n") }
        }
}
