package com.accucodeai.kash.api.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

class CompletionResultTest {
    @Test fun lcp_emptyList_isEmpty() {
        assertEquals(
            CompletionResult.Empty,
            CompletionResult.of(0, 0, emptyList()),
        )
    }

    @Test fun lcp_singleCandidate_isThatCandidatesText() {
        val r = CompletionResult.of(0, 2, listOf(Candidate("echo")))
        assertEquals("echo", r.commonPrefix)
    }

    @Test fun lcp_sharedPrefix_isTheSharedPrefix() {
        val r =
            CompletionResult.of(
                0,
                1,
                listOf(Candidate("echo"), Candidate("expr"), Candidate("env")),
            )
        assertEquals("e", r.commonPrefix)
    }

    @Test fun lcp_noSharedPrefix_isEmpty() {
        val r =
            CompletionResult.of(
                0,
                0,
                listOf(Candidate("alpha"), Candidate("bravo")),
            )
        assertEquals("", r.commonPrefix)
    }

    @Test fun lcp_extendsBeyondTypedPrefix() {
        // typed = "e" → candidates {echo, exec, exit} share "e" only.
        val r =
            CompletionResult.of(
                0,
                1,
                listOf(Candidate("echo"), Candidate("exec"), Candidate("exit")),
            )
        assertEquals("e", r.commonPrefix)

        // typed = "ex" → candidates {exec, exit} share "ex".
        val r2 =
            CompletionResult.of(
                0,
                2,
                listOf(Candidate("exec"), Candidate("exit")),
            )
        assertEquals("ex", r2.commonPrefix)
    }
}
