package com.accucodeai.kash.tools.git.porcelain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Wire `git fetch` / `git clone` flag parsing for `--depth=N` /
 * `--depth N` / `-d N` and `--filter=<spec>` / `--filter <spec>`.
 * Returns the parsed [com.accucodeai.kash.api.git.GitFetchScope] plus
 * the leftover args with those flags removed so the caller can pull
 * positional `<remote>` / `<branch>` out.
 */
class FetchScopeArgsTest {
    @Test fun noFlagsPassThroughUnchanged() {
        val (scope, rest) = parseFetchScopeArgs(listOf("origin", "main"))
        assertNull(scope.depth)
        assertNull(scope.filter)
        assertEquals(listOf("origin", "main"), rest)
    }

    @Test fun depthEqualsFormStripsAndParses() {
        val (scope, rest) = parseFetchScopeArgs(listOf("--depth=1", "origin"))
        assertEquals(1, scope.depth)
        assertEquals(listOf("origin"), rest)
    }

    @Test fun depthSpaceSeparatedConsumesTwoArgs() {
        val (scope, rest) = parseFetchScopeArgs(listOf("--depth", "42", "origin", "main"))
        assertEquals(42, scope.depth)
        assertEquals(listOf("origin", "main"), rest)
    }

    @Test fun dashDIsAlsoAcceptedForDepth() {
        val (scope, _) = parseFetchScopeArgs(listOf("-d", "5", "origin"))
        assertEquals(5, scope.depth)
    }

    @Test fun filterEqualsFormStripsAndParses() {
        val (scope, rest) = parseFetchScopeArgs(listOf("--filter=blob:none", "origin"))
        assertEquals("blob:none", scope.filter)
        assertEquals(listOf("origin"), rest)
    }

    @Test fun filterSpaceSeparatedConsumesTwoArgs() {
        val (scope, rest) =
            parseFetchScopeArgs(listOf("--filter", "blob:limit=1m", "https://example.com/r.git"))
        assertEquals("blob:limit=1m", scope.filter)
        assertEquals(listOf("https://example.com/r.git"), rest)
    }

    @Test fun depthAndFilterCanCombine() {
        val (scope, rest) =
            parseFetchScopeArgs(listOf("--depth=1", "--filter=blob:none", "origin"))
        assertEquals(1, scope.depth)
        assertEquals("blob:none", scope.filter)
        assertEquals(listOf("origin"), rest)
    }

    @Test fun negativeOrZeroDepthRejected() {
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--depth=0")) }
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--depth=-3")) }
    }

    @Test fun nonNumericDepthRejected() {
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--depth=foo")) }
    }

    @Test fun missingValueAfterDepthOrFilterRejected() {
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--depth")) }
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--filter")) }
    }

    @Test fun blankFilterRejected() {
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--filter=")) }
        assertFailsWith<IllegalArgumentException> { parseFetchScopeArgs(listOf("--filter", "")) }
    }

    @Test fun unrelatedFlagsAreLeftInPlace() {
        val (_, rest) = parseFetchScopeArgs(listOf("--quiet", "--bare", "--depth=1", "origin"))
        assertEquals(listOf("--quiet", "--bare", "origin"), rest)
    }
}
