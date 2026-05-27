package com.accucodeai.kash.interpreter

import com.accucodeai.kash.shared.regex.LinearRegex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural contract for [GlobToRegex] — bash glob → RE2 source.
 *
 * Two kinds of assertions:
 *  - Translation shape (`assertEquals` on the regex source) for stable invariants.
 *  - Semantic equivalence (compile the result with [LinearRegex] and check full-match
 *    against representative inputs). Semantic assertions catch translator drift even
 *    when the literal source changes.
 */
class GlobToRegexTest {
    private fun matches(
        glob: String,
        s: String,
    ): Boolean {
        val src = GlobToRegex.translate(glob) ?: error("expected translatable glob: $glob")
        return LinearRegex("^(?:$src)$", "s").containsMatch(s)
    }

    @Test fun emptyPattern() {
        assertEquals("", GlobToRegex.translate(""))
    }

    @Test fun literalNoMeta() {
        assertEquals("foo", GlobToRegex.translate("foo"))
        assertTrue(matches("foo", "foo"))
        assertTrue(!matches("foo", "foox"))
    }

    @Test fun literalWithRe2MetaEscaped() {
        // RE2 metacharacters in literal positions must be escaped.
        val src = GlobToRegex.translate("a.b+c(d)e|f^g\$h{i}j") ?: error("translate failed")
        // Sanity: result should compile and match literally.
        assertTrue(LinearRegex("^(?:$src)$", "s").containsMatch("a.b+c(d)e|f^g\$h{i}j"))
    }

    @Test fun starMatchesAnyRun() {
        assertEquals(".*", GlobToRegex.translate("*"))
        assertTrue(matches("*", ""))
        assertTrue(matches("*", "abc"))
        assertTrue(matches("*", "a\nb")) // dotall — bash `*` matches newline
    }

    @Test fun questionMatchesOneChar() {
        assertEquals(".", GlobToRegex.translate("?"))
        assertTrue(matches("?", "a"))
        assertTrue(!matches("?", ""))
        assertTrue(!matches("?", "ab"))
    }

    @Test fun starQuestionCombined() {
        assertTrue(matches("a*b", "ab"))
        assertTrue(matches("a*b", "azzzb"))
        assertTrue(matches("?tr*", "Xtring"))
        assertTrue(!matches("?tr*", "tring")) // ? requires one char first
    }

    @Test fun adjacentStarsCollapse() {
        // Both `**` and `*` should translate to `.*`.
        assertEquals(".*", GlobToRegex.translate("**"))
        assertEquals(".*", GlobToRegex.translate("***"))
    }

    @Test fun backslashEscape() {
        // `\*` matches literal `*`.
        assertTrue(matches("\\*", "*"))
        assertTrue(!matches("\\*", "x"))
        // `\\` matches literal `\`.
        assertTrue(matches("\\\\", "\\"))
        // `\a` matches literal `a` (non-special char unaffected).
        assertTrue(matches("\\a", "a"))
    }

    @Test fun trailingBackslashIsLiteral() {
        assertTrue(matches("foo\\", "foo\\"))
    }

    @Test fun bracketClass() {
        assertTrue(matches("[abc]", "a"))
        assertTrue(matches("[abc]", "b"))
        assertTrue(matches("[abc]", "c"))
        assertTrue(!matches("[abc]", "d"))
        assertTrue(matches("[a-z]", "m"))
        assertTrue(!matches("[a-z]", "M"))
    }

    @Test fun bracketNegation() {
        // bash `[!...]` → RE2 `[^...]`
        assertTrue(matches("[!abc]", "d"))
        assertTrue(!matches("[!abc]", "a"))
        // `[^...]` works the same way.
        assertTrue(matches("[^abc]", "d"))
        assertTrue(!matches("[^abc]", "a"))
    }

    @Test fun bracketLeadingCloseBracket() {
        // POSIX rule: `]` immediately after `[` (or `[!` / `[^`) is a member.
        assertTrue(matches("[]abc]", "]"))
        assertTrue(matches("[]abc]", "a"))
        assertTrue(matches("[!]abc]", "d"))
        assertTrue(!matches("[!]abc]", "]"))
    }

    @Test fun posixCharacterClass() {
        assertTrue(matches("[[:alnum:]]", "a"))
        assertTrue(matches("[[:alnum:]]", "5"))
        assertTrue(!matches("[[:alnum:]]", "!"))
        assertTrue(matches("[[:alpha:]_]", "_"))
        assertTrue(matches("[[:digit:]]", "7"))
        assertTrue(!matches("[[:digit:]]", "a"))
    }

    @Test fun unterminatedBracketIsLiteral() {
        // bash tolerance: `[` with no matching `]` is a literal character.
        assertTrue(matches("[abc", "[abc"))
        assertTrue(matches("[", "["))
        // `[[fu]b` parses as bracket class `[[fu]` (members `[`/`f`/`u`)
        // then literal `b`. Verify that's what we produce — matches one
        // of {[,f,u} then `b`, NOT the literal sequence `[[fu]b`.
        assertTrue(matches("[[fu]b", "[b"))
        assertTrue(matches("[[fu]b", "fb"))
        assertTrue(matches("[[fu]b", "ub"))
        assertTrue(!matches("[[fu]b", "ab"))
    }

    @Test fun extglobReturnsNull() {
        // Caller must fall back to recursive matcher for these.
        assertNull(GlobToRegex.translate("?(foo|bar)"))
        assertNull(GlobToRegex.translate("*(foo)"))
        assertNull(GlobToRegex.translate("+(foo)"))
        assertNull(GlobToRegex.translate("@(foo|bar)"))
        assertNull(GlobToRegex.translate("!(foo)"))
        // Embedded extglob also bails.
        assertNull(GlobToRegex.translate("x@(a|b)y"))
    }

    @Test fun collatingElementBails() {
        // `[.X.]` (collating) and `[=X=]` (equivalence) → null, fall back.
        assertNull(GlobToRegex.translate("[[.ch.]]"))
        assertNull(GlobToRegex.translate("[[=a=]]"))
    }

    @Test fun newExp8Patterns() {
        // The actual patterns from new-exp8.sub — verify all translate and
        // match against representative inputs from that test.
        assertNotNull(GlobToRegex.translate("str"))
        assertNotNull(GlobToRegex.translate("[^;]"))
        assertNotNull(GlobToRegex.translate("[[:alnum:]_]"))
        // [[fu]b is malformed but should translate (or null gracefully).
        assertNotNull(GlobToRegex.translate("[[:alnum:]][[fu]b"))
        assertNotNull(GlobToRegex.translate("?tr"))
        assertNotNull(GlobToRegex.translate("?tr\\"))
        assertNotNull(GlobToRegex.translate("[[:alnum:]]_"))
        assertNotNull(GlobToRegex.translate("*tr"))

        // Semantic spot-checks.
        assertTrue(matches("[^;]", "x"))
        assertTrue(!matches("[^;]", ";"))
        assertTrue(matches("?tr", "str"))
        assertTrue(matches("*tr", "xxxxtr"))
        assertTrue(matches("[[:alnum:]_]", "_"))
    }
}
