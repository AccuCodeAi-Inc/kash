package com.accucodeai.kash.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Body-boundary tests for the cmdsub sibling scanners in [Lexer]:
 * `<(...)` / `>(...)` process substitution and `${ ...; }` brace
 * command substitution.
 *
 * The capture path for `$(...)` (`readCommandSubstitution`) tracks
 * `case ... esac` pattern-terminator `)`s so that
 * `$( case x in foo) echo;; esac )` bounds the cmdsub at the trailing
 * `)`, not at `foo)`. Bash's `comsub scanner` does the same for the
 * sibling boundary finders — without it, the captured raw text is
 * truncated mid-statement and runtime re-parse sees a fragment.
 *
 * Reference: bash  `comsub scanner` + `matched-pair scanner` —
 * pattern-terminator `)` does not decrement paren depth at the cmdsub
 * boundary level.
 */
class SiblingScannerCaseAwarenessTest {
    private fun firstChunk(src: String): WordChunk? {
        val l = Lexer(src)
        val toks = l.tokenize()
        // Find the first WordTok whose chunks contain a process-sub or
        // brace-cmdsub. Simple linear scan over tokens.
        for (t in toks) {
            if (t is Token.WordTok) {
                for (c in t.parts) {
                    if (c is WordChunk.ProcessSubstitution || c is WordChunk.CommandSubstitution) {
                        return c
                    }
                }
            }
        }
        return null
    }

    @Test fun procsubInputCapturesCasePatternBody() {
        // Input procsub with a case-pattern `)` in its body. The
        // captured rawText must span the FULL case statement up to the
        // closing procsub `)`.
        val src = "cat <( case x in foo) echo ok;; esac )"
        val chunk = firstChunk(src) ?: fail("expected a WordChunk in `$src`")
        chunk as? WordChunk.ProcessSubstitution ?: fail("expected ProcessSubstitution, got ${chunk::class.simpleName}")
        // Body must include `esac` — if the case-pattern `)` mis-bounded
        // the procsub, the captured text would stop at `foo` (or shortly
        // after) and never include `esac`.
        assertTrue(
            chunk.rawText.contains("esac"),
            "procsub capture truncated mid-case. Got: `${chunk.rawText}`",
        )
        assertEquals('<', chunk.direction)
    }

    @Test fun procsubOutputCapturesCasePatternBody() {
        val src = "echo hi >( case y in bar) cat;; esac )"
        val chunk = firstChunk(src) ?: fail("expected a WordChunk in `$src`")
        chunk as? WordChunk.ProcessSubstitution ?: fail("expected ProcessSubstitution")
        assertTrue(
            chunk.rawText.contains("esac"),
            "output procsub capture truncated. Got: `${chunk.rawText}`",
        )
        assertEquals('>', chunk.direction)
    }

    @Test fun procsubLeadingParenCasePattern() {
        // Bash also accepts `case x in (foo) ...; esac` — leading-paren
        // pattern form. The procsub bound finder must handle it too.
        val src = "cat <( case x in (foo) echo ok;; esac )"
        val chunk =
            firstChunk(src) as? WordChunk.ProcessSubstitution
                ?: fail("expected ProcessSubstitution")
        assertTrue(
            chunk.rawText.contains("esac"),
            "leading-paren case form mis-bounded procsub. Got: `${chunk.rawText}`",
        )
    }

    @Test fun braceCmdsubCaseInside() {
        // Brace cmdsub `${ ...; }` only tracks `{`/`}`, so a `)` in a
        // case pattern doesn't confuse it. Regression-coverage that the
        // existing behavior stays correct.
        val src = "echo \${ case x in foo) echo ok;; esac; }"
        val chunk =
            firstChunk(src) as? WordChunk.CommandSubstitution
                ?: fail("expected CommandSubstitution from `\${ ... }`")
        assertTrue(
            chunk.rawText.contains("esac"),
            "brace cmdsub body capture wrong. Got: `${chunk.rawText}`",
        )
    }

    @Test fun procsubNestedCmdsubCase() {
        // Nested: cmdsub inside procsub. Each scanner should bound its
        // own body correctly; the inner `$(case ... esac)` is handled
        // by readCommandSubstitution (already case-aware); the outer
        // procsub bound must NOT trip on the inner `)`s.
        val src = "cat <( echo \"\$(case x in foo) echo a;; esac)\" )"
        val chunk =
            firstChunk(src) as? WordChunk.ProcessSubstitution
                ?: fail("expected ProcessSubstitution")
        // The outer procsub raw text must end with the procsub-closing `)`
        // (everything between `<(` and that `)`), and contain `esac`.
        assertTrue(
            chunk.rawText.contains("esac"),
            "nested cmdsub inside procsub mis-bounded outer. Got: `${chunk.rawText}`",
        )
    }
}
